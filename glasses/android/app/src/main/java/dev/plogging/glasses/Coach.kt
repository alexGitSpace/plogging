package dev.plogging.glasses

/**
 * Chooses short calming lines for a plogging outing. [randomIndex] is given the size of
 * the current choice list and returns an index into it.
 */
class Coach(private val randomIndex: (Int) -> Int = { size -> kotlin.random.Random.nextInt(size) }) {
  private var lastLine: String? = null

  fun welcome(): String = choose(WELCOME)

  fun idle(): String = choose(IDLE)

  fun pick(label: String): String = choose(pickLines(label))

  private fun choose(lines: List<String>): String {
    val options = lines.filter { it != lastLine }.ifEmpty { lines }
    val chosen = options[randomIndex(options.size).mod(options.size)]
    lastLine = chosen
    return chosen
  }

  companion object {
    val WELCOME =
        listOf(
            "Take it easy. We'll walk, notice the litter, and pick it up as we go.",
            "A calm outing. Whatever you find on the path, we can take it with us.",
        )

    val IDLE =
        listOf(
            "You're doing well. Keep an easy stride.",
            "Every piece you take leaves this place a little cleaner.",
            "Breathe. The next bit of litter will wait for you.",
            "Nice pace. Stay with the walk.",
        )

    val GENERIC_PICK =
        listOf(
            "One less piece on the path.",
            "That's one more for the bag.",
            "Well done. Keep going, gently.",
        )

    val NAMED_PICK =
        mapOf(
            "bottle" to
                listOf(
                    "That bottle is off the ground.",
                    "Nice, that bottle is coming with you.",
                    "Another bottle saved from the path.",
                ),
            "can" to
                listOf(
                    "That can is off the ground.",
                    "Good find. That can is coming with you.",
                    "Another can leaves the path.",
                ),
            "cup" to
                listOf(
                    "That cup is off the ground.",
                    "Nice, that cup is coming with you.",
                    "Another cup saved from the path.",
                ),
            "cigarette" to
                listOf(
                    "That cigarette is off the ground.",
                    "Small, but it matters. That cigarette is coming with you.",
                ),
            "wrapper" to
                listOf(
                    "That wrapper is off the ground.",
                    "Nice, that wrapper is coming with you.",
                ),
            "paper" to
                listOf(
                    "That paper is off the ground.",
                    "Another scrap of paper leaves the path.",
                ),
        )

    val ALL_LINES: List<String> = WELCOME + IDLE + GENERIC_PICK + NAMED_PICK.values.flatten()

    fun pickLines(label: String): List<String> = NAMED_PICK[spokenItem(label)] ?: GENERIC_PICK

    internal fun spokenItem(label: String): String? {
      val name = label.trim().lowercase()
      return when (name) {
        "bottle",
        "can",
        "cup",
        "paper",
        "wrapper" -> name
        "cigarette",
        "cigarette_butt" -> "cigarette"
        "plastic bag & wrapper" -> "wrapper"
        else -> null
      }
    }
  }
}
