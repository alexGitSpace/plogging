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
  private val recentLitter = ArrayDeque<Pair<Long, String>>()

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
  fun onDetections(detections: List<Detection>, nowMs: Long): Pick? {
    pruneLitter(nowMs)
    if (detections.isNotEmpty()) {
      recentLitter.addLast(nowMs to detections.maxBy { it.confidence }.label)
    }
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

  private fun maybePick(nowMs: Long): Pick? {
    pruneLitter(nowMs)
    if (recentLitter.size < MIN_LITTER_FRAMES) return null
    countedThisBend = true
    return Pick(recentLitter.last().second, nowMs)
  }

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
