package dev.plogging.glasses

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where the frames come from. */
enum class SourceKind(val title: String, val explanation: String) {
  GLASSES(
      "Meta glasses",
      "The live camera of the glasses, through the Meta AI app.",
  ),
  MOCK_VIDEO(
      "Simulated glasses: demo video",
      "The Meta SDK streams the demo video as if it came from Meta Ray-Ban Display glasses.",
  ),
  MOCK_PHONE_CAMERA(
      "Simulated glasses: phone camera",
      "The Meta SDK streams the back camera of this phone as if it came from the glasses.",
  ),
  VIDEO_FILE(
      "Demo video, no Meta SDK",
      "Plays the demo video straight into the detector.",
  ),
}

data class UiState(
    val sourceKind: SourceKind = SourceKind.GLASSES,
    val isRunning: Boolean = false,
    val status: String = "Ready",
    val detections: List<Detection> = emptyList(),
    val detectionsPerSecond: Int = 0,
    val inferenceMilliseconds: Float = 0f,
    val glassesRegistered: Boolean = false,
    val errorMessage: String? = null,
    val reportSent: Int = 0,
    val reportQueued: Int = 0,
    val reportError: String? = null,
    val hasLocation: Boolean = false,
    val apiUrl: String = DEFAULT_API_URL,
    val picks: Int = 0,
    val lastPick: Pick? = null,
    val tiltDegrees: Float? = null,
) {
  /** Glasses mode needs the app to be registered with the Meta AI app first. */
  val needsRegistration: Boolean
    get() = sourceKind == SourceKind.GLASSES && !glassesRegistered
}

/** The state of the single screen: picks a source, runs the detector on its frames. */
class AppViewModel(application: Application) : AndroidViewModel(application) {

  private val _uiState = MutableStateFlow(UiState())
  val uiState: StateFlow<UiState> = _uiState.asStateFlow()

  /** The latest frame, kept apart from the rest so that 24 updates a second redraw only the picture. */
  private val _frame = MutableStateFlow<Bitmap?>(null)
  val frame: StateFlow<Bitmap?> = _frame.asStateFlow()

  private val glasses = GlassesSource(application)
  private val location = PhoneLocation(application)
  private val reporter = SightingReporter(application)
  private var glassesReady = false
  private var detector: ObjectDetector? = null
  private var pickDetector = PickDetector()
  private var tone: ToneGenerator? = null
  private var run: Job? = null

  init {
    _uiState.update { it.copy(apiUrl = reporter.apiUrl) }
    reporter.start(viewModelScope)
    viewModelScope.launch {
      reporter.status.collect { report ->
        _uiState.update {
          it.copy(
              reportSent = report.sent,
              reportQueued = report.queued,
              reportError = report.error,
          )
        }
      }
    }
    viewModelScope.launch {
      location.fix.collect { fix -> _uiState.update { it.copy(hasLocation = fix != null) } }
    }
  }

  // Holds one waiting frame. While the detector is busy, a newer frame replaces it,
  // so detection never falls behind the camera.
  private val pendingFrames =
      MutableSharedFlow<Bitmap>(
          extraBufferCapacity = 1,
          onBufferOverflow = BufferOverflow.DROP_OLDEST,
      )

  // MARK: Actions

  /** The glasses need Bluetooth. Call with the user's answer to the Android permission prompt. */
  fun onBluetoothPermission(granted: Boolean) {
    if (!granted || glassesReady) return
    val error = glasses.initialize()
    if (error != null) {
      _uiState.update { it.copy(errorMessage = error) }
      return
    }
    glassesReady = true
    viewModelScope.launch {
      glasses.registered.collect { registered ->
        _uiState.update { it.copy(glassesRegistered = registered) }
      }
    }
  }

  fun select(kind: SourceKind) {
    if (!_uiState.value.isRunning) _uiState.update { it.copy(sourceKind = kind) }
  }

