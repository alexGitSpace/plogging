package dev.plogging.glasses

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import java.util.concurrent.Executors

/**
 * MediaPipe hand skeleton for one frame. [available] is false when the model asset is missing,
 * and the camera keeps running without it.
 */
class HandTracker private constructor(private val landmarker: HandLandmarker?) : AutoCloseable {

  val available: Boolean = landmarker != null

  private val runner = Executors.newSingleThreadExecutor()

  fun detect(frame: Bitmap): List<HandPose> {
    val marker = landmarker ?: return emptyList()
    return runner
        .submit<List<HandPose>> {
          try {
            val image = BitmapImageBuilder(frame).build()
            try {
              marker.detect(image).landmarks().map { landmarks ->
                HandPose(landmarks.map { HandPoint(it.x(), it.y()) })
              }
            } finally {
              image.close()
            }
          } catch (_: RuntimeException) {
            emptyList()
          }
        }
        .get()
  }

  override fun close() {
    runner.submit { landmarker?.close() }.get()
    runner.shutdown()
  }

  companion object {
    const val MODEL_ASSET = "hand_landmarker.task"

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
