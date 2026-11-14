package dev.plogging.glasses

import kotlin.math.acos
import kotlin.math.exp
import kotlin.math.sqrt

data class MotionReading(
    val timestampNs: Long,
    val x: Float,
    val y: Float,
    val z: Float,
)

data class Pick(val label: String, val t: Long)

data class HandPoint(val x: Float, val y: Float)

data class HandPose(val points: List<HandPoint>) {

  fun wristY(): Float = points.getOrNull(WRIST)?.y ?: 0f

  fun palm(): Float {
    val wrist = points.getOrNull(WRIST) ?: return 0f
    val knuckle = points.getOrNull(MIDDLE_MCP) ?: return 0f
    return distance(wrist, knuckle)
  }

  fun belongsToWearer(): Boolean = palm() >= WEARER_PALM && wristY() >= WEARER_WRIST_Y

  fun curl(): Float {
    if (points.size <= MIDDLE_MCP) return OPEN_CURL
    val wrist = points[WRIST]
    val palm = palm().coerceAtLeast(0.02f)
    val tips = FINGERTIPS.map { distance(wrist, points[it]) / palm }
    return tips.average().toFloat()
  }

  fun touches(box: Detection): Boolean {
    val near = fingertipsInside(box.left, box.top, box.right, box.bottom)
    if (near) return true
    val wrist = points.getOrNull(WRIST) ?: return false
    val knuckle = points.getOrNull(MIDDLE_MCP) ?: return false
    return inside(HandPoint((wrist.x + knuckle.x) / 2f, (wrist.y + knuckle.y) / 2f), box)
  }

  fun fingertipsInside(left: Float, top: Float, right: Float, bottom: Float): Boolean {
    if (points.size <= FINGERTIPS.max()) return false
    val box = Detection("", 0f, left, top, right, bottom)
    return FINGERTIPS.any { index -> inside(points[index], box) }
  }

  private fun inside(point: HandPoint, box: Detection): Boolean {
    val padX = maxOf(0.06f, (box.right - box.left) * 0.22f)
    val padY = maxOf(0.06f, (box.bottom - box.top) * 0.22f)
    return point.x in (box.left - padX)..(box.right + padX) &&
        point.y in (box.top - padY)..(box.bottom + padY)
  }

  private companion object {
    const val WRIST = 0
    const val MIDDLE_MCP = 9
    const val OPEN_CURL = 2.5f
    const val WEARER_PALM = 0.04f
    const val WEARER_WRIST_Y = 0.32f
    val FINGERTIPS = intArrayOf(4, 8, 12, 16, 20)
  }
}

private fun distance(a: HandPoint, b: HandPoint): Float {
  val dx = a.x - b.x
  val dy = a.y - b.y
  return sqrt(dx * dx + dy * dy)
}

/**
 * Counts a pick when the wearer bends toward litter that was just in view, and also when
 * their own hand closes on a piece.
 */
class PickDetector {

  companion object {
    const val FAST_TAU_S = 0.2
    const val SLOW_TAU_S = 10.0
    const val BEND_ENTER_DEG = 40.0
    const val BEND_EXIT_DEG = 20.0
    const val MIN_BEND_MS = 400L
    const val MAX_BEND_MS = 8_000L
    const val LITTER_WINDOW_MS = 2_500L
    const val MIN_LITTER_FRAMES = 2
    const val PICK_COOLDOWN_MS = 700L
    const val HAND_GAP_MS = 500L
    const val REACH_MS = 80L
    const val MISSING_MS = 200L
    const val OPEN_CURL = 1.8f
    const val CLOSED_CURL = 1.55f
    const val CLOSED_SAMPLES = 1
    const val WRIST_DOWN = 0.04f
    private const val MAX_DT_S = 0.5
    private const val MIN_NORM = 1e-3
  }

  @Volatile
  var tiltDegrees: Float? = null
    private set

