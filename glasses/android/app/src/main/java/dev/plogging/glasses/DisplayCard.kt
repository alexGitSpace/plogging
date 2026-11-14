package dev.plogging.glasses

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min

/**
 * One screen for the display of the glasses, short enough to read at a glance: what was
 * found, which way to turn to face it, and the camera view with the object outlined.
 */
class DisplayCard(
    val title: String,
    val detail: String,
    val sign: Sign,
    val picture: Bitmap? = null,
) {
  enum class Sign {
    LEFT,
    AHEAD,
    RIGHT,
    LOOKING,
  }

  companion object {
    /** Shown when the display comes on, before anything is found. */
    val LOOKING = DisplayCard("Plogging", "Looking for litter", Sign.LOOKING)

    /**
     * The card for [spot]. [frame] must be the frame that the spot was found in. If the frame
     * has been recycled since, the card comes without a picture.
     */
    fun of(spot: Spot, frame: Bitmap): DisplayCard {
      val picture = if (frame.isRecycled) null else highlight(frame, spot.detection)
      return DisplayCard(spot.title, spot.detail, spot.sign, picture)
    }
  }
}

/** A wide picture leaves room for the words under it on the 600 x 600 display. */
private const val PICTURE_WIDTH = 480
private const val PICTURE_HEIGHT = 270

/** The picture shows this many times the size of the object. */
private const val ROOM = 1.8f

/** The picture shows at least this share of the width of the frame. */
private const val MIN_VIEW = 0.5f

private const val DIMMING = 150
private const val OUTLINE_WIDTH = 5f
private const val OUTLINE_GAP = 4f
private const val OUTLINE_CORNER = 10f

/**
 * The camera view around [found], with the object outlined and the rest dimmed.
 *
 * The display is a small screen beside the wearer's line of sight, not a layer over the
 * world, so it cannot mark the object where it lies: it shows this picture instead. It adds
 * light to what the wearer sees, so black stays clear and the outlined object stands out.
 */
private fun highlight(frame: Bitmap, found: Detection): Bitmap {
  val box =
      RectF(
          found.left * frame.width,
          found.top * frame.height,
          found.right * frame.width,
          found.bottom * frame.height,
      )

  // Show the object with room around it, in the shape of the picture where the frame allows.
  val shape = PICTURE_WIDTH.toFloat() / PICTURE_HEIGHT
  val room = min(max(box.width() * ROOM, frame.width * MIN_VIEW), frame.width.toFloat())
  val height = min(max(box.height() * ROOM, room / shape), frame.height.toFloat())
  val width = min(max(room, height * shape), frame.width.toFloat())
  val left = (box.centerX() - width / 2).coerceIn(0f, frame.width - width)
  val top = (box.centerY() - height / 2).coerceIn(0f, frame.height - height)
  val scale = min(PICTURE_WIDTH / width, PICTURE_HEIGHT / height)

  val picture = Bitmap.createBitmap(PICTURE_WIDTH, PICTURE_HEIGHT, Bitmap.Config.ARGB_8888)
  // An opaque picture crosses Bluetooth as JPEG. With transparency it would go as PNG, far larger.
  picture.setHasAlpha(false)
  val canvas = Canvas(picture)
  canvas.drawColor(Color.BLACK)

  // From here on, draw in the coordinates of the frame.
  canvas.translate((PICTURE_WIDTH - width * scale) / 2, (PICTURE_HEIGHT - height * scale) / 2)
  canvas.scale(scale, scale)
  canvas.translate(-left, -top)
  canvas.clipRect(left, top, left + width, top + height)
  val whole = RectF(0f, 0f, frame.width.toFloat(), frame.height.toFloat())
  canvas.drawBitmap(frame, null, whole, Paint(Paint.FILTER_BITMAP_FLAG))

  val outline = RectF(box).apply { inset(-OUTLINE_GAP / scale, -OUTLINE_GAP / scale) }
  canvas.save()
  canvas.clipOutRect(outline)
  canvas.drawColor(Color.argb(DIMMING, 0, 0, 0))
  canvas.restore()
  val stroke =
      Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = OUTLINE_WIDTH / scale
        color = Color.rgb(196, 243, 107)
      }
  canvas.drawRoundRect(outline, OUTLINE_CORNER / scale, OUTLINE_CORNER / scale, stroke)
  return picture
}
