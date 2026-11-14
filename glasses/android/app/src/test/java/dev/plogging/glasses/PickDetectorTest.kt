package dev.plogging.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PickDetectorTest {

  @Test
  fun bendWithLitterCountsOnePick() {
    val detector = PickDetector()
    val clock = Clock()
    clock.stand(detector, 20)
    clock.see(detector, "Bottle")
    clock.see(detector, "Bottle")
    val picks = clock.bend(detector, 12)
    assertEquals(1, picks.size)
    assertEquals("Bottle", picks[0].label)
  }

  @Test
  fun bendWithoutLitterCountsNothing() {
    val detector = PickDetector()
    val clock = Clock()
    clock.stand(detector, 20)
    assertTrue(clock.bend(detector, 12).isEmpty())
  }

  @Test
  fun litterWithoutABendCountsNothing() {
    val detector = PickDetector()
    val clock = Clock()
    val picks = mutableListOf<Pick>()
    clock.stand(detector, 20)
    repeat(8) { clock.see(detector, "Can")?.let(picks::add) }
    picks += clock.stand(detector, 10)
    assertTrue(picks.isEmpty())
  }

  @Test
  fun oneLongBendCountsOnePick() {
    val detector = PickDetector()
    val clock = Clock()
    clock.stand(detector, 20)
    clock.see(detector, "Can")
    clock.see(detector, "Can")
    val picks = mutableListOf<Pick>()
    repeat(40) {
      clock.see(detector, "Can")?.let(picks::add)
      clock.sample(detector, BENT_X, BENT_Y, BENT_Z)?.let(picks::add)
    }
    assertEquals(1, picks.size)
    assertEquals("Can", picks[0].label)
  }

  private class Clock {
    private var ns = 0L
    private var ms = 0L

    fun sample(detector: PickDetector, x: Float, y: Float, z: Float): Pick? {
      val pick = detector.onMotion(MotionReading(ns, x, y, z), ms)
      ns += STEP_NS
      ms = ns / 1_000_000L
      return pick
    }

    fun stand(detector: PickDetector, count: Int): List<Pick> {
      val picks = mutableListOf<Pick>()
      repeat(count) { sample(detector, UP_X, UP_Y, UP_Z)?.let(picks::add) }
      return picks
    }

    fun bend(detector: PickDetector, count: Int): List<Pick> {
      val picks = mutableListOf<Pick>()
      repeat(count) { sample(detector, BENT_X, BENT_Y, BENT_Z)?.let(picks::add) }
      return picks
    }

    fun see(detector: PickDetector, label: String): Pick? {
      val pick = detector.onDetections(listOf(Detection(label, 0.9f, 0f, 0f, 1f, 1f)), ms)
      ms += 1
      return pick
    }
  }

  private companion object {
    const val STEP_NS = 1_000_000_000L / 15
    const val UP_X = 0f
    const val UP_Y = 9.8f
    const val UP_Z = 0f
    const val BENT_X = 0f
    const val BENT_Y = 3f
    const val BENT_Z = 9.3f
  }
}
