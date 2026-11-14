package dev.plogging.glasses

import kotlin.math.hypot

/** The object to point out on the display of the glasses: which one, where, and among how many. */
class Spot(val detection: Detection, val sign: DisplayCard.Sign, val count: Int) {
  val title: String
    get() = detection.label.replaceFirstChar { it.uppercase() }

  val detail: String
    get() {
      val where =
          when (sign) {
            DisplayCard.Sign.LEFT -> "On your left"
            DisplayCard.Sign.RIGHT -> "On your right"
            else -> "Straight ahead"
          }
      return if (count > 1) "$where · $count in view" else where
    }
}

/**
 * Chooses the object to show on the display of the glasses.
 *
 * It stays with one object while that is in view, rather than jumping to whichever the
 * detector is surest of in each frame, and lets every spot stand for [CHANGE_MS]: a card that
 * keeps changing cannot be read, and each one crosses Bluetooth.
 */
class Spotter {

  companion object {
    /** How long a spot stays after its object was last seen. */
    const val HOLD_MS = 1_500L

    /** The least time between two spots. */
    const val CHANGE_MS = 1_500L

    /** Boxes whose centres are closer than this share of the frame are the same object. */
    const val SAME_OBJECT = 0.15f

    /** Half the width of the middle of the frame that counts as straight ahead. */
    const val AHEAD = 0.15f
  }

  private var target: Detection? = null
  private var seenAtMs = 0L
  private var spot: Spot? = null
  private var spotAtMs = 0L

  /**
   * Call for every frame, from one thread at a time. Returns the spot to show, or null for
   * an empty display. It is the same object until there is something new to show.
   */
  fun onDetections(detections: List<Detection>, nowMs: Long): Spot? {
    val found = follow(detections)
    if (found == null) {
      // The detector misses an object for a frame or two now and then: do not blink.
      if (nowMs - seenAtMs > HOLD_MS) {
        target = null
        spot = null
      }
      return spot
    }
    target = found
    seenAtMs = nowMs

    val shown = spot
    val latest = Spot(found, signFor(found), detections.size)
    if (shown == null || (nowMs - spotAtMs > CHANGE_MS && differs(latest, shown))) {
      spot = latest
      spotAtMs = nowMs
    }
    return spot
  }

  /** The object shown before, if it is still in view, or else the one the detector is surest of. */
  private fun follow(detections: List<Detection>): Detection? {
    val last = target
    if (last != null) {
      val nearest = detections.minByOrNull { distance(it, last) }
      if (nearest != null && distance(nearest, last) < SAME_OBJECT) return nearest
    }
    return detections.maxByOrNull { it.confidence }
  }

  private fun differs(a: Spot, b: Spot): Boolean =
      a.detection.label != b.detection.label ||
          a.sign != b.sign ||
          a.count != b.count ||
          distance(a.detection, b.detection) > SAME_OBJECT

  /** How far apart the centres of two boxes are, as a share of the frame. */
  private fun distance(a: Detection, b: Detection): Float =
      hypot((a.left + a.right - b.left - b.right) / 2, (a.top + a.bottom - b.top - b.bottom) / 2)

  /** The camera looks where the head points, so the middle of the frame is straight ahead. */
  private fun signFor(found: Detection): DisplayCard.Sign {
    val x = (found.left + found.right) / 2
    return when {
      x < 0.5f - AHEAD -> DisplayCard.Sign.LEFT
      x > 0.5f + AHEAD -> DisplayCard.Sign.RIGHT
      else -> DisplayCard.Sign.AHEAD
    }
  }
}
