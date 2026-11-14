package dev.plogging.glasses

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.view.View
import androidx.activity.ComponentActivity
import com.meta.wearable.dat.camera.Camera
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.DatError
import com.meta.wearable.dat.core.types.DatResult
import com.meta.wearable.dat.core.types.DeviceType
import com.meta.wearable.dat.core.types.DonState
import com.meta.wearable.dat.core.types.HingeState
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import com.meta.wearable.dat.display.Display
import com.meta.wearable.dat.display.addDisplay
import com.meta.wearable.dat.display.types.DisplayState
import com.meta.wearable.dat.display.views.Alignment
import com.meta.wearable.dat.display.views.CornerRadius
import com.meta.wearable.dat.display.views.Direction
import com.meta.wearable.dat.display.views.FlexBoxBackground
import com.meta.wearable.dat.display.views.IconName
import com.meta.wearable.dat.display.views.TextStyle
import com.meta.wearable.dat.mockdevice.MockDeviceKit
import com.meta.wearable.dat.mockdevice.api.GlassesModel
import com.meta.wearable.dat.mockdevice.api.MockDeviceKitConfig
import com.meta.wearable.dat.mockdevice.api.camera.CameraFacing
import com.meta.wearable.dat.mockdevice.api.camera.MockCameraKit
import com.meta.wearable.dat.motion.Motion
import com.meta.wearable.dat.motion.addMotion
import com.meta.wearable.dat.motion.types.MotionConfiguration
import com.meta.wearable.dat.motion.types.MotionSamplingRate
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The camera and the display of the Meta glasses, through the Meta Wearables Device Access
 * Toolkit.
 *
 * This is the only file that talks to the Meta SDK. It can also pair simulated glasses (the
 * SDK's MockDeviceKit), which go through exactly the same session and stream code as real ones.
 */
class GlassesSource(private val context: Context) {

  sealed interface Feed {
    /** Real glasses, connected through the Meta AI app. */
    data object Glasses : Feed

    /** Simulated glasses that play a video file (H.264 or HEVC). */
    data class MockVideo(val video: Uri) : Feed

    /** Simulated glasses that show what the back camera of the phone sees. */
    data object MockPhoneCamera : Feed
  }

  class GlassesException(message: String) : Exception(message)

  private companion object {
    /** The least time between two cards on the display. */
    const val DISPLAY_INTERVAL_MS = 1_000L

    /** How often to look for a new card while there is none. */
    const val DISPLAY_POLL_MS = 100L

    /** A card that is not repeated for this long gives way to the looking card. */
    const val STALE_MS = 2_000L

    /** Simulated glasses answer within milliseconds. */
    const val SIMULATED_ANSWER_MS = 1_000L

    const val DISPLAY_ATTEMPTS = 5
    const val DISPLAY_RETRY_MS = 1_000L
  }

  private var mockEnabled = false

  // MARK: App setup

  /**
   * Call once the Bluetooth permission is granted, before anything else in this class. Returns
   * an error text if the SDK could not start.
   */
  fun initialize(): String? = Wearables.initialize(context).errorOrNull()?.description

  // MARK: Registration

  /** Whether this app is registered with the Meta AI app. */
  val registered: Flow<Boolean>
    get() = Wearables.registrationState.map { it == RegistrationState.REGISTERED }

  /** Opens the Meta AI app, where the user confirms this app. */
  fun register(activity: Activity) = Wearables.startRegistration(activity)

  fun unregister(activity: Activity) = Wearables.startUnregistration(activity)

  // MARK: Streaming

  /**
   * Connects, starts the camera and runs until the stream ends by itself (the glasses were
   * folded or went out of range) or the caller is cancelled. Throws [GlassesException] if it
   * cannot start. [onFrame] is called on a background thread for every frame. [onMotion] is
   * called for each accelerometer sample when the glasses allow the motion capability.
   */
  suspend fun stream(
      feed: Feed,
      requestGlassesCamera: suspend () -> Boolean,
      onStatus: (String) -> Unit,
      onError: (String) -> Unit,
      onFrame: (Bitmap) -> Unit,
      onMotion: (MotionReading) -> Unit,
  ) = coroutineScope {
    var session: DeviceSession? = null
    var camera: Camera? = null
    var motion: Motion? = null
    try {
      when (feed) {
        Feed.Glasses ->
            if (Wearables.registrationState.value != RegistrationState.REGISTERED) {
              throw GlassesException(
                  "Connect the glasses first: tap Connect glasses and confirm in the Meta AI app.",
              )
            }
        is Feed.MockVideo -> pairMockGlasses { it.setCameraFeed(feed.video) }
        Feed.MockPhoneCamera -> pairMockGlasses { it.setCameraFeed(CameraFacing.BACK) }
      }

      // This phone can have a Ray-Ban Meta and a Meta Ray-Ban Display connected at
      // once. The default selector takes the first connected pair, which is the
      // folded Ray-Ban Meta, and the session then fails as device unavailable.
      onStatus("Looking for Meta Ray-Ban Display…")
      val selector = AutoDeviceSelector { it.deviceType == DeviceType.META_RAYBAN_DISPLAY }
      val deviceId = selector.activeDeviceFlow().filterNotNull().first()
      onStatus("Unfold the glasses and put them on…")
      Wearables.devicesMetadata[deviceId]?.first { device ->
        device.hingeState != HingeState.CLOSED && device.donState != DonState.DOFFED
      }

      // 2. Open a session with them.
      onStatus("Connecting to the glasses…")
      val opened = Wearables.createSession(selector).orThrow()
      session = opened
      launch { opened.errors.collect { onError(it.userMessage()) } }
      opened.start()
      val state =
          opened.state.first {
            it == DeviceSessionState.STARTED || it == DeviceSessionState.STOPPED
          }
      if (state == DeviceSessionState.STOPPED) {
        throw GlassesException("The session with the glasses ended before the camera started.")
      }

      // 3. Ask for the camera. The first time this switches to the Meta AI app.
      val permission = Wearables.checkPermissionStatus(Permission.CAMERA).getOrNull()
      if (permission != PermissionStatus.Granted) {
        onStatus("Allow the camera in the Meta AI app…")
        if (!requestGlassesCamera()) {
          throw GlassesException("The camera of the glasses was not allowed in the Meta AI app.")
        }
      }

      // 4. Start the camera stream. Uncompressed frames need no decoder; low quality
      //    (360x640) is enough for the detector and survives Bluetooth best.
      val added =
          opened
              .addCamera(StreamConfiguration(videoQuality = VideoQuality.LOW, frameRate = 24))
              .orThrow()
      camera = added
      launch(Dispatchers.Default) {
        added.stream.videoStream.collect { frame -> frame.toBitmap()?.let(onFrame) }
      }
      launch { added.stream.errorStream.collect { onError(it.userMessage()) } }

      motion =
          opened
              .addMotion(MotionConfiguration(samplingRate = MotionSamplingRate.HZ_15))
              .fold(onSuccess = { it }, onFailure = { _, _ -> null })
      motion?.let { capability ->
        launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
          capability.samples.collect { sample ->
            sample.accelerometer?.let { accel ->
              onMotion(MotionReading(sample.timestampNs, accel.x, accel.y, accel.z))
            }
          }
        }
        capability.start()
      }

      onStatus("Starting the camera…")
      added.stream.start().orThrow()

      // The display is an extra: whatever goes wrong with it, the camera goes on.
      launch(Dispatchers.IO) { runDisplay(opened, simulated = feed != Feed.Glasses) }

      // 5. Follow the stream until it ends. A stream that has not started yet also
      //    reports STOPPED: that is not the end.
      var hasStarted = false
      added.stream.state.first { streamState ->
        val ended = streamState == StreamState.STOPPED || streamState == StreamState.CLOSED
        when (streamState) {
          StreamState.STARTING,
          StreamState.STARTED -> onStatus("Starting the camera…")
          StreamState.STREAMING -> onStatus("Streaming from the glasses")
          StreamState.PAUSED -> onStatus("Paused. Tap the side of the glasses to resume")
          StreamState.STOPPING -> onStatus("Stopping…")
          StreamState.STOPPED,
          StreamState.CLOSED -> Unit
        }
        if (!ended) hasStarted = true
        ended && hasStarted
      }
      coroutineContext.cancelChildren()
    } finally {
      motion?.stop()
      camera?.stop()
      // Stopping the session also takes the card off the display.
      session?.stop()
      card = null
      _displayStatus.value = null
      _displayPreview.value = null
      if (mockEnabled) {
        mockEnabled = false
        // Back to real glasses: the mock kit replaces them while it is enabled.
        MockDeviceKit.getInstance(context).disable()
      }
    }
  }

  // MARK: Display

  private val _displayStatus = MutableStateFlow<String?>(null)

  /** What the display of the glasses is doing, in a few words. Null while nothing streams. */
  val displayStatus: StateFlow<String?> = _displayStatus.asStateFlow()

  private val _displayPreview = MutableStateFlow<((Context) -> View)?>(null)

  /**
   * Makes a view of what the display shows. Only simulated glasses have one: real glasses
   * do not send their screen back.
   */
  val displayPreview: StateFlow<((Context) -> View)?> = _displayPreview.asStateFlow()

  @Volatile private var card: DisplayCard? = null
  @Volatile private var cardAt = 0L

  /**
   * Puts [card] on the display of the glasses while they stream. Null, or a card that is not
   * repeated for [STALE_MS], leaves the looking card up: the display stays filled for the
   * whole session.
   */
  fun show(card: DisplayCard?) {
    this.card = card
    cardAt = SystemClock.elapsedRealtime()
  }

  // MARK: Private

  /**
   * Keeps the display showing the latest card until the caller is cancelled. Every send
   * replaces the whole screen and crosses Bluetooth, so cards go one at a time with a pause
   * between them, and only when there is a new one.
   */
  private suspend fun runDisplay(session: DeviceSession, simulated: Boolean) {
    try {
      _displayStatus.value = "Starting the display…"
      val display = attachDisplay(session) ?: return
      display.state.first { it == DisplayState.STARTED }

      // The display stays filled until the session ends. With nothing spotted, it shows the
      // looking card instead of going blank.
      var shown: DisplayCard? = null
      while (true) {
        val fresh = card?.takeIf { SystemClock.elapsedRealtime() - cardAt < STALE_MS }
        val wanted = fresh ?: DisplayCard.LOOKING
        if (wanted === shown) {
          delay(DISPLAY_POLL_MS)
          continue
        }
        val error = display.send(wanted, simulated)
        if (error == null) shown = wanted
        reportDisplay(error)
        delay(DISPLAY_INTERVAL_MS)
      }
    } catch (e: CancellationException) {
      // The stream is over: leave no report behind, whatever was said a moment ago.
      _displayStatus.value = null
      throw e
    } catch (e: Exception) {
      reportDisplay(e.message ?: e.toString())
    }
  }

  /** The glasses can refuse while they are still settling in: asks a few times. */
  private suspend fun attachDisplay(session: DeviceSession): Display? {
    repeat(DISPLAY_ATTEMPTS) { attempt ->
      if (attempt > 0) delay(DISPLAY_RETRY_MS)
      val result = session.addDisplay()
      result.getOrNull()?.let { return it }
      reportDisplay(result.errorOrNull()?.description)
    }
    return null
  }

  private fun reportDisplay(error: String?) {
    _displayStatus.value = if (error == null) "Display on" else "Display: $error"
  }

  /** Returns what went wrong, or null if the glasses took the card. */
  private suspend fun Display.send(card: DisplayCard, simulated: Boolean): String? =
      try {
        // Simulated glasses answer at once, now and then before the SDK starts to listen for
        // the answer. It then waits five seconds for what has already come: do not wait with it.
        val result =
            if (simulated) withTimeoutOrNull(SIMULATED_ANSWER_MS) { put(card) } else put(card)
        result?.errorOrNull()?.description
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        e.message ?: e.toString()
      }

  private suspend fun Display.put(card: DisplayCard) =
      sendContent {
        // Words first. The lens only shows the top of the display, and a picture above the
        // words hides them: what is left looks like a small mark.
        flexBox(gap = 12, padding = 24, background = FlexBoxBackground.CARD) {
          text(card.title, style = TextStyle.HEADING)
          flexBox(direction = Direction.ROW, gap = 8, crossAlignment = Alignment.CENTER) {
            icon(card.sign.icon)
            text(card.detail, style = TextStyle.BODY)
          }
          card.picture?.let { image(bitmap = it, cornerRadius = CornerRadius.MEDIUM) }
        }
      }

  private val DisplayCard.Sign.icon: IconName
    get() =
        when (this) {
          DisplayCard.Sign.LEFT -> IconName.ARROW_LEFT
          DisplayCard.Sign.RIGHT -> IconName.ARROW_RIGHT
          DisplayCard.Sign.AHEAD -> IconName.CARET_UP
          DisplayCard.Sign.LOOKING -> IconName.EYE
        }

  private fun pairMockGlasses(setFeed: (MockCameraKit) -> Unit) {
    val kit = MockDeviceKit.getInstance(context)
    // Starts registered and with the camera allowed, so the Meta AI app is not needed.
    kit.enable(MockDeviceKitConfig())
    mockEnabled = true
    val glasses = kit.pairGlasses(GlassesModel.META_RAYBAN_DISPLAY).orThrow()
    setFeed(glasses.services.camera)
    _displayPreview.value = glasses.services.display::createPreviewView
    // Glasses become available once they are on, unfolded and worn.
    glasses.powerOn()
    glasses.unfold()
    glasses.don()
  }

  private fun <T, E : DatError> DatResult<T, E>.orThrow(): T =
      fold(
          onSuccess = { it },
          onFailure = { error, _ -> throw GlassesException(error.userMessage()) },
      )
}

