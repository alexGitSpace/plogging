package dev.plogging.glasses

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

internal data class ReportStatus(
    val sent: Int = 0,
    val queued: Int = 0,
    val error: String? = null,
)

/**
 * Queues sightings and posts them to `/api/events`.
 *
 * A `200` drops the batch. A rejected event is logged and not retried. A network error or
 * `5xx` keeps the same ids. A `4xx` drops the batch so a bad payload cannot block the queue.
 */
internal class SightingReporter(context: Context) {
  private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
  private val queueFile = File(context.filesDir, "sightings.json")
  private val queue = mutableListOf<SightingEvent>()
  private val gate = SightingGate()
  private val lock = Any()

  private var sent = 0
  private var error: String? = null
  private var accepting = false
  private var flushJob: Job? = null

  @Volatile
  var apiUrl: String = DEFAULT_API_URL
    private set

  private val _status = MutableStateFlow(ReportStatus())
  val status: StateFlow<ReportStatus> = _status.asStateFlow()

  init {
    apiUrl = prefs.getString(URL_KEY, null) ?: DEFAULT_API_URL
    queue += load()
    publish()
  }

  fun updateApiUrl(value: String) {
    apiUrl = value
    prefs.edit().putString(URL_KEY, value).apply()
  }

  fun start(scope: CoroutineScope) {
    if (flushJob?.isActive == true) return
    flushJob =
        scope.launch(Dispatchers.IO) {
          flush()
          while (isActive) {
            delay(FLUSH_EVERY_MS)
            flush()
          }
        }
  }

  fun beginSession() {
    synchronized(lock) { accepting = true }
  }

  fun endSession() {
    synchronized(lock) {
      accepting = false
      gate.dropHeld()
    }
  }

  fun note(detections: List<Detection>, location: LocationFix?, now: Long) {
    synchronized(lock) {
      if (!accepting) return
      val ready = gate.onFrame(detections, location, now)
      if (ready.isEmpty()) return
      queue += ready.map { it.toEvent() }
      persist()
      publish()
    }
  }

  private fun ReadySighting.toEvent() =
      SightingEvent(
          eventId = UUID.randomUUID().toString(),
          t = t,
          lat = lat,
          lon = lon,
          litterType = litterType,
          confidence = confidence.toDouble().coerceIn(0.0, 1.0),
      )

  private fun flush() {
    while (true) {
      val batch = synchronized(lock) { queue.take(MAX_BATCH).toList() }
      if (batch.isEmpty()) {
        synchronized(lock) {
          if (error != null) {
            error = null
            publish()
          }
        }
        return
      }
      val outcome =
          try {
            post(batch)
          } catch (e: CancellationException) {
            throw e
          } catch (e: IOException) {
            Log.w(TAG, "Sighting post failed", e)
            Keep("No connection")
          } catch (e: Exception) {
            Log.w(TAG, "Sighting post failed", e)
            Keep("No connection")
          }
      val stop =
          synchronized(lock) {
            when (outcome) {
              is Sent -> {
                remove(batch)
                sent += outcome.accepted
                error = null
                persist()
                publish()
                false
              }
              is Keep -> {
                error = outcome.message
                publish()
                true
              }
              is Drop -> {
                remove(batch)
                error = outcome.message
                persist()
                publish()
                true
              }
            }
          }
      if (stop) return
    }
  }

  private fun remove(batch: List<SightingEvent>) {
    val ids = batch.map { it.eventId }.toSet()
    queue.removeAll { it.eventId in ids }
  }

  private fun post(events: List<SightingEvent>): Outcome {
    val connection = URL(eventsUrl(apiUrl)).openConnection() as HttpURLConnection
    try {
      connection.requestMethod = "POST"
      connection.connectTimeout = 10_000
      connection.readTimeout = 15_000
      connection.doOutput = true
      connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
      val body = sightingBatchJson(events).toByteArray(Charsets.UTF_8)
      connection.outputStream.use { it.write(body) }
      val code = connection.responseCode
      val stream = if (code in 200..299) connection.inputStream else connection.errorStream
      val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
      return when {
        code in 200..299 -> {
          logRejected(text)
          Sent(acceptedCount(text, events.size))
        }
        code in 500..599 -> Keep("API error $code")
        code in 400..499 -> Drop("API error $code")
        else -> Keep("API error $code")
      }
    } finally {
      connection.disconnect()
    }
  }

  private fun publish() {
    _status.value = ReportStatus(sent, queue.size, error)
  }

  private fun persist() {
    if (queue.isEmpty()) {
      queueFile.delete()
      return
    }
    val payload = sightingBatchJson(queue)
    val tmp = File(queueFile.parentFile, "${queueFile.name}.tmp")
    tmp.writeText(payload)
    if (!tmp.renameTo(queueFile)) {
      queueFile.writeText(payload)
      tmp.delete()
    }
  }

  private fun load(): List<SightingEvent> {
    if (!queueFile.exists()) return emptyList()
    return try {
      val events = JSONObject(queueFile.readText()).optJSONArray("events") ?: return emptyList()
      buildList {
        for (i in 0 until events.length()) {
          val item = events.optJSONObject(i) ?: continue
          add(
              SightingEvent(
                  eventId = item.getString("event_id"),
                  t = item.getLong("t"),
                  lat = item.getDouble("lat"),
                  lon = item.getDouble("lon"),
                  litterType = item.getString("litter_type"),
                  confidence = item.getDouble("confidence"),
              ),
          )
        }
      }
    } catch (e: Exception) {
      Log.w(TAG, "Could not read the sighting queue", e)
      emptyList()
    }
  }

  private sealed interface Outcome

  private data class Sent(val accepted: Int) : Outcome

  private data class Keep(val message: String) : Outcome

  private data class Drop(val message: String) : Outcome

  private companion object {
    const val TAG = "SightingReporter"
    const val PREFS = "sightings"
    const val URL_KEY = "api_url"
    const val MAX_BATCH = 100
    const val FLUSH_EVERY_MS = 2_000L

    fun acceptedCount(body: String, fallback: Int): Int =
        try {
          JSONObject(body).optJSONArray("accepted")?.length() ?: fallback
        } catch (_: Exception) {
          fallback
        }

    fun logRejected(body: String) {
      try {
        val rejected = JSONObject(body).optJSONArray("rejected") ?: return
        for (i in 0 until rejected.length()) {
          val item = rejected.optJSONObject(i) ?: continue
          Log.w(TAG, "Rejected ${item.opt("event_id")}: ${item.optString("error")}")
        }
      } catch (e: Exception) {
        Log.w(TAG, "Could not read the API response", e)
      }
    }
  }
}
