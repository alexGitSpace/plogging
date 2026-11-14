package dev.plogging.glasses

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * MediaPipe hand skeleton for one frame. [available] is false when the model asset is missing,
 * and the camera keeps running without it.
 *
 * Frames are shrunk before the model, and video mode tracks the hand instead of finding it
 * again every frame. Call [detect] from one thread at a time.
 */
class HandTracker private constructor(private val landmarker: HandLandmarker?) : AutoCloseable {

  val available: Boolean = landmarker != null

  private var lastTimestampMs = 0L

  fun detect(frame: Bitmap): List<HandPose> {
    val marker = landmarker ?: return emptyList()
    var timestampMs = SystemClock.elapsedRealtime()
    if (timestampMs <= lastTimestampMs) timestampMs = lastTimestampMs + 1
    lastTimestampMs = timestampMs
    return try {
      val image = BitmapImageBuilder(frame).build()
      try {
        marker.detectForVideo(image, timestampMs).landmarks().map { landmarks ->
          HandPose(landmarks.map { HandPoint(it.x(), it.y()) })
        }
      } finally {
        image.close()
      }
    } catch (_: RuntimeException) {
      emptyList()
    }
  }

  override fun close() {
    landmarker?.close()
  }

  companion object {
    const val MODEL_ASSET = "hand_landmarker.task"

    /** Longest side sent to the model. Landmarks stay in 0..1 of the original frame. */
    const val MAX_SIDE = 192

    fun scaled(frame: Bitmap): Bitmap {
      val longSide = max(frame.width, frame.height)
      if (longSide <= MAX_SIDE) {
        return frame.copy(Bitmap.Config.ARGB_8888, false)
      }
      val scale = MAX_SIDE.toFloat() / longSide
      val width = (frame.width * scale).roundToInt().coerceAtLeast(1)
      val height = (frame.height * scale).roundToInt().coerceAtLeast(1)
      return Bitmap.createScaledBitmap(frame, width, height, true)
    }

    fun open(context: Context): HandTracker {
      val marker =
          try {
            context.assets.open(MODEL_ASSET).close()
            val options =
                HandLandmarker.HandLandmarkerOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL_ASSET).build())
                    .setRunningMode(RunningMode.VIDEO)
                    .setNumHands(1)
                    .setMinHandDetectionConfidence(0.4f)
                    .setMinHandPresenceConfidence(0.4f)
                    .setMinTrackingConfidence(0.4f)
                    .build()
            HandLandmarker.createFromOptions(context, options)
          } catch (_: Exception) {
            null
          }
      return HandTracker(marker)
    }
  }
}
