package dev.plogging.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PickDetectorTest {

  @Test
  fun bendWithLitterCountsNothing() {
    val detector = PickDetector()
    val clock = Clock()
    clock.stand(detector, 20)
    clock.see(detector, "Bottle")
    clock.see(detector, "Bottle")
    assertTrue(clock.bend(detector, 12).isEmpty())
  }

  @Test
  fun twoBendsTowardLitterCountNothing() {
    val detector = PickDetector()
    val clock = Clock()
    clock.stand(detector, 20)
    clock.see(detector, "Bottle")
    clock.see(detector, "Bottle")
    assertTrue(clock.bend(detector, 12).isEmpty())
    clock.stand(detector, 20)
    clock.see(detector, "Can")
    clock.see(detector, "Can")
    assertTrue(clock.bend(detector, 12).isEmpty())
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
  fun wearerClosingOnACupCountsOnePick() {
    val detector = PickDetector()
    val cup = CUP
    val open = listOf(pose(0.30f))
    val closed = listOf(pose(0.58f))
    assertNull(detector.onFrame(listOf(cup), open, 0L))
    assertNull(detector.onFrame(listOf(cup), open, 80L))
    assertNull(detector.onFrame(listOf(cup), open, 160L))
    assertNull(detector.onFrame(listOf(cup), emptyList(), 200L))
    assertNull(detector.onFrame(listOf(cup), closed, 240L))
    val pick = detector.onFrame(listOf(cup), closed, 280L)
    assertEquals("Cup", pick?.label)
    assertNull(detector.onFrame(listOf(cup), closed, 400L))
  }

  @Test
  fun aDistantHandClosingOnACupCountsNothing() {
    val detector = PickDetector()
    val cup = Detection("Cup", 0.9f, 0.35f, 0.10f, 0.65f, 0.40f)
    val open = listOf(pose(0.18f, wristY = 0.28f, palm = 0.03f))
    val closed = listOf(pose(0.26f, wristY = 0.28f, palm = 0.03f))
    assertNull(detector.onFrame(listOf(cup), open, 0L))
    assertNull(detector.onFrame(listOf(cup), open, 80L))
    assertNull(detector.onFrame(listOf(cup), open, 160L))
    assertNull(detector.onFrame(listOf(cup), closed, 240L))
    assertNull(detector.onFrame(listOf(cup), closed, 280L))
  }

  @Test
  fun aHandThatMissesTheCupCountsNothing() {
    val detector = PickDetector()
    val cup = Detection("Cup", 0.9f, 0.7f, 0.7f, 0.9f, 0.9f)
    val open = listOf(pose(0.30f))
    val closed = listOf(pose(0.58f))
    assertNull(detector.onFrame(listOf(cup), open, 0L))
    assertNull(detector.onFrame(listOf(cup), open, 80L))
    assertNull(detector.onFrame(listOf(cup), open, 160L))
    assertNull(detector.onFrame(listOf(cup), closed, 240L))
    assertNull(detector.onFrame(listOf(cup), closed, 280L))
  }

  @Test
  fun aOneFrameFlickerUnderTheHandCountsNothing() {
    val detector = PickDetector()
    val cup = CUP
    val hand = listOf(pose(0.30f))
    assertNull(detector.onFrame(listOf(cup), hand, 0L))
    assertNull(detector.onFrame(listOf(cup), hand, 80L))
    assertNull(detector.onFrame(listOf(cup), hand, 160L))
    assertNull(detector.onFrame(emptyList(), hand, 200L))
    assertNull(detector.onFrame(listOf(cup), hand, 240L))
  }

  @Test
  fun liftingTheCupAfterACloseCountsOnePick() {
    val detector = PickDetector()
    val cup = CUP
    val open = listOf(pose(0.30f))
    val closed = listOf(pose(0.58f))
    val down = listOf(pose(0.68f, wristY = 0.72f))
    assertNull(detector.onFrame(listOf(cup), open, 0L))
    assertNull(detector.onFrame(listOf(cup), open, 80L))
    assertNull(detector.onFrame(listOf(cup), open, 160L))
    assertNull(detector.onFrame(listOf(cup), closed, 200L))
    assertNull(detector.onFrame(emptyList(), closed, 250L))
    val pick = detector.onFrame(emptyList(), down, 650L)
    assertEquals("Cup", pick?.label)
  }

  @Test
  fun oneLongBendCountsNothing() {
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
    assertTrue(picks.isEmpty())
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

  private fun pose(tipY: Float, wristY: Float = 0.62f, palm: Float = 0.10f): HandPose {
    val points = MutableList(21) { HandPoint(0.5f, wristY) }
    points[0] = HandPoint(0.5f, wristY)
    points[9] = HandPoint(0.5f, wristY - palm)
    for (index in intArrayOf(4, 8, 12, 16, 20)) points[index] = HandPoint(0.5f, tipY)
    return HandPose(points)
  }

  private companion object {
    val CUP = Detection("Cup", 0.9f, 0.35f, 0.25f, 0.65f, 0.7f)
    const val STEP_NS = 1_000_000_000L / 15
    const val UP_X = 0f
    const val UP_Y = 9.8f
    const val UP_Z = 0f
    const val BENT_X = 0f
    const val BENT_Y = 3f
    const val BENT_Z = 9.3f
  }
}
