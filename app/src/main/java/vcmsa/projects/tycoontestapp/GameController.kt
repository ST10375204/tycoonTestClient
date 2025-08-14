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

    private fun getCardValue(card: String): String {
        return when (card) {
            "RJ", "BJ", "Joker" -> "Joker" // treat both jokers as the same for value comparisons
            "10S", "10H", "10D", "10C" -> "10"
            else -> card.first().toString()
        }
    }

    private fun getCardIndex(card: String): Int {
        val value = getCardValue(card)
        val idx = cardStrengths.indexOf(value)
        if (idx == -1) log("Warning: '$value' not found in cardStrengths: ${cardStrengths.joinToString()}")
        return idx
    }

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
            return if (revolution)
                getCardIndex(played) < getCardIndex(potCard)
            else
                getCardIndex(played) > getCardIndex(potCard)
        }

        val basePot = pot.asReversed()
            .firstOrNull { it.isNotEmpty() && getCardValue(it) != null && getCardValue(it) != "Joker" }
            ?.let { getCardValue(it) }
            ?: "Error"

        log("Valid hand played, Base = $basePlayed")
        log("Base Played: $basePlayed, Base Pot: $basePot")
        log("Revolution mode: $revolution")
        log("cardStrengths: ${cardStrengths.joinToString(", ")}")
        log("basePlayed raw: '$basePlayed'")
        log("basePot raw: '$basePot'")
        log("basePlayed index: ${getCardIndex(basePlayed)}")
        log("basePot index: ${getCardIndex(basePot)}")

        val playedIndex = getCardIndex(basePlayed)
        val potIndex = getCardIndex(basePot)
        val isValid = if (revolution)
            playedIndex < potIndex
        else
            playedIndex > potIndex

        log("Comparing played to pot: $playedIndex ${if (revolution) "<" else ">"} $potIndex = $isValid")
        return isValid
    }

    private fun getBaseValue(cards: List<String>): String {
        val base = cards.firstOrNull { getCardValue(it) != "Joker" } ?: "Joker"
        return getCardValue(base)
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
