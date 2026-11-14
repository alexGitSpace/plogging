package dev.plogging.glasses

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Speaks calming plogging lines through ElevenLabs. One line at a time. A pick that
 * arrives while a line is playing waits as the single next line. Idle lines do not queue.
 */
class CalmVoice(
    context: Context,
    private val apiKey: String = BuildConfig.ELEVENLABS_API_KEY,
    private val onLine: (String?) -> Unit,
) {
  private val cacheDir = File(context.cacheDir, "elevenlabs").also { it.mkdirs() }
  private var coach = Coach()
  private val fetchMutex = Mutex()
  private val playerLock = Any()
  private val pendingLabel = AtomicReference<String?>(null)

  @Volatile private var active = false
  private var loopJob: Job? = null
  private var player: MediaPlayer? = null
  private var tone: ToneGenerator? = null
  private var lastSpeakMs = 0L

  fun begin(scope: CoroutineScope) {
    end()
    active = true
    coach = Coach()
    pendingLabel.set(null)
    lastSpeakMs = SystemClock.elapsedRealtime()
    loopJob =
        scope.launch {
          speak(coach.welcome(), beepIfMissing = false)
          launch(Dispatchers.IO) { prefetch() }
          while (isActive && active) {
            val label = pendingLabel.getAndSet(null)
            if (label != null) {
              speak(coach.pick(label), beepIfMissing = true)
              continue
            }
            if (SystemClock.elapsedRealtime() - lastSpeakMs >= QUIET_MS) {
              speak(coach.idle(), beepIfMissing = false)
              continue
            }
            delay(250)
          }
        }
  }

  fun onPick(label: String) {
    if (!active) return
    pendingLabel.set(label)
  }

  fun end() {
    active = false
    pendingLabel.set(null)
    loopJob?.cancel()
    loopJob = null
    synchronized(playerLock) { releasePlayer() }
    tone?.release()
    tone = null
    onLine(null)
  }

  private suspend fun speak(line: String, beepIfMissing: Boolean) {
    if (!active) return
    lastSpeakMs = SystemClock.elapsedRealtime()
    onLine(line)
    try {
      val file = withContext(Dispatchers.IO) { fileFor(line) }
      if (file != null) {
        playFile(file)
      } else {
        if (beepIfMissing) playBeep()
        delay(BEEP_HUD_MS)
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.w(TAG, "Could not speak", e)
      if (beepIfMissing) playBeep()
    } finally {
      onLine(null)
      lastSpeakMs = SystemClock.elapsedRealtime()
    }
  }

  private suspend fun prefetch() {
    for (line in Coach.ALL_LINES) {
      if (!active) return
      fileFor(line)
    }
  }

  private suspend fun fileFor(text: String): File? {
    val cached = cacheFile(text)
    if (cached.length() > 0) return cached
    if (apiKey.isBlank()) return null
    return fetchMutex.withLock {
      if (cached.length() > 0) cached else download(text, cached)
    }
  }

  private fun cacheFile(text: String): File {
    val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
    val name = digest.joinToString("") { byte -> "%02x".format(byte) }.take(16)
    return File(cacheDir, "$name.mp3")
  }

  private fun download(text: String, dest: File): File? {
    val connection =
        URL("$TTS_URL?output_format=mp3_44100_128").openConnection() as HttpURLConnection
    try {
      connection.requestMethod = "POST"
      connection.connectTimeout = 10_000
      connection.readTimeout = 20_000
      connection.doOutput = true
      connection.setRequestProperty("xi-api-key", apiKey)
      connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
      connection.setRequestProperty("Accept", "audio/mpeg")
      val body =
          JSONObject()
              .put("text", text)
              .put("model_id", MODEL_ID)
              .put(
                  "voice_settings",
                  JSONObject()
                      .put("stability", 0.6)
                      .put("similarity_boost", 0.75)
                      .put("speed", 0.85),
              )
              .toString()
      connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
      val code = connection.responseCode
      if (code !in 200..299) {
        Log.w(TAG, "ElevenLabs HTTP $code")
        return null
      }
      val tmp = File(dest.path + ".part")
      connection.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
      if (tmp.length() < MIN_AUDIO_BYTES) {
        tmp.delete()
        return null
      }
      if (dest.exists()) dest.delete()
      if (!tmp.renameTo(dest)) {
        tmp.copyTo(dest, overwrite = true)
        tmp.delete()
      }
      return dest
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.w(TAG, "ElevenLabs request failed", e)
      return null
    } finally {
      connection.disconnect()
    }
  }

  private suspend fun playFile(file: File) =
      withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { waiting ->
          val next =
              try {
                MediaPlayer().apply {
                  setAudioAttributes(
                      AudioAttributes.Builder()
                          .setUsage(AudioAttributes.USAGE_MEDIA)
                          .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                          .build(),
                  )
                  setDataSource(file.absolutePath)
                  setOnCompletionListener {
                    synchronized(playerLock) { releasePlayer() }
                    if (waiting.isActive) waiting.resume(Unit)
                  }
                  setOnErrorListener { _, _, _ ->
                    synchronized(playerLock) { releasePlayer() }
                    if (waiting.isActive) waiting.resume(Unit)
                    true
                  }
                  prepare()
                }
              } catch (e: Exception) {
                Log.w(TAG, "Could not play speech", e)
                if (waiting.isActive) waiting.resume(Unit)
                return@suspendCancellableCoroutine
              }
          synchronized(playerLock) {
            releasePlayer()
            player = next
          }
          waiting.invokeOnCancellation { synchronized(playerLock) { releasePlayer() } }
          try {
            next.start()
          } catch (e: Exception) {
            Log.w(TAG, "Could not start speech", e)
            synchronized(playerLock) { releasePlayer() }
            if (waiting.isActive) waiting.resume(Unit)
          }
        }
      }

  private fun releasePlayer() {
    player?.let { current ->
      try {
        current.stop()
      } catch (_: IllegalStateException) {}
      try {
        current.release()
      } catch (_: IllegalStateException) {}
    }
    player = null
  }

  private fun playBeep() {
    try {
      val generator = tone ?: ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80).also { tone = it }
      generator.startTone(ToneGenerator.TONE_PROP_ACK, 180)
    } catch (_: RuntimeException) {}
  }

  companion object {
    private const val TAG = "CalmVoice"
    private const val VOICE_ID = "EXAVITQu4vr4xnSDxMaL"
    private const val MODEL_ID = "eleven_turbo_v2_5"
    private const val TTS_URL = "https://api.elevenlabs.io/v1/text-to-speech/$VOICE_ID"
    private const val QUIET_MS = 45_000L
    private const val BEEP_HUD_MS = 2_000L
    private const val MIN_AUDIO_BYTES = 100L
  }
}
