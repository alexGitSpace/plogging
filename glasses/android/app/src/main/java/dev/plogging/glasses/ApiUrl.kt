package dev.plogging.glasses

internal const val DEFAULT_API_URL = "https://plogging-api.up.railway.app"

internal fun eventsUrl(raw: String): String {
  var base = raw.trim().trimEnd('/')
  if (base.isEmpty()) base = DEFAULT_API_URL
  if (!base.contains("://")) base = "https://$base"
  base = base.trimEnd('/')
  if (base.endsWith("/api/events")) return base
  return "$base/api/events"
}

internal fun recogniseUrl(raw: String): String {
  val events = eventsUrl(raw)
  return if (events.endsWith("/api/events")) {
    events.removeSuffix("/api/events") + "/api/recognise"
  } else {
    "$events/api/recognise"
  }
}
