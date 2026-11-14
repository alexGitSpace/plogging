package dev.plogging.glasses

/**
 * One object found in a frame. The box is normalised to 0..1 with the origin in the
 * top-left corner of the frame.
 */
data class Detection(
    /** Class name from the model, such as `bottle`. */
    val label: String,
    /** 0 to 1. */
    val confidence: Float,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
  fun countsAsLitter(): Boolean = label in NAMED_LITTER

  fun isLiveLitter(): Boolean = countsAsLitter() && confidence >= LIVE_CONFIDENCE

  companion object {
    const val LIVE_CONFIDENCE = 0.55f

    private val NAMED_LITTER =
        setOf(
            "Bottle",
            "Can",
            "Cup",
        )
  }
}
