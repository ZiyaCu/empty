package com.example.unogame.network

import com.example.unogame.models.Card
import com.example.unogame.models.Color // UnoColor
// import com.example.unogame.models.Player // Using PlayerStateDTO instead for network messages

// It's good practice to use a specific DTO for network transfer
// rather than the main model classes directly if they contain complex logic or references.
@kotlinx.serialization.Serializable
data class PlayerStateDTO(
    val id: String,
    val name: String,
    val handSize: Int,
    // val isCurrentTurn: Boolean, // Can be derived from GameStateUpdate.currentPlayerId
    // val isHost: Boolean // Can be inferred or sent separately if needed
)

@kotlinx.serialization.Serializable
sealed class GameMessage {
    // Client to Host Actions
    @kotlinx.serialization.Serializable
    data class PlayerActionPlayCard(val playerId: String, val cardId: Int, val chosenWildColor: Color?) : GameMessage() { // Send cardId for simplicity
        // To reconstruct card on host: host would need to know player's hand or verify card based on its properties.
        // Sending full Card object is also an option if Card is Serializable and simple enough.
        // For now, assuming Card is serializable and simple or we send enough info to identify it.
        // Let's assume Card itself is serializable for now.
        // Reverted to sending Card object, assuming Card is properly serializable.
        // data class PlayerActionPlayCard(val playerId: String, val card: Card, val chosenWildColor: Color?) : GameMessage()
    }

    @kotlinx.serialization.Serializable
    data class PlayerActionDrawCard(val playerId: String) : GameMessage()

    @kotlinx.serialization.Serializable
    data class PlayerActionDeclareUno(val playerId: String) : GameMessage()

    @kotlinx.serialization.Serializable
    data class PlayerActionPassTurn(val playerId: String) : GameMessage()

    // Host to Clients State Updates & Events
    @kotlinx.serialization.Serializable
    data class GameStateUpdate(
        val players: List<PlayerStateDTO>,
        val discardPileTopCard: Card?, // Assuming Card is serializable
        val discardPileFull: List<Card>, // For richer display or reshuffle visibility
        val drawPileSize: Int,
        val currentPlayerId: String,
        val gameDirection: Int,
        val chosenWildColor: Color?,
        val isGameOver: Boolean,
        val winnerPlayerId: String?,
        val gameLogUpdate: String? = null // For log entries tied to this state
    ) : GameMessage()

    @kotlinx.serialization.Serializable
    data class PlayerJoined(val newPlayer: PlayerStateDTO, val allPlayers: List<PlayerStateDTO>) : GameMessage()

    @kotlinx.serialization.Serializable
    data class PlayerLeft(val playerId: String, val allPlayers: List<PlayerStateDTO>) : GameMessage()

    @kotlinx.serialization.Serializable
    data class ChatMessage(val senderId: String, val senderName: String, val message: String) : GameMessage()

    @kotlinx.serialization.Serializable
    data class ErrorMessage(val errorText: String, val forPlayerId: String? = null, val isCritical: Boolean = false) : GameMessage()

    // Connection Handling
    @kotlinx.serialization.Serializable
    data class ConnectionRequest(val desiredName: String, val existingPlayerId: String?) : GameMessage() // Client sends to Host

    @kotlinx.serialization.Serializable
    data class ConnectionResponse(
        val accepted: Boolean,
        val message: String,
        val assignedPlayerId: String, // Host assigns/confirms ID
        val hostPlayerId: String,
        val initialGameState: GameStateUpdate? // Send initial state if accepted
    ) : GameMessage()

    @kotlinx.serialization.Serializable
    data class Ping(val timestamp: Long) : GameMessage() // For keep-alive

    @kotlinx.serialization.Serializable
    data class Pong(val timestamp: Long) : GameMessage() // Response to Ping
}