  /**
   * @param requestGlassesCamera asks for the camera of the glasses in the Meta AI app.
   * @param requestPhoneCamera asks Android for the camera of this phone.
   * @param requestLocation asks Android for this phone's position, used on each sighting.
   */
  fun start(
      requestGlassesCamera: suspend () -> Boolean,
      requestPhoneCamera: suspend () -> Boolean,
      requestLocation: suspend () -> Boolean,
  ) {
    if (_uiState.value.isRunning) return
    val kind = _uiState.value.sourceKind
    pickDetector = PickDetector()
    _uiState.update {
      it.copy(
          isRunning = true,
          errorMessage = null,
          status = "Loading the model…",
          picks = 0,
          lastPick = null,
          tiltDegrees = null,
      )
    }

    run = viewModelScope.launch {
      var endStatus = "Stopped"
      var detecting: Job? = null
      try {
        val detector = loadDetector()
        requestLocation()
        location.start()
        reporter.beginSession()
        detecting = launch(Dispatchers.Default) { pendingFrames.collect { detect(it, detector) } }

        val onFrame: (Bitmap) -> Unit = { frame ->
          _frame.value = frame
          pendingFrames.tryEmit(frame)
        }

        if (kind == SourceKind.VIDEO_FILE) {
          status("Playing the demo video")
          VideoFileSource(demoVideo()).play(onFrame)
        } else {
          if (!glassesReady) {
            throw IllegalStateException("Allow Nearby devices (Bluetooth) for this app in Settings.")
          }
          val feed =
              when (kind) {
                SourceKind.MOCK_VIDEO -> GlassesSource.Feed.MockVideo(Uri.fromFile(demoVideo()))
                SourceKind.MOCK_PHONE_CAMERA -> {
                  if (!requestPhoneCamera()) {
                    throw IllegalStateException("Allow camera access for this app in Settings.")
                  }
                  GlassesSource.Feed.MockPhoneCamera
                }
                else -> GlassesSource.Feed.Glasses
              }
          glasses.stream(
              feed = feed,
              requestGlassesCamera = requestGlassesCamera,
              onStatus = ::status,
              onError = { message -> _uiState.update { it.copy(errorMessage = message) } },
              onFrame = onFrame,
              onMotion = ::onMotion,
          )
          endStatus = "The stream ended"
        }
      } catch (e: CancellationException) {
        // Stopped by the user.
        throw e
      } catch (e: Exception) {
        endStatus = "Failed"
        _uiState.update { it.copy(errorMessage = e.message ?: e.toString()) }
      } finally {
        detecting?.cancel()
        location.stop()
        reporter.endSession()
        _frame.value = null
        _uiState.update {
          it.copy(
              isRunning = false,
              status = endStatus,
              detections = emptyList(),
              detectionsPerSecond = 0,
              tiltDegrees = null,
          )
        }
      }
    }
  }

  fun stop() {
    run?.cancel()
  }

  /** Opens the Meta AI app to register this app with the glasses. */
  fun connectGlasses(activity: Activity) {
    if (glassesReady) glasses.register(activity)
  }

  fun disconnectGlasses(activity: Activity) {
    stop()
    if (glassesReady) glasses.unregister(activity)
  }

  fun dismissError() {
    _uiState.update { it.copy(errorMessage = null) }
  }

  fun setApiUrl(value: String) {
    reporter.updateApiUrl(value)
    _uiState.update { it.copy(apiUrl = value) }
  }

  // MARK: Private

  private val resultTimes = ArrayDeque<Long>()

  /** Runs on a background thread, one frame at a time. */
  private fun detect(frame: Bitmap, detector: ObjectDetector) {
    val startedAt = System.nanoTime()
    val detections = detector.detect(frame)
    val now = System.nanoTime()

    resultTimes.addLast(now)
    while (now - resultTimes.first() > 1_000_000_000L) resultTimes.removeFirst()

    reporter.note(detections, location.fix.value, System.currentTimeMillis())
    val pick = pickDetector.onDetections(detections, System.currentTimeMillis())

    _uiState.update {
      if (!it.isRunning) return@update it
      it.copy(
          detections = detections,
          detectionsPerSecond = resultTimes.size,
          inferenceMilliseconds = (now - startedAt) / 1_000_000f,
          picks = if (pick != null) it.picks + 1 else it.picks,
          lastPick = pick ?: it.lastPick,
      )
    }
    if (pick != null) playPickTone()
  }

  private fun onMotion(reading: MotionReading) {
    val pick = pickDetector.onMotion(reading, System.currentTimeMillis())
    _uiState.update {
      if (!it.isRunning) return@update it
      it.copy(
          tiltDegrees = pickDetector.tiltDegrees,
          picks = if (pick != null) it.picks + 1 else it.picks,
          lastPick = pick ?: it.lastPick,
      )
    }
    if (pick != null) playPickTone()
  }

  private fun playPickTone() {
    try {
      val generator = tone ?: ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80).also { tone = it }
      generator.startTone(ToneGenerator.TONE_PROP_ACK, 180)
    } catch (_: RuntimeException) {}
  }

  private suspend fun loadDetector(): ObjectDetector =
      detector
          ?: withContext(Dispatchers.Default) { ObjectDetector.load(getApplication()) }
              .also { detector = it }

  private suspend fun demoVideo() =
      withContext(Dispatchers.IO) { DemoVideo.file(getApplication()) }

  private fun status(text: String) {
    _uiState.update { it.copy(status = text) }
  }

  override fun onCleared() {
    location.stop()
    reporter.endSession()
    detector?.close()
    tone?.release()
    tone = null
  }
}
