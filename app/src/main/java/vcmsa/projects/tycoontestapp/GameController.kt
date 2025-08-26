package vcmsa.projects.tycoontestapp

class GameController(private val logger: ((String) -> Unit)? = null) {

    var revolution: Boolean = false
        set(value) {
            field = value
            log("Revolution mode is now ${if (value) "ON" else "OFF"}.")
        }

    private val cardStrengths: Array<String>
        get() = if (revolution)
            arrayOf("Joker", "3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K", "A", "2")
        else
            arrayOf("3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K", "A", "2", "Joker")

    /**
      Robustly parse the card's rank value.
      Returns normalized rank strings like "3","4",...,"10","J","Q","K","A","2","Joker".

      Accepts a variety of inputs:
      - "10H", "10h", "10_H", "10-H" => "10"
       - "QS", "qS" => "Q"
      - "RJ","BJ","jrj","joker" => "Joker"

      - "TH" or "T H" will treat 'T' as '10'
     */
    private fun getCardValue(cardRaw: String?): String {
        if (cardRaw == null) return "Unknown"

        val s = cardRaw.trim()

        if (s.isEmpty()) return "Unknown"

        // Quick joker checks (many possible naming variations)
        val lower = s.lowercase()
        val jokerVariants = listOf("rj", "bj", "jrj", "jbj", "joker", "joker_red", "joker_black", "face_joker_red", "face_joker_black", "jokerred", "jokerblack")
        if (jokerVariants.any { lower.contains(it) }) return "Joker"

        // Remove any non-alphanumeric (keeps letters and digits only)
        val alnum = s.filter { it.isLetterOrDigit() }

        if (alnum.isEmpty()) return "Unknown"

        // If the last character is a suit letter (S,H,D,C) then rank portion is everything before it.
        // Otherwise try to interpret full string as rank (some art-names might be rank_4 etc)
        val last = alnum.last()
        val possibleSuits = setOf('S', 's', 'H', 'h', 'D', 'd', 'C', 'c')
        val rankPart = if (possibleSuits.contains(last)) {
            alnum.substring(0, alnum.length - 1)
        } else {
            // Could be "10", "T", "ACE", "A", "rank_4", etc. Try to strip common prefixes/suffixes:
            alnum
        }

        val rankNormalized = when {
            rankPart.isEmpty() -> {
                // maybe single-char like "9" or just suit - fallback
                alnum
            }
            // Accept "T" or "t" as 10
            rankPart.equals("T", ignoreCase = true) -> "10"
            // Accept spelled-out ranks: "A", "J", "Q", "K"
            rankPart.equals("A", ignoreCase = true) -> "A"
            rankPart.equals("J", ignoreCase = true) -> "J"
            rankPart.equals("Q", ignoreCase = true) -> "Q"
            rankPart.equals("K", ignoreCase = true) -> "K"
            // numeric 2..10
            rankPart.toIntOrNull() != null -> {
                val n = rankPart.toInt()
                if (n == 1) {
                    // guard: if someone used "1" meaning "10"
                    log("Note: parsed '1' as '10' for card '$cardRaw'")
                    "10"
                } else n.toString()
            }
            // Accept common textual forms like "TEN", "TENRED", "rank_10", etc.
            rankPart.matches(Regex("(?i).*10.*")) || rankPart.equals("TEN", ignoreCase = true) -> "10"
            // If it looks like rank_a or rank_j
            rankPart.matches(Regex("(?i).*a.*")) -> "A"
            rankPart.matches(Regex("(?i).*j(ack)?.*")) -> "J"
            rankPart.matches(Regex("(?i).*q(ueen)?.*")) -> "Q"
            rankPart.matches(Regex("(?i).*k(ing)?.*")) -> "K"
            // fallback: first character (uppercase) — this is last resort
            else -> rankPart.first().uppercaseChar().toString()
        }

        val finalRank = when (rankNormalized.uppercase()) {
            "1" -> "10"
            "T" -> "10"
            else -> rankNormalized.uppercase()
        }

        return finalRank
    }

    private fun getCardIndex(card: String): Int {
        val value = getCardValue(card)
        val idx = cardStrengths.indexOf(value)
        if (idx == -1) {
            log("Warning: parsed value '$value' for card '$card' not found in cardStrengths: ${cardStrengths.joinToString()}")
        }
        return idx
    }