  private var fastX = 0.0
  private var fastY = 0.0
  private var fastZ = 0.0
  private var slowX = 0.0
  private var slowY = 0.0
  private var slowZ = 0.0
  private var hasBaseline = false
  private var lastTimestampNs: Long? = null
  private var bending = false
  private var bendStartedMs: Long? = null
  private var countedThisBend = false
  private var lastPickMs = Long.MIN_VALUE
  private val recentLitter = ArrayDeque<Pair<Long, String>>()
  private var lastHandMs = Long.MIN_VALUE
  private var grab: Grab? = null

  @Synchronized
  fun onMotion(reading: MotionReading, nowMs: Long): Pick? {
    val x = reading.x.toDouble()
    val y = reading.y.toDouble()
    val z = reading.z.toDouble()
    if (norm(x, y, z) < MIN_NORM) return null

    val previousNs = lastTimestampNs
    lastTimestampNs = reading.timestampNs
    val dt =
        if (previousNs == null) 0.0
        else ((reading.timestampNs - previousNs).coerceAtLeast(0L) / 1_000_000_000.0).coerceAtMost(MAX_DT_S)

    if (!hasBaseline) {
      fastX = x
      fastY = y
      fastZ = z
      slowX = x
      slowY = y
      slowZ = z
      hasBaseline = true
      tiltDegrees = 0f
      return null
    }

    val fastAlpha = emaAlpha(dt, FAST_TAU_S)
    fastX = fastAlpha * x + (1.0 - fastAlpha) * fastX
    fastY = fastAlpha * y + (1.0 - fastAlpha) * fastY
    fastZ = fastAlpha * z + (1.0 - fastAlpha) * fastZ
    if (!bending) {
      val slowAlpha = emaAlpha(dt, SLOW_TAU_S)
      slowX = slowAlpha * x + (1.0 - slowAlpha) * slowX
      slowY = slowAlpha * y + (1.0 - slowAlpha) * slowY
      slowZ = slowAlpha * z + (1.0 - slowAlpha) * slowZ
    }

    val tilt = angleDeg(fastX, fastY, fastZ, slowX, slowY, slowZ)
    tiltDegrees = tilt.toFloat()
    return updateBend(tilt, nowMs)
  }

  @Synchronized
  fun onDetections(detections: List<Detection>, nowMs: Long): Pick? =
      onFrame(detections, emptyList(), nowMs)

  @Synchronized
  fun onFrame(detections: List<Detection>, hands: List<HandPose>, nowMs: Long): Pick? {
    noteLitter(detections, nowMs)
    val fromHand = handPick(detections, hands.filter { it.belongsToWearer() }, nowMs)
    if (fromHand != null) return fromHand
    if (!bending || countedThisBend) return null
    val started = bendStartedMs ?: return null
    if (nowMs - started < MIN_BEND_MS) return null
    return maybePick(nowMs)
  }

  private fun updateBend(tilt: Double, nowMs: Long): Pick? {
    if (!bending && tilt >= BEND_ENTER_DEG) {
      bending = true
      bendStartedMs = nowMs
      countedThisBend = false
    }
    if (!bending) return null

    val started = bendStartedMs ?: nowMs
    val duration = nowMs - started
    if (duration >= MAX_BEND_MS) {
      slowX = fastX
      slowY = fastY
      slowZ = fastZ
      bending = false
      bendStartedMs = null
      countedThisBend = false
      return null
    }
    if (tilt < BEND_EXIT_DEG) {
      val pick = if (!countedThisBend && duration >= MIN_BEND_MS) maybePick(nowMs) else null
      bending = false
      bendStartedMs = null
      countedThisBend = false
      return pick
    }
    if (!countedThisBend && duration >= MIN_BEND_MS) return maybePick(nowMs)
    return null
  }

  private fun noteLitter(detections: List<Detection>, nowMs: Long) {
    pruneLitter(nowMs)
    if (detections.isNotEmpty()) {
      recentLitter.addLast(nowMs to detections.maxBy { it.confidence }.label)
    }
  }

