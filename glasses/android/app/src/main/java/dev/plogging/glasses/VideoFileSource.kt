package dev.plogging.glasses

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** The demo video that `glasses/scripts/setup.sh` puts into the app. */
object DemoVideo {
  private const val ASSET = "demo.mp4"

  /** Copies the video out of the APK. Replaces the cached file when the asset changed. */
  fun file(context: Context): File {
    val file = File(context.cacheDir, ASSET)
    try {
      context.assets.open(ASSET).use { input ->
        val bytes = input.readBytes()
        if (!file.exists() || file.length() != bytes.size.toLong()) {
          file.writeBytes(bytes)
        }
      }
    } catch (e: FileNotFoundException) {
      throw SetupException(ASSET)
    }
    return file
  }
}

/**
 * Plays a video file as if it were a camera: frames arrive at the pace of the video, and it
 * starts again when it ends. Used to try the detector without glasses and without the Meta SDK.
 */
class VideoFileSource(private val file: File) {

  /** Runs until cancelled. [onFrame] is called on a background thread. */
  suspend fun play(onFrame: (Bitmap) -> Unit) =
      withContext(Dispatchers.Default) {
        val retriever = MediaMetadataRetriever()
        try {
          retriever.setDataSource(file.path)
          val frameCount =
              retriever
                  .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
                  ?.toIntOrNull() ?: 0
          val durationMs =
              retriever
                  .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                  ?.toLongOrNull() ?: 0
          if (frameCount <= 0 || durationMs <= 0) {
            throw IllegalStateException("No video frames in ${file.name}")
          }
          val frameMs = durationMs / frameCount

          while (true) {
            for (index in 0 until frameCount) {
              ensureActive()
              val startedAt = System.currentTimeMillis()
              retriever.getFrameAtIndex(index)?.let(onFrame)
              delay((frameMs - (System.currentTimeMillis() - startedAt)).coerceAtLeast(0))
            }
          }
        } finally {
          retriever.release()
        }
      }
}
