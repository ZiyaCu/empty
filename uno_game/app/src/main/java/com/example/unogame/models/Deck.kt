package com.example.unogame.models

import java.util.Collections
import java.util.Stack

class Deck {
    private val cards = Stack<Card>()

    init {
        reset()
    }

    fun reset() {
        cards.clear()
        // Add number cards
        for (color in Color.values()) {
            if (color == Color.WILD) continue
            // Each color has one 0 card
            cards.add(Card(color, Value.ZERO))
            // Each color has two of each number 1-9
            for (i in 1..9) {
                val value = Value.values().first { it.ordinal == i }
                cards.add(Card(color, value))
                cards.add(Card(color, value))
            }
            // Each color has two Skip, two Reverse, two Draw Two
            cards.add(Card(color, Value.SKIP))
            cards.add(Card(color, Value.SKIP))
            cards.add(Card(color, Value.REVERSE))
            cards.add(Card(color, Value.REVERSE))
            cards.add(Card(color, Value.DRAW_TWO))
            cards.add(Card(color, Value.DRAW_TWO))
        }

        // Add Wild cards (4 of each)
        for (i in 0..3) {
            cards.add(Card(Color.WILD, Value.WILD))
            cards.add(Card(Color.WILD, Value.WILD_DRAW_FOUR))
        }
        shuffle()
    }

    fun shuffle() {
        cards.shuffle()
    }

    fun draw(): Card? {
        return if (cards.isNotEmpty()) {
            cards.pop()
        } else {
            null // Or handle reshuffling discard pile into draw pile
        }
    }

    fun drawMultiple(count: Int): List<Card> {
        val drawnCards = mutableListOf<Card>()
        for (i in 0 until count) {
            draw()?.let { drawnCards.add(it) } ?: break // Stop if deck is empty
        }
        return drawnCards
    }

    fun isEmpty(): Boolean {
        return cards.isEmpty()
    }

    fun addCard(card: Card) {
        cards.add(card)
    }

    fun addCards(cardsToAdd: List<Card>) {
        cards.addAll(cardsToAdd)
    }

    val size: Int
        get() = cards.size
}
