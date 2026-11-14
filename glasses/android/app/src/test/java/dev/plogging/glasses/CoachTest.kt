package dev.plogging.glasses

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoachTest {

  @Test
  fun pickLineNamesAKnownLabel() {
    val line = Coach { 0 }.pick("Bottle")
    assertTrue(line.contains("bottle", ignoreCase = true))
  }

  @Test
  fun sameLineIsNotChosenTwiceInARow() {
    val coach = Coach { 0 }
    val first = coach.pick("Bottle")
    val second = coach.pick("Bottle")
    assertNotEquals(first, second)
    assertTrue(first.contains("bottle", ignoreCase = true))
    assertTrue(second.contains("bottle", ignoreCase = true))
  }

  @Test
  fun unknownLabelStillGetsASentence() {
    val line = Coach { 0 }.pick("Widget")
    assertTrue(line.isNotBlank())
    assertTrue(line in Coach.GENERIC_PICK)
    assertFalse(line.contains("widget", ignoreCase = true))
  }
}
