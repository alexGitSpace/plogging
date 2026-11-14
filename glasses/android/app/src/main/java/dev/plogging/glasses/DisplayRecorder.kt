package dev.plogging.glasses

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import java.util.concurrent.atomic.AtomicReference

/**
 * Records the glasses camera with the card that is on the display composited over it.
 *
 * The glasses do not send their own screen back, so the card is drawn here from the same
 * [DisplayCard] the lenses are showing. Call [offer] from the camera thread.
 */
class DisplayRecorder(private val context: Context) {

  var failure: String? = null
    private set

  @Volatile private var running = false

  private val pending = AtomicReference<Shot?>(null)
  private var worker: Thread? = null
  private var savedName: String? = null

  fun arm() {
    if (worker?.isAlive == true) return
    failure = null
    savedName = null
    running = true
    worker = Thread(::loop, "display-recorder").also { it.start() }
  }

  fun offer(frame: Bitmap, card: DisplayCard) {
    if (!running || frame.width < 2 || frame.height < 2) return
    val copy = frame.copy(Bitmap.Config.ARGB_8888, false)
    pending.getAndSet(Shot(copy, card))?.frame?.recycle()
  }

  /** Stops the encoder and returns the file name in Movies/Plogging, or null if nothing was saved. */
  fun finish(): String? {
    running = false
    worker?.join(8_000)
    worker = null
    return savedName
  }

  private fun loop() {
    var encoder: Encoder? = null
    try {
      while (running || pending.get() != null) {
        val shot = pending.getAndSet(null)
        if (shot == null) {
          Thread.sleep(8)
          continue
        }
        try {
          val current = encoder ?: Encoder.open(context, shot.frame.width, shot.frame.height).also { encoder = it }
          current.write(shot.frame, shot.card)
        } finally {
          shot.frame.recycle()
        }
      }
    } catch (e: InterruptedException) {
      Thread.currentThread().interrupt()
    } catch (e: Exception) {
      Log.e(TAG, "Recording failed", e)
      failure = e.message ?: "Recording failed"
    } finally {
      savedName = encoder?.close()
    }
  }

  private class Shot(val frame: Bitmap, val card: DisplayCard)

  private class Encoder(
      private val codec: MediaCodec,
      private val surface: Surface,
      private val muxer: MediaMuxer,
      private val descriptor: ParcelFileDescriptor,
      private val uri: android.net.Uri,
      private val resolver: android.content.ContentResolver,
      private val displayName: String,
      private val width: Int,
      private val height: Int,
  ) {
    private var track = -1
    private var muxerStarted = false
    private var frames = 0L
    private val info = MediaCodec.BufferInfo()

    fun write(frame: Bitmap, card: DisplayCard) {
      val canvas = surface.lockHardwareCanvas()
      try {
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(frame, null, RectF(0f, 0f, width.toFloat(), height.toFloat()), framePaint)
        drawCard(canvas, card, width, height)
      } finally {
        surface.unlockCanvasAndPost(canvas)
      }
      frames++
      drain(end = false)
    }

    fun close(): String? {
      val wrote = frames > 0
      try {
        if (wrote) {
          codec.signalEndOfInputStream()
          drain(end = true)
        }
      } catch (e: Exception) {
        Log.e(TAG, "Stopping the encoder failed", e)
      }
      try {
        if (muxerStarted) muxer.stop()
      } catch (_: Exception) {}
      muxer.release()
      codec.release()
      surface.release()
      descriptor.close()
      if (!wrote) {
        resolver.delete(uri, null, null)
        return null
      }
      val ready = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
      resolver.update(uri, ready, null, null)
      return displayName
    }

    private fun drain(end: Boolean) {
      var waits = 0
      while (true) {
        val index = codec.dequeueOutputBuffer(info, if (end) 10_000 else 0)
        when {
          index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
            if (!end || ++waits > 5) return
          }
          index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
            if (muxerStarted) return
            track = muxer.addTrack(codec.outputFormat)
            muxer.start()
            muxerStarted = true
          }
          index >= 0 -> {
            val buffer = codec.getOutputBuffer(index)
            if (buffer != null && info.size > 0 && muxerStarted &&
                info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
            ) {
              if (info.presentationTimeUs == 0L) {
                info.presentationTimeUs = frames * 1_000_000L / FRAME_RATE
              }
              buffer.position(info.offset)
              buffer.limit(info.offset + info.size)
              muxer.writeSampleData(track, buffer, info)
            }
            codec.releaseOutputBuffer(index, false)
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
          }
          else -> if (!end) return
        }
      }
    }

    companion object {
      fun open(context: Context, frameWidth: Int, frameHeight: Int): Encoder {
        val width = frameWidth - frameWidth % 2
        val height = frameHeight - frameHeight % 2
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
        val displayName = "plogging-$stamp.mp4"
        val values =
            ContentValues().apply {
              put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
              put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
              put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/Plogging")
              put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        val resolver = context.contentResolver
        val uri =
            resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Could not create the video in Movies/Plogging")
        val descriptor =
            resolver.openFileDescriptor(uri, "w")
                ?: throw IllegalStateException("Could not open the video file")
        val format =
            MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
              setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
              setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000)
              setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
              setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = codec.createInputSurface()
        codec.start()
        val muxer = MediaMuxer(descriptor.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        return Encoder(codec, surface, muxer, descriptor, uri, resolver, displayName, width, height)
      }
    }
  }

  private companion object {
    const val TAG = "DisplayRecorder"
    const val FRAME_RATE = 24

    val framePaint = Paint(Paint.FILTER_BITMAP_FLAG)
  }
}

/** The camera, with the glasses card across the bottom so both are in one video. */
private fun drawCard(canvas: Canvas, card: DisplayCard, width: Int, height: Int) {
  val margin = width * 0.03f
  val panel = RectF(margin, height * 0.58f, width - margin, height - margin)
  canvas.drawRoundRect(panel, 18f, 18f, Paint().apply { color = Color.argb(220, 8, 17, 14); isAntiAlias = true })

  val picture = card.picture?.takeUnless { it.isRecycled }
  var textRight = panel.right - 16f
  if (picture != null) {
    val pictureHeight = panel.height() - 28f
    val pictureWidth = pictureHeight * picture.width / picture.height
    val dest = RectF(panel.right - 14f - pictureWidth, panel.top + 14f, panel.right - 14f, panel.bottom - 14f)
    canvas.drawBitmap(picture, null, dest, Paint(Paint.FILTER_BITMAP_FLAG))
    textRight = dest.left - 12f
  }

  val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.rgb(196, 243, 107)
    textSize = width * 0.032f
    typeface = Typeface.DEFAULT_BOLD
  }
  val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.WHITE
    textSize = width * 0.055f
    typeface = Typeface.DEFAULT_BOLD
  }
  val detail = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.rgb(232, 240, 233)
    textSize = width * 0.038f
  }
  val textLeft = panel.left + 16f
  val maxText = (textRight - textLeft).coerceAtLeast(40f)
  fit(title, card.title, maxText)
  fit(detail, card.detail, maxText)
  canvas.drawText("ON THE GLASSES", textLeft, panel.top + label.textSize + 10f, label)
  canvas.drawText(card.title, textLeft, panel.top + label.textSize + title.textSize + 22f, title)
  canvas.drawText(card.detail, textLeft, panel.top + label.textSize + title.textSize + detail.textSize + 36f, detail)
}

private fun fit(paint: Paint, text: String, maxWidth: Float) {
  while (paint.measureText(text) > maxWidth && paint.textSize > 12f) {
    paint.textSize -= 1f
  }
}
