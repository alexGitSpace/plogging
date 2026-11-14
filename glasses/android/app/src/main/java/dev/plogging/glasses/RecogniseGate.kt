package dev.plogging.glasses

internal data class RecogniseCrop(
    val box: Detection,
    val hint: String?,
    val hintConfidence: Float?,
)

/**
 * Sends at most one close-up per uncertain pick. A strong YOLO pick is left alone.
 * A closed hand with no strong box is treated as a pick of something unnamed.
 */
internal class RecogniseGate {
  private var inFlight = false
  private var lastAt = Long.MIN_VALUE
  private var closedSamples = 0

  fun consider(
      pick: Pick?,
      hands: List<HandPose>,
      detections: List<Detection>,
      nowMs: Long,
  ): RecogniseCrop? {
    if (inFlight) return null
    if (lastAt != Long.MIN_VALUE && nowMs - lastAt < COOLDOWN_MS) {
      updateClosed(hands, detections)
      return null
    }
    if (pick != null && pick.confidence >= ASK_BELOW) {
      closedSamples = 0
      return null
    }
    if (pick != null) {
      inFlight = true
      closedSamples = 0
      return cropForPick(pick, detections)
    }
    updateClosed(hands, detections)
    if (closedSamples < PickDetector.CLOSED_SAMPLES) return null
    inFlight = true
    closedSamples = 0
    return cropForHand(hands, detections)
  }

  fun finish(nowMs: Long) {
    inFlight = false
    lastAt = nowMs
  }

  private fun updateClosed(hands: List<HandPose>, detections: List<Detection>) {
    if (hands.isEmpty()) return
    val closed = hands.filter { it.belongsToWearer() && it.curl() <= PickDetector.CLOSED_CURL }
    val strong = detections.filter { it.isLiveLitter() }
    val grabbingStrong = closed.any { hand -> strong.any { hand.touches(it) } }
    if (closed.isEmpty() || grabbingStrong) {
      closedSamples = 0
      return
    }
    closedSamples += 1
  }

  private fun cropForPick(pick: Pick, detections: List<Detection>): RecogniseCrop {
    val box =
        pick.box
            ?: detections.filter { it.label == pick.label }.maxByOrNull { it.confidence }
            ?: Detection(pick.label, pick.confidence, 0f, 0f, 1f, 1f)
    return RecogniseCrop(box, pick.label, pick.confidence)
  }

  private fun cropForHand(hands: List<HandPose>, detections: List<Detection>): RecogniseCrop {
    val closed = hands.filter { it.belongsToWearer() && it.curl() <= PickDetector.CLOSED_CURL }
    val touched = detections.filter { box -> closed.any { it.touches(box) } }
    val weak = touched.maxByOrNull { it.confidence }
    if (weak != null) return RecogniseCrop(weak, weak.label, weak.confidence)
    val palm = closed.firstOrNull()?.palmBox() ?: Detection("other", 0f, 0.25f, 0.25f, 0.75f, 0.75f)
    return RecogniseCrop(palm, null, null)
  }

  companion object {
    const val ASK_BELOW = 0.75f
    const val COOLDOWN_MS = 3_000L
  }
}
