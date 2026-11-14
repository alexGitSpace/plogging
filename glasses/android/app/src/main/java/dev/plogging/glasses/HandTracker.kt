package dev.plogging.glasses

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
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
 * Each frame is detected on its own. The glasses already stream at 360x640, so anything larger
 * is shrunk and a missed frame cannot knock the tracker out. Call [detect] from one thread.
 */
class HandTracker private constructor(private val landmarker: HandLandmarker?) : AutoCloseable {

  val available: Boolean = landmarker != null

  fun detect(frame: Bitmap): List<HandPose> {
    val marker = landmarker ?: return emptyList()
    return try {
      val image = BitmapImageBuilder(frame).build()
      try {
        marker.detect(image).landmarks().map { landmarks ->
          HandPose(landmarks.map { HandPoint(it.x(), it.y()) })
        }
      } finally {
        image.close()
      }
    } catch (e: RuntimeException) {
      Log.w(TAG, "Hand detection failed", e)
      emptyList()
    }
  }

  override fun close() {
    landmarker?.close()
  }

  companion object {
    const val MODEL_ASSET = "hand_landmarker.task"
    private const val TAG = "HandTracker"

    /** Longest side sent to the model. The glasses stream is already 640 on its long side. */
    const val MAX_SIDE = 640

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
                    .setRunningMode(RunningMode.IMAGE)
                    .setNumHands(2)
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
