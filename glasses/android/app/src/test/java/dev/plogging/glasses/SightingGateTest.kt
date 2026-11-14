package dev.plogging.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SightingGateTest {

  private val here = LocationFix(60.18431, 24.83023)

  @Test
  fun mapsContractLabelsAndIgnoresEverydayClasses() {
    assertEquals("bottle", litterType("bottle"))
    assertEquals("bottle", litterType("Bottle"))
    assertEquals("can", litterType("can"))
    assertEquals("cigarette_butt", litterType("cigarette_butt"))
    assertEquals("wrapper", litterType("wrapper"))
    assertEquals("paper", litterType("paper"))
    assertEquals("other", litterType("other"))
    assertNull(litterType("person"))
    assertNull(litterType("cup"))
    assertNull(litterType("toothbrush"))
  }

  @Test
  fun oneEventPerLitterTypeAtHighestConfidence() {
    val best =
        bestLitter(
            listOf(
                detection("bottle", 0.4f),
                detection("person", 0.99f),
                detection("bottle", 0.88f),
                detection("can", 0.7f),
            ),
        )
    assertEquals(mapOf("bottle" to 0.88f, "can" to 0.7f), best)
  }

  @Test
  fun firstSightingWithAFixIsReported() {
    val gate = SightingGate()
    val ready = gate.onFrame(listOf(detection("bottle", 0.9f)), here, 1_000L)
    assertEquals(1, ready.size)
    assertEquals("bottle", ready[0].litterType)
    assertEquals(0.9f, ready[0].confidence)
    assertEquals(1_000L, ready[0].t)
    assertEquals(here.lat, ready[0].lat, 0.0)
    assertEquals(here.lon, ready[0].lon, 0.0)
  }

  @Test
  fun sameTypeNearbyWithinWindowIsHeldBack() {
    val gate = SightingGate()
    gate.onFrame(listOf(detection("bottle", 0.9f)), here, 1_000L)
    val again = gate.onFrame(listOf(detection("bottle", 0.95f)), here, 10_000L)
    assertTrue(again.isEmpty())
  }

  @Test
  fun sameTypeAfterWindowIsReported() {
    val gate = SightingGate()
    gate.onFrame(listOf(detection("bottle", 0.9f)), here, 1_000L)
    val later = gate.onFrame(listOf(detection("bottle", 0.8f)), here, 1_000L + SightingGate.WINDOW_MS)
    assertEquals(1, later.size)
    assertEquals(0.8f, later[0].confidence)
  }

  @Test
  fun sameTypeFartherThanTwelveMetresIsReported() {
    val gate = SightingGate()
    gate.onFrame(listOf(detection("bottle", 0.9f)), here, 1_000L)
    val away = LocationFix(here.lat + 0.0002, here.lon)
    assertTrue(metersBetween(here, away) > SightingGate.NEAR_METERS)
    val ready = gate.onFrame(listOf(detection("bottle", 0.7f)), away, 2_000L)
    assertEquals(1, ready.size)
    assertEquals(away.lat, ready[0].lat, 0.0)
  }

  @Test
  fun withoutAFixTheSightingWaitsAndKeepsItsTime() {
    val gate = SightingGate()
    assertTrue(gate.onFrame(listOf(detection("bottle", 0.4f)), null, 1_000L).isEmpty())
    assertTrue(gate.onFrame(listOf(detection("bottle", 0.92f)), null, 1_500L).isEmpty())
    val ready = gate.onFrame(emptyList(), here, 2_000L)
    assertEquals(1, ready.size)
    assertEquals(1_000L, ready[0].t)
    assertEquals(0.92f, ready[0].confidence)
    assertEquals(here.lat, ready[0].lat, 0.0)
  }

  @Test
  fun dropHeldForgetsSightingsThatNeverGotAFix() {
    val gate = SightingGate()
    gate.onFrame(listOf(detection("can", 0.8f)), null, 1_000L)
    gate.dropHeld()
    assertTrue(gate.onFrame(emptyList(), here, 2_000L).isEmpty())
  }

  @Test
  fun eventsUrlStripsSlashAndFillsTheDefault() {
    assertEquals("$DEFAULT_API_URL/api/events", eventsUrl(""))
    assertEquals("$DEFAULT_API_URL/api/events", eventsUrl("$DEFAULT_API_URL/"))
    assertEquals("http://10.0.2.2:8000/api/events", eventsUrl("http://10.0.2.2:8000"))
    assertEquals("http://10.0.2.2:8000/api/events", eventsUrl("http://10.0.2.2:8000/api/events"))
    assertEquals("https://example.com/api/events", eventsUrl("example.com"))
  }

  @Test
  fun recogniseUrlSitsBesideEvents() {
    assertEquals("$DEFAULT_API_URL/api/recognise", recogniseUrl(""))
    assertEquals("http://10.0.2.2:8000/api/recognise", recogniseUrl("http://10.0.2.2:8000"))
    assertEquals("http://10.0.2.2:8000/api/recognise", recogniseUrl("http://10.0.2.2:8000/api/events"))
  }

  @Test
  fun sightingJsonMatchesTheContract() {
    val json =
        SightingEvent(
                eventId = "6bad6be2-8e7a-46e9-9f19-950499dd251d",
                t = 1794654042000,
                lat = 60.18431,
                lon = 24.83023,
                litterType = "bottle",
                confidence = 0.88,
            )
            .toJson()
    assertEquals(
        "{\"event_id\":\"6bad6be2-8e7a-46e9-9f19-950499dd251d\",\"type\":\"sighting\"," +
            "\"device_id\":\"glasses-1\",\"t\":1794654042000,\"lat\":60.18431,\"lon\":24.83023," +
            "\"litter_type\":\"bottle\",\"confidence\":0.88}",
        json,
    )
  }

  private fun detection(label: String, confidence: Float) =
      Detection(label, confidence, 0f, 0f, 1f, 1f)
}