  private fun maybePick(nowMs: Long): Pick? {
    pruneLitter(nowMs)
    if (recentLitter.size < MIN_LITTER_FRAMES) return null
    if (lastPickMs != Long.MIN_VALUE && nowMs - lastPickMs < PICK_COOLDOWN_MS) return null
    countedThisBend = true
    lastPickMs = nowMs
    return Pick(recentLitter.last().second, nowMs)
  }

  private fun pruneLitter(nowMs: Long) {
    while (recentLitter.isNotEmpty() && nowMs - recentLitter.first().first > LITTER_WINDOW_MS) {
      recentLitter.removeFirst()
    }
  }

  private fun handPick(detections: List<Detection>, hands: List<HandPose>, nowMs: Long): Pick? {
    if (hands.isNotEmpty()) lastHandMs = nowMs
    val current = grab
    if (current?.taken == true) {
      val stillOn = hands.any { it.touches(current.box) }
      if (!stillOn) grab = null
      return null
    }
    val contact = contact(detections, hands)
    if (contact != null) return onContact(contact, nowMs)
    return onMiss(hands, nowMs)
  }

  private fun contact(detections: List<Detection>, hands: List<HandPose>): Contact? {
    var best: Contact? = null
    for (detection in detections) {
      for (hand in hands) {
        if (!hand.touches(detection)) continue
        val same = grab?.label == detection.label
        if (best == null || same) best = Contact(detection, hand)
        if (same) return best
      }
    }
    return best
  }

  private fun onContact(contact: Contact, nowMs: Long): Pick? {
    val curl = contact.hand.curl()
    val current = grab
    if (current == null || current.label != contact.detection.label) {
      grab =
          Grab(
              label = contact.detection.label,
              box = contact.detection,
              reachedAt = nowMs,
              wristY = contact.hand.wristY(),
          )
      return null
    }
    current.box = contact.detection
    current.missingSince = null
    if (curl >= OPEN_CURL) current.closedSamples = 0
    else if (curl <= CLOSED_CURL) current.closedSamples += 1
    if (nowMs - current.reachedAt < REACH_MS) return null
    if (current.closedSamples >= CLOSED_SAMPLES) return emit(current, nowMs)
    return null
  }

  private fun onMiss(hands: List<HandPose>, nowMs: Long): Pick? {
    val current = grab ?: return null
    if (hands.isEmpty()) {
      if (lastHandMs != Long.MIN_VALUE && nowMs - lastHandMs < HAND_GAP_MS) return null
      grab = null
      return null
    }
    val missingSince = current.missingSince ?: nowMs.also { current.missingSince = it }
    val wristDown = hands.maxOf { it.wristY() } - current.wristY >= WRIST_DOWN
    if (nowMs - current.reachedAt >= REACH_MS && nowMs - missingSince >= MISSING_MS && wristDown) {
      return emit(current, nowMs)
    }
    return null
  }

  private fun emit(current: Grab, nowMs: Long): Pick? {
    if (lastPickMs != Long.MIN_VALUE && nowMs - lastPickMs < PICK_COOLDOWN_MS) return null
    lastPickMs = nowMs
    current.taken = true
    grab = current
    return Pick(current.label, nowMs)
  }

  private class Grab(
      val label: String,
      var box: Detection,
      val reachedAt: Long,
      val wristY: Float,
      var missingSince: Long? = null,
      var closedSamples: Int = 0,
      var taken: Boolean = false,
  )

  private class Contact(val detection: Detection, val hand: HandPose)
}

private fun emaAlpha(dt: Double, tau: Double): Double {
  if (dt <= 0.0) return 0.0
  return 1.0 - exp(-dt / tau)
}

private fun norm(x: Double, y: Double, z: Double): Double = sqrt(x * x + y * y + z * z)

private fun angleDeg(
    ax: Double,
    ay: Double,
    az: Double,
    bx: Double,
    by: Double,
    bz: Double,
): Double {
  val na = norm(ax, ay, az)
  val nb = norm(bx, by, bz)
  if (na < 1e-3 || nb < 1e-3) return 0.0
  val cosine = ((ax * bx + ay * by + az * bz) / (na * nb)).coerceIn(-1.0, 1.0)
  return Math.toDegrees(acos(cosine))
}
