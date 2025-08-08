package vcmsa.projects.tycoontestapp

class GameController {
    var revolution: Boolean = false
        set(value) {
            field = value
            println("Revolution mode is now ${if (value) "ON" else "OFF"}.")
        }

    private val cardStrengths: Array<String>
        get() = if (revolution) //if false joker high, if true joker low
            arrayOf("Joker", "3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K", "A", "2")
        else
            arrayOf("3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K", "A", "2", "Joker")


    private fun getCardValue(card: String): String {
        return when {
            card == "Joker" -> "Joker"
            card.length > 2 && card.startsWith("10") -> "10"
            else -> card.first().toString()
        }
    }


    private fun getCardIndex(card: String): Int {
        return cardStrengths.indexOf(getCardValue(card))
    }

    fun isValidPlay(playedHand: List<String>, pot: List<String>): Boolean {

        if (pot.isEmpty()){
            println("empty pot, valid")
            return true
        }

        if (playedHand.size != pot.size) {
            println("Played hand and pot must have the same number of cards.")
            return false
        }

        if (playedHand.size == 1) {
            val played = playedHand[0]
            val potCard = pot[0]
            println("Single card played: $played, Pot card: $potCard")
            return if (revolution)
                getCardIndex(played) > getCardIndex(potCard)
            else
                getCardIndex(played) < getCardIndex(potCard)
        }

        val basePlayed = getBaseValue(playedHand)
        if (!validateHand(playedHand, basePlayed)) {
            println("Invalid hand played --check play")
            return false
        }

        val basePot = pot.firstOrNull { getCardValue(it) != "Joker" }?.let { getCardValue(it) } ?: "Joker"

        println("Valid hand played, Base = $basePlayed")
        println("Base Played: $basePlayed, Base Pot: $basePot")

        println("Revolution mode: $revolution")
        println("cardStrengths: ${cardStrengths.joinToString(", ")}")
        println("basePlayed raw: '$basePlayed'")
        println("basePot raw: '$basePot'")
        println("basePlayed index: ${getCardIndex(basePlayed)}")
        println("basePot index: ${getCardIndex(basePot)}")
        println("Comparing played to pot: ")
        val playedIndex = getCardIndex(basePlayed)
        val potIndex = getCardIndex(basePot)

        val isValid = if (revolution)
            playedIndex < potIndex
        else
            playedIndex > potIndex

        println("Comparing played to pot: $playedIndex ${if (revolution) "<" else ">"} $potIndex = $isValid")

        println("hit default")
        return isValid //default case
    }

    private fun getBaseValue(cards: List<String>): String {
        val base = cards.firstOrNull { getCardValue(it) != "Joker" } ?: "Joker"
        return getCardValue(base)
    }

    private fun validateHand(hand: List<String>, baseValue: String): Boolean {
        for (card in hand) {
            val value = getCardValue(card)
            if (value == "Joker" || value == baseValue) continue
            println("Invalid card $card — does not match baseValue $baseValue.")
            return false
        }
        println("Hand validated.")
        return true
    }

    // Kotlin wrapper for MainActivity use
    fun isValidPlayAgainstPot(playedHand: List<String>, potState: List<List<String>>): Boolean {
        val lastPotHand = potState.lastOrNull() ?: emptyList()
        return isValidPlay(playedHand, lastPotHand)
    }

}