private fun DatError.userMessage(): String {
  val raw = description
  return when {
    "DEVICE_UNAVAILABLE" in raw ->
        "Meta Ray-Ban Display is not ready. Unfold them, put them on, and tap Start."
    "DAT_APP" in raw || "DWA_" in raw ->
        "Update the glasses in the Meta AI app: Device Access Toolkit has to be installed on the Meta Ray-Ban Display."
    else -> raw
  }
}

/**
 * Asks, through the Meta AI app, to use the camera of the glasses. Create it in
 * `Activity.onCreate`: Android only lets an activity register for results before it starts.
 */
class GlassesCameraPermission(activity: ComponentActivity) {
  private var continuation: CancellableContinuation<Boolean>? = null

  private val launcher =
      activity.registerForActivityResult(Wearables.RequestPermissionContract()) { result ->
        continuation?.resume(result.getOrNull() == PermissionStatus.Granted)
        continuation = null
      }

  /** Returns whether the user allowed it. */
  suspend fun request(): Boolean = suspendCancellableCoroutine { waiting ->
    continuation = waiting
    waiting.invokeOnCancellation { continuation = null }
    launcher.launch(Permission.CAMERA)
  }
}

/** Uncompressed frames arrive as I420: a full-size Y plane, then quarter-size U and V planes. */
private fun VideoFrame.toBitmap(): Bitmap? {
  if (isCompressed || width <= 0 || height <= 0) return null
  return i420ToBitmap(buffer, width, height)
}

