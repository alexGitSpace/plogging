package dev.plogging.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class SpotterTest {

  @Test
  fun nothingInViewShowsNothing() {
    assertNull(Spotter().onDetections(emptyList(), 1_000L))
  }

  @Test
  fun pointsOutTheObjectTheDetectorIsSurestOf() {
    val spot =
        Spotter()
            .onDetections(
                listOf(detection("Can", 0.5f, x = 0.2f), detection("Bottle", 0.9f, x = 0.5f)),
                1_000L,
            )!!
    assertEquals("Bottle", spot.detection.label)
    assertEquals(2, spot.count)
  }

  @Test
  fun tellsWhichWayToTurn() {
    assertEquals(DisplayCard.Sign.LEFT, signAt(0.2f))
    assertEquals(DisplayCard.Sign.AHEAD, signAt(0.4f))
    assertEquals(DisplayCard.Sign.AHEAD, signAt(0.6f))
    assertEquals(DisplayCard.Sign.RIGHT, signAt(0.8f))
  }

  @Test
  fun wordsTheCard() {
    val alone = Spotter().onDetections(listOf(detection("bottle", 0.9f, x = 0.2f)), 1_000L)!!
    assertEquals("Bottle", alone.title)
    assertEquals("On your left", alone.detail)

    val among =
        Spotter()
            .onDetections(
                listOf(
                    detection("Plastic bag & wrapper", 0.9f, x = 0.5f),
                    detection("Can", 0.4f, x = 0.1f),
                    detection("Cup", 0.4f, x = 0.9f),
                ),
                1_000L,
            )!!
    assertEquals("Plastic bag & wrapper", among.title)
    assertEquals("Straight ahead · 3 in view", among.detail)
  }

  @Test
  fun returnsTheSameSpotWhileNothingChanges() {
    val spotter = Spotter()
    val first = spotter.onDetections(listOf(detection("Bottle", 0.9f, x = 0.5f)), 1_000L)
    val later = spotter.onDetections(listOf(detection("Bottle", 0.8f, x = 0.52f)), 9_000L)
    assertSame(first, later)
  }

  @Test
  fun staysWithItsObjectWhenTheDetectorIsSurerOfAnother() {
    val spotter = Spotter()
    spotter.onDetections(listOf(detection("Bottle", 0.6f, x = 0.2f)), 1_000L)
    val later =
        spotter.onDetections(
            listOf(detection("Bottle", 0.6f, x = 0.22f), detection("Can", 0.95f, x = 0.8f)),
            1_000L + Spotter.CHANGE_MS + 1,
        )!!
    assertEquals("Bottle", later.detection.label)
    assertEquals(2, later.count)
  }

  @Test
  fun letsASpotStandBeforeChangingIt() {
    val spotter = Spotter()
    val first = spotter.onDetections(listOf(detection("Bottle", 0.9f, x = 0.2f)), 1_000L)!!

    val two = listOf(detection("Bottle", 0.9f, x = 0.2f), detection("Can", 0.5f, x = 0.8f))
    assertSame(first, spotter.onDetections(two, 1_000L + Spotter.CHANGE_MS))

    val changed = spotter.onDetections(two, 1_000L + Spotter.CHANGE_MS + 1)!!
    assertNotSame(first, changed)
    assertEquals(2, changed.count)
  }

  @Test
  fun followsItsObjectAcrossTheFrame() {
    val spotter = Spotter()
    var now = 1_000L
    var spot = spotter.onDetections(listOf(detection("Bottle", 0.9f, x = 0.2f)), now)!!
    assertEquals(DisplayCard.Sign.LEFT, spot.sign)

    // The wearer turns towards it: it drifts to the middle, while the detector is surer of a can.
    for (step in 1..6) {
      now += 500L
      spot =
          spotter.onDetections(
              listOf(
                  detection("Bottle", 0.6f, x = 0.2f + step * 0.05f),
                  detection("Can", 0.95f, x = 0.9f),
              ),
              now,
          )!!
      assertEquals("Bottle", spot.detection.label)
    }
    assertEquals(DisplayCard.Sign.AHEAD, spot.sign)
  }

  @Test
  fun holdsTheSpotThroughMissedFramesThenClearsIt() {
    val spotter = Spotter()
    val first = spotter.onDetections(listOf(detection("Bottle", 0.9f, x = 0.5f)), 1_000L)
    assertSame(first, spotter.onDetections(emptyList(), 1_000L + Spotter.HOLD_MS))
    assertNull(spotter.onDetections(emptyList(), 1_000L + Spotter.HOLD_MS + 1))
  }

  @Test
  fun takesTheNextObjectOnceItsOwnIsGone() {
    val spotter = Spotter()
    spotter.onDetections(
        listOf(detection("Bottle", 0.9f, x = 0.2f), detection("Can", 0.5f, x = 0.8f)),
        1_000L,
    )
    val later = 1_000L + Spotter.CHANGE_MS + 1
    val next = spotter.onDetections(listOf(detection("Can", 0.5f, x = 0.8f)), later)!!
    assertEquals("Can", next.detection.label)
    assertEquals(DisplayCard.Sign.RIGHT, next.sign)
    assertEquals(1, next.count)
  }

  private fun signAt(x: Float) =
      Spotter().onDetections(listOf(detection("Bottle", 0.9f, x)), 1_000L)!!.sign

  /** A small box whose centre is at [x] across the frame, halfway down. */
  private fun detection(label: String, confidence: Float, x: Float) =
      Detection(label, confidence, x - 0.05f, 0.45f, x + 0.05f, 0.55f)
}