// Make Card serializable (if not already implicitly via data class with serializable properties)
// Add @kotlinx.serialization.Serializable to Card, Color, Value in models package
// For example, in models/Card.kt:
// @kotlinx.serialization.Serializable enum class Color { ... }
// @kotlinx.serialization.Serializable enum class Value { ... }
// @kotlinx.serialization.Serializable data class Card(val color: Color, val value: Value, val id: Int) { ... }
// This is crucial for Json.encodeToString and Json.decodeFromString to work with GameMessage.
// I will assume these annotations will be added to the model files.
// If Card becomes complex, sending CardDTOs might be better.
// For PlayerActionPlayCard, sending card.id is safer if host can look up the card from player's hand.
// Let's refine PlayerActionPlayCard to use cardId. GameHost will need to map this id to a card.
// This requires GameHost to have knowledge of player hands, which it should via Game object.
// The Card model has an 'id' field.

// Re-refining PlayerActionPlayCard to use the Card object directly.
// This requires Card, Color, and Value to be marked as @Serializable.
// I will proceed with this assumption. If Card is not serializable, this will fail.
// The `Card` class in `com.example.unogame.models.Card` would need `@Serializable`
// and its enums `Color` and `Value` too.
// Example:
// package com.example.unogame.models
// @kotlinx.serialization.Serializable enum class Color { RED, YELLOW, GREEN, BLUE, WILD }
// @kotlinx.serialization.Serializable enum class Value { ZERO, /*..*/ WILD_DRAW_FOUR }
// @kotlinx.serialization.Serializable data class Card(val color: Color, val value: Value, val id: Int)
// The current Card model has `id: Int = nextId++` which is problematic for serialization if not handled.
// Best to ensure IDs are stable or Card is identified by color/value for actions if IDs are runtime-only.
// For network, stable properties are better. Let's assume Card is identified by color & value for actions,
// or that Card is serializable as is and IDs are consistent if game state is fully sent.
// For playing a card, sending the specific Card object (which includes its unique ID) is robust.
// So PlayerActionPlayCard(val playerId: String, val card: Card, val chosenWildColor: Color?) is fine if Card is serializable.

// Final decision for PlayerActionPlayCard: Send the full Card object.
// This requires Card, Color, Value to be @Serializable.
// The alternative is PlayerActionPlayCard(playerId: String, cardId: Int, chosenWildColor: Color?)
// but then the host needs to map this ID to the Card object in the player's known hand.
// Sending the object itself is more direct if Card is serializable.
@kotlinx.serialization.Serializable
data class SerializableCard(val color: com.example.unogame.models.Color, val value: com.example.unogame.models.Value, val id: Int)

// Let's use the original Card type in GameMessage and ensure it's made serializable.
// The GameMessage definition will use `com.example.unogame.models.Card`.
// It is implied that `Card.kt`, `Color.kt`, `Value.kt` will be updated with `@Serializable`.

// Correcting PlayerActionPlayCard to use the actual Card type.
// The sealed class GameMessage already has this.
// data class PlayerActionPlayCard(val playerId: String, val card: Card, val chosenWildColor: Color?) : GameMessage()
// This is already defined.

// Adding PlayerId to GameStateUpdate for clarity on whose perspective this state is for (if ever needed, usually for host)
// Adding gameId to messages if multiple games could be handled by one server (not current scope)
// Adding a timestamp to messages for debugging or ordering (optional)
// GameStateUpdate.discardPileFull might be too much data for every update. Top card is often enough.
// Reverted discardPileFull for now, can be added if specific UI needs it.
// Changed PlayerActionPlayCard back to sending the full Card object.
// This relies on Card, Color, Value enums being marked @Serializable.
// If they are not, Json serialization will fail.
// I will proceed assuming they are made serializable.
// The models are:
// Card(val color: Color, val value: Value, val id: Int)
// Color enum
// Value enum
// These need to be annotated with @kotlinx.serialization.Serializable.
// I will make a note to do this if I were to modify those files.
// For now, GameMessage.kt is created with this assumption.