private fun i420ToBitmap(buffer: ByteBuffer, width: Int, height: Int): Bitmap? {
  val chromaWidth = (width + 1) / 2
  val chromaHeight = (height + 1) / 2
  val ySize = width * height
  val chromaSize = chromaWidth * chromaHeight
  if (buffer.remaining() < ySize + 2 * chromaSize) return null

  val data = ByteArray(ySize + 2 * chromaSize)
  buffer.duplicate().get(data)

  val pixels = IntArray(ySize)
  for (row in 0 until height) {
    val yRow = row * width
    val uRow = ySize + (row / 2) * chromaWidth
    val vRow = uRow + chromaSize
    for (column in 0 until width) {
      // BT.601, video range, in fixed point.
      val y = ((data[yRow + column].toInt() and 0xFF) - 16).coerceAtLeast(0) * 1192
      val u = (data[uRow + column / 2].toInt() and 0xFF) - 128
      val v = (data[vRow + column / 2].toInt() and 0xFF) - 128
      val red = ((y + 1634 * v) shr 10).coerceIn(0, 255)
      val green = ((y - 833 * v - 400 * u) shr 10).coerceIn(0, 255)
      val blue = ((y + 2066 * u) shr 10).coerceIn(0, 255)
      pixels[yRow + column] = (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
    }
  }
  return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
}
