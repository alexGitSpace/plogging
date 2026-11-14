package dev.plogging.glasses

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

internal val LITTER_TYPES = setOf("bottle", "can", "cigarette_butt", "wrapper", "paper", "other")

internal fun litterType(label: String): String? {
  val name = label.trim().lowercase()
  return name.takeIf { it in LITTER_TYPES }
}

data class LocationFix(val lat: Double, val lon: Double)

internal data class ReadySighting(
    val litterType: String,
    val confidence: Float,
    val t: Long,
    val lat: Double,
    val lon: Double,
)

/**
 * Chooses which detections become sightings.
 *
 * One event per litter type in a frame, using the highest confidence. The same type is
 * held back for [WINDOW_MS] while the phone stays within [NEAR_METERS] of the previous
 * report. With no fix yet, the sighting waits and keeps the time it was first seen.
 */
internal class SightingGate {
  private data class Mark(val t: Long, val location: LocationFix)

  private data class Held(val t: Long, var confidence: Float)

  private val reported = mutableMapOf<String, Mark>()
  private val held = mutableMapOf<String, Held>()

  fun onFrame(detections: List<Detection>, location: LocationFix?, now: Long): List<ReadySighting> {
    val ready = mutableListOf<ReadySighting>()
    if (location != null) releaseHeld(location, now, ready)
    for ((type, confidence) in bestLitter(detections)) {
      if (location == null) {
        hold(type, confidence, now)
      } else if (shouldReport(type, location, now)) {
        ready += ReadySighting(type, confidence, now, location.lat, location.lon)
        reported[type] = Mark(now, location)
      }
    }
    return ready
  }

  fun dropHeld() {
    held.clear()
  }

  private fun hold(type: String, confidence: Float, now: Long) {
    val existing = held[type]
    if (existing == null) held[type] = Held(now, confidence)
    else if (confidence > existing.confidence) existing.confidence = confidence
  }

  private fun releaseHeld(location: LocationFix, now: Long, ready: MutableList<ReadySighting>) {
    if (held.isEmpty()) return
    val pending = held.toMap()
    held.clear()
    for ((type, item) in pending) {
      if (!shouldReport(type, location, now)) continue
      ready += ReadySighting(type, item.confidence, item.t, location.lat, location.lon)
      reported[type] = Mark(now, location)
    }
  }

  private fun shouldReport(type: String, location: LocationFix, now: Long): Boolean {
    val previous = reported[type] ?: return true
    val recent = now - previous.t < WINDOW_MS
    val near = metersBetween(previous.location, location) < NEAR_METERS
    return !(recent && near)
  }

  companion object {
    const val WINDOW_MS = 30_000L
    const val NEAR_METERS = 12.0
  }
}

internal fun bestLitter(detections: List<Detection>): Map<String, Float> {
  val best = mutableMapOf<String, Float>()
  for (detection in detections) {
    val type = litterType(detection.label) ?: continue
    val confidence = detection.confidence.coerceIn(0f, 1f)
    val current = best[type]
    if (current == null || confidence > current) best[type] = confidence
  }
  return best
}

internal fun metersBetween(a: LocationFix, b: LocationFix): Double {
  val earth = 6_371_000.0
  val lat1 = Math.toRadians(a.lat)
  val lat2 = Math.toRadians(b.lat)
  val dLat = lat2 - lat1
  val dLon = Math.toRadians(b.lon - a.lon)
  val h = sin(dLat / 2).let { it * it } + cos(lat1) * cos(lat2) * sin(dLon / 2).let { it * it }
  return 2 * earth * asin(sqrt(h.coerceIn(0.0, 1.0)))
}
