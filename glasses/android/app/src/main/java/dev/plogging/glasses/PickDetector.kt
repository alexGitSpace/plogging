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

  fun curl(): Float {
    if (points.size <= MIDDLE_MCP) return OPEN_CURL
    val wrist = points[WRIST]
    val palm = distance(wrist, points[MIDDLE_MCP]).coerceAtLeast(0.02f)
    val tips = FINGERTIPS.map { distance(wrist, points[it]) / palm }
    return tips.average().toFloat()
  }

  fun touches(box: Detection): Boolean = fingertipsInside(box.left, box.top, box.right, box.bottom)

  fun fingertipsInside(left: Float, top: Float, right: Float, bottom: Float): Boolean {
    if (points.size <= FINGERTIPS.max()) return false
    val padX = maxOf(0.08f, (right - left) * 0.25f)
    val padY = maxOf(0.08f, (bottom - top) * 0.25f)
    return FINGERTIPS.any { index ->
      val point = points[index]
      point.x in (left - padX)..(right + padX) && point.y in (top - padY)..(bottom + padY)
    }
  }

  private companion object {
    const val WRIST = 0
    const val MIDDLE_MCP = 9
    const val OPEN_CURL = 2.5f
    val FINGERTIPS = intArrayOf(4, 8, 12, 16, 20)
  }
}

private fun distance(a: HandPoint, b: HandPoint): Float {
  val dx = a.x - b.x
  val dy = a.y - b.y
  return sqrt(dx * dx + dy * dy)
}

/**
 * Counts a pick when the wearer bends toward litter that was just in view.
 *
 * Tilt is the angle between a fast and a slow average of the accelerometer, so it
 * follows the wearer's own walking posture rather than a fixed sensor axis.
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
    const val PICK_COOLDOWN_MS = 1_200L
    const val CLOSED_CURL = 1.45f
    const val CURL_DROP = 0.45f
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
  private val held = mutableListOf<Held>()

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
    pruneLitter(nowMs)
    if (detections.isNotEmpty()) {
      recentLitter.addLast(nowMs to detections.maxBy { it.confidence }.label)
    }
    val fromHand = handPick(detections, hands, nowMs)
    if (!bending || countedThisBend) return fromHand
    val started = bendStartedMs ?: return fromHand
    if (nowMs - started < MIN_BEND_MS) return fromHand
    return fromHand ?: maybePick(nowMs)
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

  private fun maybePick(nowMs: Long): Pick? {
    pruneLitter(nowMs)
    if (recentLitter.size < MIN_LITTER_FRAMES) return null
    return emit(recentLitter.last().second, nowMs)
  }

  private fun handPick(detections: List<Detection>, hands: List<HandPose>, nowMs: Long): Pick? {
    if (hands.isEmpty() && held.isEmpty()) return null
    val next = mutableListOf<Held>()
    val touched = mutableSetOf<Held>()
    for (detection in detections) {
      val onIt = hands.filter { it.touches(detection) }
      if (onIt.isEmpty()) continue
      val curl = onIt.minOf { it.curl() }
      val existing = held.firstOrNull { it.label == detection.label && !touched.contains(it) }
      val item =
          if (existing == null) {
            Held(detection.label, detection.left, detection.top, detection.right, detection.bottom, 1, curl)
          } else {
            existing.seen += 1
            existing.left = detection.left
            existing.top = detection.top
            existing.right = detection.right
            existing.bottom = detection.bottom
            existing.openCurl = maxOf(existing.openCurl, curl)
            existing
          }
      touched += item
      val closed = item.seen >= 2 && item.openCurl - curl >= CURL_DROP && curl <= CLOSED_CURL
      if (closed) {
        held.clear()
        return emit(item.label, nowMs)
      }
      next += item
    }
    for (item in held) {
      if (item in touched) continue
      val stillThere = hands.any { it.fingertipsInside(item.left, item.top, item.right, item.bottom) }
      if (stillThere && item.seen >= 2) {
        held.clear()
        return emit(item.label, nowMs)
      }
    }
    held.clear()
    held += next
    return null
  }

  private fun emit(label: String, nowMs: Long): Pick? {
    if (lastPickMs != Long.MIN_VALUE && nowMs - lastPickMs < PICK_COOLDOWN_MS) return null
    lastPickMs = nowMs
    countedThisBend = true
    held.clear()
    return Pick(label, nowMs)
  }

  private class Held(
      val label: String,
      var left: Float,
      var top: Float,
      var right: Float,
      var bottom: Float,
      var seen: Int,
      var openCurl: Float,
  )

  private fun pruneLitter(nowMs: Long) {
    while (recentLitter.isNotEmpty() && nowMs - recentLitter.first().first > LITTER_WINDOW_MS) {
      recentLitter.removeFirst()
    }
  }
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
