package com.example.unogame.models

import kotlinx.serialization.Serializable

@Serializable
enum class Color {
    RED, YELLOW, GREEN, BLUE, WILD
}

@Serializable
enum class Value {
    ZERO, ONE, TWO, THREE, FOUR, FIVE, SIX, SEVEN, EIGHT, NINE,
    SKIP, REVERSE, DRAW_TWO,
    WILD, WILD_DRAW_FOUR
}

@Serializable
data class Card(val color: Color, val value: Value, val id: Int = Companion.nextId++) {
    // Note: Use of a companion object static counter for IDs can be problematic if Card instances
    // are created on different devices without synchronization. For a networked game,
    // card IDs should ideally be assigned by a central authority (the deck on the host)
    // or cards should be identified by properties (color/value) if IDs are not globally unique.
    // The current Deck implementation creates all cards with unique IDs sequentially, so it's fine
    // as long as only the host's Deck is the source of truth for cards.
    companion object {
        @kotlinx.serialization.Transient // nextId should not be part of serialization
        private var nextId = 0

        // Call this if you need to reset ID generation, e.g., for new games or testing
        fun resetNextId() {
            nextId = 0
        }
    }

    override fun toString(): String {
        return if (color == Color.WILD) {
            value.name
        } else {
            "${color.name}_${value.name}"
        }
    }

    fun isSpecialActionCard(): Boolean {
        return value == Value.SKIP || value == Value.REVERSE || value == Value.DRAW_TWO
    }

    fun isWildCard(): Boolean {
        return value == Value.WILD || value == Value.WILD_DRAW_FOUR
    }
}
