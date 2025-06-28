package com.example.unogame.models

data class Player(val id: String, var name: String, val isHuman: Boolean = true) {
    val hand: MutableList<Card> = mutableListOf()

    fun addCardToHand(card: Card) {
        hand.add(card)
    }

    fun addCardsToHand(cards: List<Card>) {
        hand.addAll(cards)
    }

    fun removeCardFromHand(card: Card): Boolean {
        return hand.remove(card)
    }

    fun playCard(card: Card): Card? {
        return if (hand.remove(card)) {
            card
        } else {
            null // Card not in hand
        }
    }

    fun hasPlayableCard(topCard: Card, chosenWildColor: Color?): Boolean {
        return hand.any { canPlayCard(it, topCard, chosenWildColor, false) } // Don't check WDFour legality here, just general playability
    }

    // new parameter to check for wild draw four legality
    fun canPlayCard(cardToPlay: Card, topCard: Card, chosenWildColor: Color?, checkWildDrawFourLegality: Boolean = true): Boolean {
        // Rule 1: Playing a Wild card (Regular Wild or Wild Draw Four)
        if (cardToPlay.value == Value.WILD) return true // Regular Wild can always be played.
        if (cardToPlay.value == Value.WILD_DRAW_FOUR) {
            if (!checkWildDrawFourLegality) return true // If just checking general wild playability for UI hints, not strict rule.

            // Legality for playing Wild Draw Four:
            // Player can only play it if they do NOT have another card in hand that matches the CURRENT active color.
            // The current active color is `chosenWildColor` if `topCard` is Wild, otherwise it's `topCard.color`.
            val activeColor = if (topCard.color == Color.WILD) chosenWildColor else topCard.color

            if (activeColor == Color.WILD || activeColor == null) {
                // This means either the top card is a Wild whose color hasn't been chosen (e.g. game start, error),
                // or the activeColor is somehow still WILD. In such cases, WDF is typically allowed.
                return true
            }

            val hasMatchingColorCard = hand.any { handCard ->
                handCard.color == activeColor // && handCard.value != Value.WILD_DRAW_FOUR (already covered by checking cardToPlay first)
            }
            return !hasMatchingColorCard // Can play WDFour if NO card matches current color.
        }

        // Rule 2: Playing a non-Wild card
        // The card to play must match the top card's color OR value.
        // If the top card is a Wild card, its effective color is `chosenWildColor`.
        if (topCard.color == Color.WILD) {
            if (chosenWildColor == null) {
                // This is an invalid game state: a Wild card is on top, but no color has been chosen.
                // This should be prevented by game flow: player *must* choose a color when playing a Wild.
                return false // Cannot play a colored card if Wild's color is not chosen.
            }
            // If top card is Wild, a non-Wild card must match the chosenWildColor.
            // Value matching is not applicable against the Wild card itself.
            return cardToPlay.color == chosenWildColor
        } else {
            // Top card is a regular colored card. Match color or value.
            return cardToPlay.color == topCard.color || cardToPlay.value == topCard.value
        }
    }

    fun getHandSize(): Int {
        return hand.size
    }

    fun hasUno(): Boolean {
        return hand.size == 1
    }

    fun hasWon(): Boolean {
        return hand.isEmpty()
    }

    override fun toString(): String {
        return "$name (ID: $id, Hand: ${hand.joinToString()})"
    }
}
