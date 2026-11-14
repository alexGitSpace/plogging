package dev.plogging.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RecogniseGateTest {

  @Test
  fun aStrongPickDoesNotAsk() {
    val gate = RecogniseGate()
    val box = Detection("Bottle", 0.92f, 0.3f, 0.3f, 0.5f, 0.6f)
    assertNull(gate.consider(Pick("Bottle", 1_000L, 0.92f, box), emptyList(), listOf(box), 1_000L))
  }

  @Test
  fun aWeakPickAsksForThatBox() {
    val gate = RecogniseGate()
    val box = Detection("Can", 0.61f, 0.3f, 0.3f, 0.5f, 0.6f)
    val crop = gate.consider(Pick("Can", 1_000L, 0.61f, box), emptyList(), listOf(box), 1_000L)
    assertEquals(box, crop?.box)
    assertEquals("Can", crop?.hint)
    assertEquals(0.61f, crop?.hintConfidence)
  }

  @Test
  fun aSecondAskWhileInFlightIsHeldBack() {
    val gate = RecogniseGate()
    val first = Detection("Can", 0.5f, 0.2f, 0.2f, 0.4f, 0.4f)
    val second = Detection("Bottle", 0.4f, 0.6f, 0.6f, 0.8f, 0.8f)
    assertNotNull(gate.consider(Pick("Can", 1_000L, 0.5f, first), emptyList(), listOf(first), 1_000L))
    assertNull(gate.consider(Pick("Bottle", 1_100L, 0.4f, second), emptyList(), listOf(second), 1_100L))
  }

  @Test
  fun afterFinishTheCooldownHoldsTheNextAsk() {
    val gate = RecogniseGate()
    val box = Detection("Can", 0.5f, 0.2f, 0.2f, 0.4f, 0.4f)
    val pick = Pick("Can", 1_000L, 0.5f, box)
    assertNotNull(gate.consider(pick, emptyList(), listOf(box), 1_000L))
    gate.finish(1_200L)
    assertNull(gate.consider(pick, emptyList(), listOf(box), 1_200L + 2_000L))
    val later = gate.consider(pick, emptyList(), listOf(box), 1_200L + RecogniseGate.COOLDOWN_MS)
    assertEquals(box, later?.box)
  }

  @Test
  fun oneClosedSampleDoesNotAsk() {
    val gate = RecogniseGate()
    assertNull(gate.consider(null, listOf(closedHand()), emptyList(), 1_000L))
  }

  @Test
  fun aClosedHandWithNoLitterAsksForThePalm() {
    val gate = RecogniseGate()
    val hand = closedHand()
    assertNull(gate.consider(null, listOf(hand), emptyList(), 1_000L))
    val crop = gate.consider(null, listOf(hand), emptyList(), 1_080L)
    assertEquals(hand.palmBox(), crop?.box)
    assertNull(crop?.hint)
  }

  @Test
  fun aClosedHandOnStrongLitterDoesNotAsk() {
    val gate = RecogniseGate()
    val bottle = Detection("Bottle", 0.9f, 0.35f, 0.25f, 0.65f, 0.7f)
    val hand = listOf(closedHand())
    assertNull(gate.consider(null, hand, listOf(bottle), 1_000L))
    assertNull(gate.consider(null, hand, listOf(bottle), 1_080L))
  }

  @Test
  fun aClosedHandOnAWeakTacoClassAsksForThatBox() {
    val gate = RecogniseGate()
    val wrapper = Detection("Plastic bag & wrapper", 0.4f, 0.35f, 0.25f, 0.65f, 0.7f)
    val hand = listOf(closedHand())
    assertNull(gate.consider(null, hand, listOf(wrapper), 1_000L))
    val crop = gate.consider(null, hand, listOf(wrapper), 1_080L)
    assertEquals(wrapper, crop?.box)
    assertEquals("Plastic bag & wrapper", crop?.hint)
    assertEquals(0.4f, crop?.hintConfidence)
  }

  @Test
  fun aWeakPickWinsOverAClosedHand() {
    val gate = RecogniseGate()
    val can = Detection("Can", 0.6f, 0.1f, 0.1f, 0.2f, 0.2f)
    val wrapper = Detection("Plastic bag & wrapper", 0.4f, 0.35f, 0.25f, 0.65f, 0.7f)
    val crop =
        gate.consider(
            Pick("Can", 1_000L, 0.6f, can),
            listOf(closedHand()),
            listOf(can, wrapper),
            1_000L,
        )
    assertEquals(can, crop?.box)
  }

  @Test
  fun emptyHandsDoNotResetClosedSamples() {
    val gate = RecogniseGate()
    val hand = closedHand()
    assertNull(gate.consider(null, listOf(hand), emptyList(), 1_000L))
    assertNull(gate.consider(null, emptyList(), emptyList(), 1_040L))
    val crop = gate.consider(null, listOf(hand), emptyList(), 1_080L)
    assertEquals(hand.palmBox(), crop?.box)
  }

  private fun closedHand(): HandPose {
    val wristY = 0.62f
    val palm = 0.10f
    val points = MutableList(21) { HandPoint(0.5f, wristY) }
    points[0] = HandPoint(0.5f, wristY)
    points[9] = HandPoint(0.5f, wristY - palm)
    for (index in intArrayOf(4, 8, 12, 16, 20)) points[index] = HandPoint(0.5f, 0.58f)
    return HandPose(points)
  }
}
