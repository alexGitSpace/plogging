package dev.plogging.glasses

import java.util.Locale

internal data class SightingEvent(
    val eventId: String,
    val t: Long,
    val lat: Double,
    val lon: Double,
    val litterType: String,
    val confidence: Double,
) {
  fun toJson(): String =
      buildString {
        append("{\"event_id\":\"")
        append(eventId)
        append("\",\"type\":\"sighting\",\"device_id\":\"glasses-1\",\"t\":")
        append(t)
        append(",\"lat\":")
        append(jsonNumber(lat))
        append(",\"lon\":")
        append(jsonNumber(lon))
        append(",\"litter_type\":\"")
        append(litterType)
        append("\",\"confidence\":")
        append(jsonNumber(confidence))
        append("}")
      }
}

internal fun sightingBatchJson(events: List<SightingEvent>): String =
    events.joinToString(separator = ",", prefix = "{\"events\":[", postfix = "]}") { it.toJson() }

internal fun jsonNumber(value: Double): String {
  if (!value.isFinite()) return "0"
  return String.format(Locale.US, "%.6f", value).trimEnd('0').trimEnd('.')
}