    /**
     * Validate if a played hand is allowed against the pot.
         and ranking comparisons respect revolution flag.
     */
    fun isValidPlay(playedHand: List<String>, pot: List<String>): Boolean {

        val basePlayed = getBaseValue(playedHand)
        if (!validateHand(playedHand, basePlayed)) {
            log("Invalid hand played -- check play")
            return false
        }

        if (pot.isEmpty()) {
            log("Empty pot, valid")
            return true
        }

        if (playedHand.size != pot.size) {
            log("Played hand and pot must have the same number of cards.")
            return false
        }

        if (playedHand.size == 1) {
            val played = playedHand[0]
            val potCard = pot[0]
            log("Single card played: $played, Pot card: $potCard")
            val playedIdx = getCardIndex(played)
            val potIdx = getCardIndex(potCard)
            if (playedIdx == -1 || potIdx == -1) {
                log("Index lookup failed for single-card comparison.")
                return false
            }

            // Robust 3♠ vs Joker rule: detect rank and suit with normalization
            val playedValue = getCardValue(played)
            val potValue = getCardValue(potCard)
            val playedSuit = getSuitChar(played)
            if (playedValue == "3" && playedSuit == 'S' && potValue == "Joker") {
                log("Special rule: 3♠ beats Joker (single-card).")
                return true
            }

            return if (revolution) playedIdx < potIdx else playedIdx > potIdx
        }


        // For multi-card compare the base rank (first non-joker)
        val basePot = pot.asReversed()
            .firstOrNull { it.isNotEmpty() && getCardValue(it) != "Joker" }
            ?.let { getCardValue(it) }
            ?: "Error"

        if (!validateHand(playedHand, basePlayed)) {
            log("Invalid hand: ${playedHand.joinToString()} vs base $basePlayed")
            return false
        }

        log("Valid hand played, Base = $basePlayed")
        log("Base Played: $basePlayed, Base Pot: $basePot")
        log("Revolution mode: $revolution")
        log("cardStrengths: ${cardStrengths.joinToString(", ")}")
        log("basePlayed raw: '$basePlayed'")
        log("basePot raw: '$basePot'")
        val playedIndex = getCardIndex(basePlayed)
        val potIndex = getCardIndex(basePot)
        if (playedIndex == -1 || potIndex == -1) {
            log("Index lookup failed for multi-card comparison.")
            return false
        }

        val isValid = if (revolution) playedIndex < potIndex else playedIndex > potIndex
        log("Multi-card compare: played=$basePlayed ($playedIndex), pot=$basePot ($potIndex), rev=$revolution → $isValid")

        return isValid
    }


    private fun getBaseValue(cards: List<String>): String {
        val baseCard = cards.firstOrNull { getCardValue(it) != "Joker" } ?: "Joker"
        return getCardValue(baseCard)
    }
    private fun getSuitChar(cardRaw: String?): Char? {
        if (cardRaw == null) return null
        val alnum = cardRaw.filter { it.isLetterOrDigit() }
        if (alnum.isEmpty()) return null
        val last = alnum.last().uppercaseChar()
        return if (last in setOf('S', 'H', 'D', 'C')) last else null
    }

    private fun validateHand(hand: List<String>, baseValue: String): Boolean {
        for (card in hand) {
            val value = getCardValue(card)
            if (value == "Joker" || value == baseValue) continue
            log("Invalid card $card — does not match baseValue $baseValue.")
            return false
        }
        log("Hand validated.")
        return true
    }
//me, vv, avi karan
    //8stop edge case, if 1t card in pot

    fun sortHand(hand: List<String>): List<String> {
        return hand.sortedWith(compareBy(
            { idx -> getCardIndex(idx).takeIf { it >= 0 } ?: Int.MAX_VALUE },
            { getSuitChar(it) ?: 'Z' }
        ))
    }


    fun removePlayedCards(hand: MutableList<String>, playedHand: List<String>) {
        for (card in playedHand) {
            hand.remove(card)
        }
    }

    fun isValidPlayAgainstPot(playedHand: List<String>, potState: List<List<String>>): Boolean {
        val lastPotHand = potState.asReversed()
            .firstOrNull { it.isNotEmpty() } ?: emptyList()
        return isValidPlay(playedHand, lastPotHand)
    }

    fun displayCard(card: String): String {
        return when (card) {
            "RJ", "BJ" -> "Joker"
            else -> card
        }
    }

    private fun log(message: String) {
        logger?.invoke(message) ?: println(message)
    }
}
