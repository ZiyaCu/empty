package com.example.unogame.models

import java.util.Stack
import java.util.UUID

// Ensure Card, Color, Value are serializable for network messages
// For example, add @kotlinx.serialization.Serializable to them.

interface GameListener {
    fun onTurnChanged(newPlayerId: String)
    fun onCardPlayed(player: Player, card: Card)
    fun onCardDrawn(player: Player, card: Card)
    fun onPlayerWon(player: Player)
    fun onWildColorChosen(color: Color)
    fun onUnoCalled(player: Player)
    fun onNextPlayerTurn(player: Player) // Player whose turn is about to start
    fun onInvalidPlay(player: Player, card: Card)
    fun onDeckEmpty()
    fun onHandUpdated(player: Player) // When a player's hand changes significantly
    fun onGameLog(message: String)
    // New events for player management if dynamic
    fun onPlayerAdded(player: Player, allPlayers: List<Player>)
    fun onPlayerRemoved(playerId: String, allPlayers: List<Player>)
    fun onGameStateChanged() // Generic event to signal a full state refresh might be needed
}

class Game {

    val players: MutableList<Player> = mutableListOf()
    val drawPile: Deck = Deck() // Made public for GameHost to get size
    val discardPile: Stack<Card> = Stack()

    var currentPlayerIndex: Int = 0
        private set
    var currentDirection: Int = 1 // 1 for forward, -1 for reverse
        private set
    var isGameOver: Boolean = false
        private set
    var chosenWildColor: Color? = null
        private set

    private val listeners = mutableListOf<GameListener>()

    // Constructor for fixed players (local game)
    constructor(playerIdsAndNames: List<Pair<String, String>>, initialListener: GameListener? = null) {
        if (playerIdsAndNames.size < 2 || playerIdsAndNames.size > 4) {
            listeners.forEach { it.onGameLog("Error: Game must have 2 to 4 players.") }
            // isGameOver = true; // Or handle error state
            // return
        }
        playerIdsAndNames.forEach { players.add(Player(it.first, it.second)) }
        initialListener?.let { addGameListener(it) }
        if (players.isNotEmpty()) { // Only start if players were successfully added
            startGame()
        } else {
            isGameOver = true; // Cannot start game
            listeners.forEach{ it.onGameLog("Failed to initialize players for the game.")}
        }
    }

    // Constructor for dynamic players (network game host might use this)
    constructor(hostPlayerId: String, hostPlayerName: String, initialListener: GameListener? = null) {
        val host = Player(hostPlayerId, hostPlayerName)
        players.add(host)
        initialListener?.let { addGameListener(it) }
        // Game doesn't auto-start here; host waits for clients.
        // Call startGame() once enough players have joined.
        // Or, host calls a specific "initializeForNetwork()"
        listeners.forEach { it.onGameLog("Game created for host ${host.name}. Waiting for players...")}
        listeners.forEach { it.onPlayerAdded(host, players.toList())} // Notify host player added
    }


    fun addGameListener(listener: GameListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    fun removeGameListener(listener: GameListener) {
        listeners.remove(listener)
    }

    // --- Player Management Methods (for network games) ---
    fun addPlayer(playerId: String, playerName: String): Player? {
        if (players.size >= 4) {
            listeners.forEach { it.onGameLog("Cannot add player $playerName, game is full.") }
            return null
        }
        if (players.any { it.id == playerId || it.name == playerName}) {
             listeners.forEach { it.onGameLog("Player $playerName (id: $playerId) already exists or name taken.") }
            return players.find { it.id == playerId || it.name == playerName } // Return existing if duplicate
        }
        val newPlayer = Player(playerId, playerName)
        players.add(newPlayer)
        listeners.forEach { it.onPlayerAdded(newPlayer, players.toList()) }
        listeners.forEach { it.onGameLog("Player ${newPlayer.name} added to the game.") }
        listeners.forEach { it.onGameStateChanged() }
        return newPlayer
    }

    fun removePlayer(playerIdToRemove: String): Boolean {
        val playerToRemove = players.find { it.id == playerIdToRemove }
        if (playerToRemove != null) {
            val wasCurrentPlayer = playerToRemove.id == getCurrentPlayer().id
            players.remove(playerToRemove)
            listeners.forEach { it.onPlayerRemoved(playerIdToRemove, players.toList()) }
            listeners.forEach { it.onGameLog("Player ${playerToRemove.name} removed from the game.") }

            if (players.size < 2 && !isGameOver) { // Not enough players to continue
                isGameOver = true
                listeners.forEach { it.onGameLog("Game over: Not enough players to continue after ${playerToRemove.name} left.")}
                // No specific winner unless rules for this scenario exist
            } else if (wasCurrentPlayer && !isGameOver) {
                // If the removed player was the current player, advance turn carefully
                // The current logic of moveToNextPlayer might try to find an index that's now out of bounds
                // or skip incorrectly. Simplest is to recalculate current player index based on remaining players.
                // Let's assume moveToNextPlayer handles this by wrapping around correctly with new players.size.
                // However, currentPlayerIndex might now be invalid.
                // A robust way: if current player is removed, set current player to (currentPlayerIndex % new_size)
                // or just call moveToNextPlayer which should re-evaluate based on new list.
                // This needs careful testing. For now, let's assume moveToNextPlayer is called.
                // If currentPlayerIndex was pointing to the removed player, it might need adjustment before calling moveToNextPlayer.
                currentPlayerIndex %= players.size // Adjust index before moving, ensures it's valid
                moveToNextPlayer() // This will then correctly pick the next based on new list
            }
            listeners.forEach { it.onGameStateChanged() }
            return true
        }
        return false
    }

    // Call this when enough players (e.g., 2) have joined a network game
    fun prepareAndStartGame() {
        if (players.size < 2) {
            listeners.forEach { it.onGameLog("Cannot start game, not enough players. Need at least 2.") }
            return
        }
        if (discardPile.isNotEmpty()) { // Game already started or in progress
            listeners.forEach { it.onGameLog("Game is already in progress or started.") }
            return
        }
        startGame()
    }


    fun getCurrentPlayer(): Player {
        if (players.isEmpty()) {
            // This should not happen in a running game.
            // Create a dummy player or throw exception.
            // For robustness, let's ensure it doesn't crash, but log heavily.
            listeners.forEach { it.onGameLog("CRITICAL ERROR: getCurrentPlayer called with no players!")}
            // To prevent crash, return a temporary placeholder. This state should be fixed.
            return Player(UUID.randomUUID().toString(), "ErrorPlayer", false)
        }
        return players[currentPlayerIndex]
    }

    private fun startGame() {
        drawPile.reset()
        drawPile.shuffle()
        discardPile.clear() // Ensure discard pile is empty before starting
        isGameOver = false
        chosenWildColor = null
        currentDirection = 1
        currentPlayerIndex = 0 // Typically starts with the first player (host in network game)

        dealInitialCards()

        var firstCard = drawPile.draw()
        while (firstCard != null && firstCard.value == Value.WILD_DRAW_FOUR) {
            drawPile.addCard(firstCard)
            drawPile.shuffle()
            firstCard = drawPile.draw()
        }

        if (firstCard == null) {
            listeners.forEach { it.onGameLog("Error: Deck empty during setup after trying to draw first card.") }
            isGameOver = true
            return
        }

        discardPile.push(firstCard)
        listeners.forEach { it.onGameLog("First card is: $firstCard") }

        // Special handling for the first card if it's an action card
        var initialPlayerAffected = players[currentPlayerIndex]

        if (firstCard.value == Value.SKIP) {
            listeners.forEach { it.onGameLog("First card is a Skip. ${initialPlayerAffected.name}'s turn is skipped.") }
            moveToNextPlayer() // Current player is skipped
        } else if (firstCard.value == Value.REVERSE) {
            listeners.forEach { it.onGameLog("First card is a Reverse. Direction reversed.") }
            currentDirection *= -1
            if (players.size == 2) {
                 moveToNextPlayer() // Acts like Skip for 2 players
            } else {
                // The player who would have gone (player 0) is now effectively skipped as direction reverses
                // The new current player becomes the one "before" player 0 in the new direction
                currentPlayerIndex = (players.size - 1 + players.size) % players.size // Start with the "last" player
            }
        } else if (firstCard.value == Value.DRAW_TWO) {
            listeners.forEach { it.onGameLog("First card is a Draw Two. ${initialPlayerAffected.name} draws two and is skipped.") }
            val drawnCards = drawPile.drawMultiple(2)
            ensureDeckHasCards(2) // Ensure this call is meaningful
            initialPlayerAffected.addCardsToHand(drawnCards)
            listeners.forEach { listener -> drawnCards.forEach { c -> listener.onCardDrawn(initialPlayerAffected, c) } }
            listeners.forEach { it.onHandUpdated(initialPlayerAffected) }
            moveToNextPlayer() // Current player (who drew) is skipped
        } else if (firstCard.color == Color.WILD) {
            // If the first card is a regular Wild, the first player (currentPlayerIndex is still 0) chooses the color.
            // The game will wait for this player to make a choice.
            // No automatic color choice here; Game.playCard will handle it when player plays the Wild.
            // For the first card, this means the first player's turn involves choosing a color for this Wild.
            // This is a special state. The UI for player 0 needs to prompt for color.
            // The GameHost will need to get this color choice from the client (if host isn't player 0)
            // or from local UI (if host is player 0).
            // `chosenWildColor` will be set by a subsequent action.
            listeners.forEach { it.onGameLog("${initialPlayerAffected.name} must choose a color for the starting Wild card.") }
            // The game is now waiting for this choice. No turn advance yet.
            // The `playCard` logic for a Wild card needs to be invoked with this `firstCard`
            // by the current player, specifying a color.
            // This is a tricky part of starting with a Wild.
            // For now, we'll assume the first player will make a "play" of this wild card to set its color.
        }

        listeners.forEach { it.onNextPlayerTurn(getCurrentPlayer()) }
        listeners.forEach { it.onTurnChanged(getCurrentPlayer().id) }
        listeners.forEach { it.onGameStateChanged() } // Broad initial state change
    }

    private fun dealInitialCards() {
        players.forEach { player ->
            player.addCardsToHand(drawPile.drawMultiple(7))
            listeners.forEach { it.onHandUpdated(player) }
        }
    }

    fun playCard(playerId: String, cardToPlayId: Int, newColorForWild: Color? = null): Boolean {
        if (isGameOver || playerId != getCurrentPlayer().id) {
            listeners.forEach { it.onGameLog("Not player's turn or game is over. Player: $playerId, Current: ${getCurrentPlayer().id}") }
            return false
        }

        val player = getCurrentPlayer()
        val cardToPlay = player.hand.find { it.id == cardToPlayId }

        if (cardToPlay == null) {
            listeners.forEach { it.onGameLog("Player ${player.name} attempted to play card ID $cardToPlayId not in hand.")}
            listeners.forEach { it.onInvalidPlay(player, Card(Color.WILD, Value.WILD, cardToPlayId)) } // Dummy card for event
            return false
        }

        val topCard = discardPile.peek()

        // Special case: If the top card is a Wild from game start, and current player is playing it "again" to set color
        var isSettingColorForStartingWild = false
        if (discardPile.size == 1 && topCard.isWildCard() && chosenWildColor == null && cardToPlay.id == topCard.id) {
            if (cardToPlay.isWildCard() && newColorForWild != null && newColorForWild != Color.WILD) {
                isSettingColorForStartingWild = true
            } else if (!cardToPlay.isWildCard()){
                // Trying to play a non-wild card on an unchosen starting wild. This shouldn't happen.
                // The rule is the first player *must* declare the color of the starting wild.
            }
        }


        if (isSettingColorForStartingWild || player.canPlayCard(cardToPlay, topCard, chosenWildColor)) {
            if (!isSettingColorForStartingWild) { // If not special first wild case, remove from hand
                 player.playCard(cardToPlay) // Removes from hand
                 discardPile.push(cardToPlay)
            }
            // If isSettingColorForStartingWild, card is already on discard pile. We are just choosing its color.

            listeners.forEach { it.onCardPlayed(player, cardToPlay) }
            listeners.forEach { it.onHandUpdated(player) }
            listeners.forEach { it.onGameLog("${player.name} played $cardToPlay") }

            // Reset chosenWildColor from previous turn IF the new card isn't wild itself,
            // OR if it is wild and a new color is being chosen.
            // If a non-wild is played on a wild, the wild effect (chosen color) ends.
            if (!cardToPlay.isWildCard()) {
                chosenWildColor = null
            }

            if (player.hasWon()) {
                isGameOver = true
                listeners.forEach { it.onPlayerWon(player) }
                listeners.forEach { it.onGameLog("${player.name} has won!") }
                listeners.forEach { it.onGameStateChanged() }
                return true
            }

            if (player.hasUno()) {
                listeners.forEach { it.onUnoCalled(player) }
                listeners.forEach { it.onGameLog("${player.name} shouts UNO!") }
            }

            // Handle card actions
            var skipNextPlayerAfterDraw = false
            when (cardToPlay.value) {
                Value.SKIP -> {
                    listeners.forEach { it.onGameLog("Skip card played. Next player is skipped.") }
                    moveToNextPlayer() // Current player's turn ends, next player determined
                    // moveToNextPlayer() // Effectively skip that determined next player
                }
                Value.REVERSE -> {
                    listeners.forEach { it.onGameLog("Reverse card played. Direction of play reversed.") }
                    currentDirection *= -1
                    // if (players.size == 2) { // In a 2-player game, Reverse acts like Skip
                    //     moveToNextPlayer() // Current player's turn ends
                    //     // moveToNextPlayer() // Skip the other player (who is now current)
                    // } else {
                    //     moveToNextPlayer() // Current player's turn ends, new direction applies
                    // }
                }
                Value.DRAW_TWO -> {
                    listeners.forEach { it.onGameLog("Draw Two played. Next player draws two and is skipped.") }
                    // Target for draw is determined *after* current player's turn ends
                    // moveToNextPlayer() // Determine who is next
                    // val nextPlayerToDraw = getCurrentPlayer() // This is the player who will draw
                    // val drawn = drawPile.drawMultiple(2)
                    // ensureDeckHasCards(2)
                    // nextPlayerToDraw.addCardsToHand(drawn)
                    // listeners.forEach { l -> drawn.forEach { c -> l.onCardDrawn(nextPlayerToDraw, c) } }
                    // listeners.forEach { it.onHandUpdated(nextPlayerToDraw) }
                    // listeners.forEach { it.onGameLog("${nextPlayerToDraw.name} draws 2 cards.") }
                    // skipNextPlayerAfterDraw = true // This player (who drew) is skipped
                    handleDrawCardsForNextPlayer(2)
                }
                Value.WILD, Value.WILD_DRAW_FOUR -> {
                    if (newColorForWild == null || newColorForWild == Color.WILD) {
                        listeners.forEach { it.onGameLog("Error: Wild card $cardToPlay played by ${player.name} without choosing a color.") }
                        // This is an invalid state. Game should ideally wait for color choice.
                        // For now, if this happens, it's an error. Let's default to RED for robustness in model.
                        // UI must ensure color is provided.
                        chosenWildColor = Color.RED
                        listeners.forEach { it.onGameLog("Defaulted chosen wild color to RED.")}
                    } else {
                        chosenWildColor = newColorForWild
                    }
                    listeners.forEach { it.onWildColorChosen(chosenWildColor!!) }
                    listeners.forEach { it.onGameLog("Color chosen: $chosenWildColor") }

                    if (cardToPlay.value == Value.WILD_DRAW_FOUR) {
                        listeners.forEach { it.onGameLog("Wild Draw Four. Next player draws four and is skipped.") }
                        // moveToNextPlayer() // Determine who is next
                        // val nextPlayerToDrawWDF = getCurrentPlayer()
                        // val drawnWDF = drawPile.drawMultiple(4)
                        // ensureDeckHasCards(4)
                        // nextPlayerToDrawWDF.addCardsToHand(drawnWDF)
                        // listeners.forEach { l -> drawnWDF.forEach { c -> l.onCardDrawn(nextPlayerToDrawWDF, c) } }
                        // listeners.forEach { it.onHandUpdated(nextPlayerToDrawWDF) }
                        // listeners.forEach { it.onGameLog("${nextPlayerToDrawWDF.name} draws 4 cards.") }
                        // skipNextPlayerAfterDraw = true
                        handleDrawCardsForNextPlayer(4)
                    }
                }
                else -> { // Number cards or non-special action cards that don't skip/draw
                    // chosenWildColor = null; // If a colored number is played on a wild, the wild color effect ends. Already handled above.
                }
            }

            // Advance turn
            // If isSettingColorForStartingWild, the player doesn't get another turn immediately, turn passes.
            // If a card like Skip, Reverse, Draw_Two, Wild_Draw_Four was played, these cards dictate the next player.
            // The moveToNextPlayer() call handles the primary turn advancement.
            // Additional skips (like for Draw Two victim) are handled by calling moveToNextPlayer() again.

            // The core idea: playCard action completes for current player. Then, determine next player.
            // If the card played has an effect on "next player" (skip, draw), that effect applies to player who becomes current *after* this turn ends.

            // Revised turn logic:
            // 1. Current player plays.
            // 2. Effects of card are determined (e.g. who draws, who is skipped).
            // 3. Advance to the "logical" next player based on direction.
            // 4. If that "logical" next player is skipped (due to Skip card, or being victim of Draw Two/Four), advance again.

            val action = cardToPlay.value
            var selfSkipFromReverseInTwoPlayer = false
            if (action == Value.REVERSE && players.size == 2) {
                selfSkipFromReverseInTwoPlayer = true; // Reverse in 2P game means current player effectively skips opponent and plays again.
                                                        // So, move to next, then that player is "skipped" (which is current player again).
            }

            moveToNextPlayer() // Base turn advancement

            if (action == Value.SKIP || skipNextPlayerAfterDraw || selfSkipFromReverseInTwoPlayer) {
                listeners.forEach { it.onGameLog("${getCurrentPlayer().name}'s turn is skipped due to card effect.")}
                moveToNextPlayer() // Skip the player who just became current
            }

            listeners.forEach { it.onGameStateChanged() }
            return true
        } else {
            listeners.forEach { it.onGameLog("Invalid move: ${player.name} cannot play $cardToPlay on $topCard (Wild Color: $chosenWildColor).") }
            listeners.forEach { it.onInvalidPlay(player, cardToPlay) }
            return false
        }
    }

    private fun handleDrawCardsForNextPlayer(count: Int) {
        // This function assumes the current player has just finished their turn by playing a DrawX card.
        // The "next player" needs to be determined based on current direction *before* this player draws.
        // This is tricky. Let's adjust: the player whose turn it *would be* draws.

        var victimIndex = (currentPlayerIndex + currentDirection + players.size) % players.size
        val victimPlayer = players[victimIndex]

        listeners.forEach { it.onGameLog("${victimPlayer.name} must draw $count cards.")}
        val drawnCards = drawPile.drawMultiple(count)
        ensureDeckHasCards(count)
        victimPlayer.addCardsToHand(drawnCards)
        listeners.forEach { l -> drawnCards.forEach { c -> l.onCardDrawn(victimPlayer, c)}}
        listeners.forEach { it.onHandUpdated(victimPlayer) }
        // This player (victimPlayer) will be skipped. This is handled by the main playCard loop.
    }


    fun drawCard(playerId: String): Card? {
        if (isGameOver || playerId != getCurrentPlayer().id) {
            listeners.forEach { it.onGameLog("Cannot draw: Not player's $playerId turn or game over.")}
            return null
        }

        ensureDeckHasCards(1)
        if (drawPile.isEmpty()) {
            listeners.forEach { it.onGameLog("Draw pile is empty. Cannot draw.") }
            if (discardPile.size <= 1) {
                listeners.forEach { it.onGameLog("Not enough cards in discard pile to reshuffle.") }
                // Player cannot draw, turn should pass if no other move.
                // passTurn(playerId) // Or UI prompts for pass.
                // For now, let's assume passTurn is called by UI if draw fails and no play.
                return null
            }
            reshuffleDiscardPile()
            if(drawPile.isEmpty()){ // Still empty after reshuffle attempt (very rare)
                 listeners.forEach { it.onGameLog("Draw pile still empty after reshuffle. Cannot draw.")}
                 return null
            }
        }

        val player = getCurrentPlayer()
        val drawnCard = drawPile.draw()
        if (drawnCard != null) {
            player.addCardToHand(drawnCard)
            listeners.forEach { it.onCardDrawn(player, drawnCard) }
            listeners.forEach { it.onHandUpdated(player) }
            listeners.forEach { it.onGameLog("${player.name} drew $drawnCard") }

            val topCard = discardPile.peek() // Should not be null if game is running
            if (player.canPlayCard(drawnCard, topCard, chosenWildColor)) {
                listeners.forEach { it.onGameLog("${player.name} can play the drawn card: $drawnCard. Waiting for play or pass.") }
                // Game waits for player to either play this card (by calling playCard) or pass (by calling passTurn).
                // No automatic turn end here.
            } else {
                listeners.forEach { it.onGameLog("${player.name} drew $drawnCard and cannot play it. Turn ends.") }
                passTurn(playerId) // Automatically pass if drawn card is not playable.
            }
            listeners.forEach { it.onGameStateChanged() }
            return drawnCard
        }
        listeners.forEach { it.onGameStateChanged() } // Should not be reached if drawnCard is null and handled above
        return null
    }

    fun passTurn(playerId: String) {
        if (isGameOver || playerId != getCurrentPlayer().id) {
            listeners.forEach { it.onGameLog("Cannot pass: Not player's $playerId turn or game over.")}
            return
        }
        listeners.forEach { it.onGameLog("${getCurrentPlayer().name} passes their turn.") }
        moveToNextPlayer()
        listeners.forEach { it.onGameStateChanged() }
    }


    private fun moveToNextPlayer() {
        if (players.isEmpty()) {
             listeners.forEach { it.onGameLog("CRITICAL: moveToNextPlayer called with no players.")}
             isGameOver = true
             return
        }
        currentPlayerIndex = (currentPlayerIndex + currentDirection + players.size) % players.size

        // If the top card is NOT a wild card, any chosenWildColor effect is now void.
        // If top card IS wild, chosenWildColor remains in effect.
        // This is handled in playCard: chosenWildColor is nulled if a non-wild is played.
        // It's set if a wild is played.
        // So, chosenWildColor should persist if top card is wild.
        if (discardPile.isNotEmpty() && !discardPile.peek().isWildCard()) {
            if (chosenWildColor != null) {
                listeners.forEach { it.onGameLog("Resetting chosen wild color as top card is not wild.")}
                chosenWildColor = null
            }
        }

        listeners.forEach { it.onNextPlayerTurn(getCurrentPlayer()) }
        listeners.forEach { it.onTurnChanged(getCurrentPlayer().id) }
        listeners.forEach { it.onGameLog("It's now ${getCurrentPlayer().name}'s turn.") }
    }

    private fun ensureDeckHasCards(numNeeded: Int) {
        if (drawPile.size < numNeeded) {
            reshuffleDiscardPile()
        }
        if (drawPile.size < numNeeded && !isGameOver) {
            listeners.forEach { it.onDeckEmpty() }
            listeners.forEach { it.onGameLog("Warning: Draw pile is critically low or empty after reshuffle attempt.") }
            // Potentially game over if drawing is impossible and required.
            // For now, Game.drawCard handles immediate failure.
        }
    }

    private fun reshuffleDiscardPile() {
        listeners.forEach { it.onGameLog("Draw pile empty/low. Reshuffling discard pile.") }
        if (discardPile.size <= 1) {
            listeners.forEach { it.onGameLog("Not enough cards in discard pile to reshuffle.") }
            return
        }
        val topCard = discardPile.pop()
        val cardsToShuffle = mutableListOf<Card>()
        while(discardPile.isNotEmpty()) {
            cardsToShuffle.add(discardPile.pop())
        }
        drawPile.addCards(cardsToShuffle.filterNotNull()) // Ensure no nulls if any issue
        drawPile.shuffle()
        discardPile.push(topCard)
        listeners.forEach { it.onGameLog("Discard pile reshuffled into draw pile. Draw pile size: ${drawPile.size}") }
    }

    fun getPlayerState(playerId: String): Player? {
        return players.find { it.id == playerId }
    }

    fun getTopCard(): Card? {
        return if (discardPile.isNotEmpty()) discardPile.peek() else null
    }

    // This method might be used by UI if wild color choice is a separate step after playing Wild card.
    // However, playCard now takes newColorForWild, making it more atomic.
    // If called, it assumes the current player just played a Wild and needs to set its color.
    fun setChosenWildColorByPlayer(playerId: String, color: Color) {
        if (isGameOver || playerId != getCurrentPlayer().id) {
            listeners.forEach { it.onGameLog("Cannot set wild color: Not player's $playerId turn or game over.")}
            return
        }
        val topCard = discardPile.peek()
        if (topCard != null && topCard.isWildCard()) { // Color can only be chosen if top card is Wild
            if (color != Color.WILD) { // Cannot choose WILD as the color
                chosenWildColor = color
                listeners.forEach { it.onWildColorChosen(color) }
                listeners.forEach { it.onGameLog("${getCurrentPlayer().name} chose color $color for the Wild card.") }
                // After choosing color for a wild, the turn usually passes to the next player.
                // This depends on whether this function is called mid-playCard or after.
                // If playCard already handled turn logic based on wild type, this is just for confirmation.
                // If this is the *only* way to set wild color, then turn logic might follow here.
                // For now, assume playCard handles the full turn sequence.
                listeners.forEach { it.onGameStateChanged() }
            } else {
                listeners.forEach { it.onGameLog("Invalid color choice for Wild card (cannot choose WILD itself). Player $playerId, Color $color") }
            }
        } else {
            listeners.forEach { it.onGameLog("Cannot set wild color: Top card is not a Wild card, or pile is empty. Player $playerId") }
        }
    }
}
        fun onCardDrawn(player: Player, card: Card)
        fun onPlayerWon(player: Player)
        fun onWildColorChosen(color: Color)
        fun onUnoCalled(player: Player)
        fun onNextPlayerTurn(player: Player)
        fun onInvalidPlay(player: Player, card: Card)
        fun onDeckEmpty()
        fun onHandUpdated(player: Player)
        fun onGameLog(message: String)
    }

    val players: MutableList<Player> = mutableListOf()
    private val drawPile: Deck = Deck()
    val discardPile: Stack<Card> = Stack()

    var currentPlayerIndex: Int = 0
        private set
    var currentDirection: Int = 1 // 1 for forward, -1 for reverse
        private set
    var isGameOver: Boolean = false
        private set
    var chosenWildColor: Color? = null // For when a Wild card is played
        private set

    init {
        if (playerIdsAndNames.size < 2 || playerIdsAndNames.size > 4) {
            // For now, we'll log this. In a real app, this might throw an exception or be handled by UI.
            gameListener?.onGameLog("Error: Game must have 2 to 4 players.")
            // Potentially set isGameOver = true or handle this state
        }
        playerIdsAndNames.forEach { players.add(Player(it.first, it.second)) }
        startGame()
    }

    fun getCurrentPlayer(): Player = players[currentPlayerIndex]

    private fun startGame() {
        drawPile.reset()
        drawPile.shuffle()
        dealInitialCards()

        // Place the first card on the discard pile
        var firstCard = drawPile.draw()
        while (firstCard != null && firstCard.value == Value.WILD_DRAW_FOUR) {
            // A Wild Draw Four cannot be the first card. Reshuffle it into the deck.
            drawPile.addCard(firstCard)
            drawPile.shuffle()
            firstCard = drawPile.draw()
        }

        if (firstCard == null) {
            // Extremely unlikely scenario: deck runs out immediately.
            // This might happen if the deck somehow became empty after drawing initial hands.
            // Or if the deck only contained WILD_DRAW_FOUR cards.
            gameListener?.onGameLog("Error: Deck became empty during setup. Resetting deck.")
            // Attempt to reset and draw again, or handle as a critical error.
            // For simplicity here, we'll assume this doesn't lead to an infinite loop.
            drawPile.reset() // Ensure deck has cards
            drawPile.shuffle()
            // Redo dealing and first card logic if necessary, or signal game setup failure.
            // This part needs robust error handling in a production app.
            // For now, let's just try to draw another card.
            firstCard = drawPile.draw()
            while (firstCard != null && firstCard.value == Value.WILD_DRAW_FOUR) {
                 drawPile.addCard(firstCard)
                 drawPile.shuffle()
                 firstCard = drawPile.draw()
            }
            if (firstCard == null) {
                 gameListener?.onGameLog("Critical Error: Could not draw a valid starting card.")
                 isGameOver = true
                 return
            }
        }


        discardPile.push(firstCard)
        gameListener?.onGameLog("First card is: $firstCard")

        // Handle first card actions if it's a special card (Skip, Reverse, Draw Two)
        // Wild is handled separately as it requires color choice.
        if (firstCard.value == Value.SKIP) {
            gameListener?.onGameLog("First card is a Skip. ${players[currentPlayerIndex].name}'s turn is skipped.")
            moveToNextPlayer()
        } else if (firstCard.value == Value.REVERSE) {
            gameListener?.onGameLog("First card is a Reverse. Direction reversed.")
            currentDirection *= -1
            // In a 2-player game, Reverse acts like Skip.
            if (players.size == 2) {
                 moveToNextPlayer()
            } else {
                // With more than 2 players, the starting player changes.
                // The current player is 0. Reverse means previous player goes.
                // So, current player becomes last player.
                currentPlayerIndex = (players.size - 1 + players.size) % players.size
            }
        } else if (firstCard.value == Value.DRAW_TWO) {
            gameListener?.onGameLog("First card is a Draw Two. ${players[currentPlayerIndex].name} draws two cards and is skipped.")
            players[currentPlayerIndex].addCardsToHand(drawPile.drawMultiple(2))
            gameListener?.onHandUpdated(players[currentPlayerIndex])
            moveToNextPlayer()
        } else if (firstCard.color == Color.WILD) {
            // If the first card is a regular Wild, the first player chooses the color.
            // This would require input. For now, let's default or signal.
            // In a real game, you'd prompt the current player.
            gameListener?.onGameLog("${players[currentPlayerIndex].name} needs to choose a color for the Wild card.")
            // chosenWildColor will be set by player action.
        }

        gameListener?.onNextPlayerTurn(getCurrentPlayer())
        gameListener?.onTurnChanged(getCurrentPlayer().id)
    }

    private fun dealInitialCards() {
        players.forEach { player ->
            player.addCardsToHand(drawPile.drawMultiple(7))
            gameListener?.onHandUpdated(player)
        }
    }

    fun playCard(playerId: String, cardToPlay: Card, newColorForWild: Color? = null): Boolean {
        if (isGameOver || playerId != getCurrentPlayer().id) {
            gameListener?.onGameLog("Not player's turn or game is over.")
            return false
        }

        val player = getCurrentPlayer()
        val topCard = discardPile.peek()

        if (player.canPlayCard(cardToPlay, topCard, chosenWildColor)) {
            if (!player.hand.contains(cardToPlay)) {
                 gameListener?.onGameLog("Player ${player.name} does not have card ${cardToPlay}.")
                 gameListener?.onInvalidPlay(player, cardToPlay)
                 return false
            }

            player.playCard(cardToPlay)
            discardPile.push(cardToPlay)
            gameListener?.onCardPlayed(player, cardToPlay)
            gameListener?.onHandUpdated(player)
            gameListener?.onGameLog("${player.name} played $cardToPlay")

            chosenWildColor = null // Reset chosen wild color

            if (player.hasWon()) {
                isGameOver = true
                gameListener?.onPlayerWon(player)
                gameListener?.onGameLog("${player.name} has won!")
                return true
            }

            if (player.hasUno()) {
                gameListener?.onUnoCalled(player)
                gameListener?.onGameLog("${player.name} shouts UNO!")
            }

            // Handle card actions
            when (cardToPlay.value) {
                Value.SKIP -> {
                    gameListener?.onGameLog("Skip card played. Next player is skipped.")
                    moveToNextPlayer() // Skip one player
                    moveToNextPlayer() // Move to the actual next player
                }
                Value.REVERSE -> {
                    gameListener?.onGameLog("Reverse card played. Direction of play reversed.")
                    currentDirection *= -1
                    if (players.size == 2) { // In a 2-player game, Reverse acts like Skip
                        moveToNextPlayer()
                        moveToNextPlayer()
                    } else {
                        moveToNextPlayer()
                    }
                }
                Value.DRAW_TWO -> {
                    gameListener?.onGameLog("Draw Two played. Next player draws two and is skipped.")
                    moveToNextPlayer() // Target the next player
                    val nextPlayer = getCurrentPlayer()
                    val drawn = drawPile.drawMultiple(2)
                    ensureDeckHasCards(2)
                    nextPlayer.addCardsToHand(drawn)
                    gameListener?.onHandUpdated(nextPlayer)
                    drawn.forEach { gameListener?.onCardDrawn(nextPlayer, it) }
                    gameListener?.onGameLog("${nextPlayer.name} draws 2 cards.")
                    moveToNextPlayer() // Skip the player who drew
                }
                Value.WILD -> {
                    if (newColorForWild == null || newColorForWild == Color.WILD) {
                        gameListener?.onGameLog("Error: Wild card played without choosing a color or invalid color chosen.")
                        // This should ideally be prevented by UI forcing a choice.
                        // For now, revert or ask for color. Let's assume UI handles this.
                        // If not handled, this could be an invalid state.
                        // Re-adding card to hand and asking for proper play might be an option.
                        // For now, we'll log and let the turn pass to next player.
                        // This is a simplification.
                        gameListener?.onInvalidPlay(player, cardToPlay) // Or a specific "WildColorNotChosen" event
                        // player.addCardToHand(cardToPlay) // Give card back
                        // discardPile.pop() // Remove wild from discard
                        // gameListener?.onHandUpdated(player)
                        // return false // Indicate invalid play
                        chosenWildColor = Color.RED // Default if not provided, for now
                        gameListener?.onGameLog("No color chosen for WILD. Defaulting to RED.")

                    } else {
                        chosenWildColor = newColorForWild
                    }
                    gameListener?.onWildColorChosen(chosenWildColor!!)
                    gameListener?.onGameLog("Color chosen: $chosenWildColor")
                    moveToNextPlayer()
                }
                Value.WILD_DRAW_FOUR -> {
                     if (newColorForWild == null || newColorForWild == Color.WILD) {
                        gameListener?.onGameLog("Error: Wild Draw Four played without choosing a color.")
                        // Similar to WILD, this needs proper handling.
                        // player.addCardToHand(cardToPlay)
                        // discardPile.pop()
                        // gameListener?.onHandUpdated(player)
                        // gameListener?.onInvalidPlay(player, cardToPlay)
                        // return false
                        chosenWildColor = Color.BLUE // Default if not provided
                        gameListener?.onGameLog("No color chosen for WILD_DRAW_FOUR. Defaulting to BLUE.")
                    } else {
                        chosenWildColor = newColorForWild
                    }
                    gameListener?.onWildColorChosen(chosenWildColor!!)
                    gameListener?.onGameLog("Color chosen: $chosenWildColor")

                    moveToNextPlayer() // Target the next player
                    val nextPlayer = getCurrentPlayer()
                    val drawn = drawPile.drawMultiple(4)
                    ensureDeckHasCards(4)
                    nextPlayer.addCardsToHand(drawn)
                    gameListener?.onHandUpdated(nextPlayer)
                    drawn.forEach { gameListener?.onCardDrawn(nextPlayer, it) }
                    gameListener?.onGameLog("${nextPlayer.name} draws 4 cards.")
                    moveToNextPlayer() // Skip the player who drew
                }
                else -> { // Number cards or non-special action cards
                    moveToNextPlayer()
                }
            }
            return true
        } else {
            gameListener?.onGameLog("Invalid move: ${player.name} cannot play $cardToPlay on $topCard (Wild Color: $chosenWildColor).")
            gameListener?.onInvalidPlay(player, cardToPlay)
            return false
        }
    }

    fun drawCard(playerId: String): Card? {
        if (isGameOver || playerId != getCurrentPlayer().id) return null

        ensureDeckHasCards(1)
        if (drawPile.isEmpty()) {
            gameListener?.onGameLog("Draw pile is empty. Cannot draw.")
            // Check if reshuffle is possible
             if (discardPile.size <= 1) { // Cannot reshuffle if only one or no cards in discard
                gameListener?.onGameLog("Not enough cards in discard pile to reshuffle.")
                // This could be a stalemate or game end condition if no one can play.
                // For now, turn passes if player cannot play the drawn card (if any).
                // Or if no card can be drawn.
                // Consider game rules for this scenario.
                // Let's assume for now the turn passes if no card can be drawn and no play is made.
                moveToNextPlayer()
                return null
            }
            reshuffleDiscardPile()
        }


        val player = getCurrentPlayer()
        val drawnCard = drawPile.draw()
        if (drawnCard != null) {
            player.addCardToHand(drawnCard)
            gameListener?.onCardDrawn(player, drawnCard)
            gameListener?.onHandUpdated(player)
            gameListener?.onGameLog("${player.name} drew $drawnCard")

            // According to official rules, if the drawn card is playable, the player can play it immediately.
            // Otherwise, their turn ends.
            val topCard = discardPile.peek()
            if (player.canPlayCard(drawnCard, topCard, chosenWildColor)) {
                gameListener?.onGameLog("${player.name} can play the drawn card: $drawnCard. Waiting for play or pass.")
                // The game should now wait for the player to either play this card or pass the turn.
                // This requires more complex state management or player input handling.
                // For this version, let's assume the player *must* play if possible, or UI handles choice.
                // If we don't auto-play, then we need a "passTurn" function.
                // For now, we'll just end the turn after drawing, simplifying the flow.
                 // playCard(playerId, drawnCard, if (drawnCard.isWildCard()) chosenWildColor else null)
                 // If we don't auto-play, then turn moves to next player.
                 // Let's assume for now: draw and then turn ends. Player can play it on their next turn or if UI allows immediate play.
                 // Official rule: "If the card you picked up is playable, you may play it. Otherwise, your turn ends."
                 // This implies a choice. For simplicity in this model, we will not auto-play.
                 // The player will have to wait for their next turn or we need a "playDrawnCard" action.
                 // Let's keep it simple: draw ends turn unless UI implements immediate play.
                 // The current `playCard` handles the next turn logic.
                 // So if player wants to play the drawn card, they call playCard.
                 // If they don't (or can't), their turn should end.
                 // The current simplified model: draw, if not playable, turn ends.
                 // If playable, player *could* play it.
                 // Let's assume player draws, and if they don't play, their turn moves on.
                 // This needs refinement for proper interactive flow.
                 // For now, drawing a card and not playing it means the turn moves.
                 // We will not automatically end the turn here. The player can choose to play the drawn card or another card.
                 // If they can't play any card (even the drawn one), they would effectively pass.
                 // A dedicated "passTurn" might be needed if player draws and cannot/chooses not to play.
            } else {
                 gameListener?.onGameLog("${player.name} drew $drawnCard and cannot play it. Turn ends.")
                 moveToNextPlayer()
            }
            return drawnCard
        }
        return null
    }

    // Call this after a player draws a card and cannot or chooses not to play it.
    fun passTurn(playerId: String) {
        if (isGameOver || playerId != getCurrentPlayer().id) return
        gameListener?.onGameLog("${getCurrentPlayer().name} passes their turn.")
        moveToNextPlayer()
    }


    private fun moveToNextPlayer() {
        currentPlayerIndex = (currentPlayerIndex + currentDirection + players.size) % players.size
        chosenWildColor = null // Reset chosen wild color if the previous card was a lasting Wild
        // If the top card of the discard pile is a Wild card that has had its color chosen,
        // that color choice should persist until a non-Wild card is played on top of it or another Wild is played.
        // However, our current `chosenWildColor` is reset. Let's check top card.
        val topCard = discardPile.peek()
        if (topCard.color == Color.WILD && chosenWildColor == null) {
            // This state should ideally not be reached if a wild card was played and its color was set.
            // This implies the chosenWildColor should persist if the top card is still that Wild card.
            // Let's re-think: chosenWildColor is associated with the EFFECT of the wild card on the discard pile.
            // It should only be reset when a card is played ON TOP of the wild card, or another wild is played.
            // So, `moveToNextPlayer` should not blindly reset `chosenWildColor`.
            // `playCard` should reset it *before* setting a new one if the played card is Wild.
            // And it should be reset if a non-wild card is played on a wild.
            // The current logic in `playCard` resets it at the start of processing a played card.
            // Let's refine: `chosenWildColor` is the active color if the top discard is Wild.
        }
        if (discardPile.peek().color != Color.WILD) {
            chosenWildColor = null // Clear chosen color if top card is not Wild
        }


        gameListener?.onNextPlayerTurn(getCurrentPlayer())
        gameListener?.onTurnChanged(getCurrentPlayer().id)
        gameListener?.onGameLog("It's now ${getCurrentPlayer().name}'s turn.")
    }

    private fun ensureDeckHasCards(numNeeded: Int) {
        if (drawPile.size < numNeeded) {
            reshuffleDiscardPile()
        }
        if (drawPile.size < numNeeded) {
            // Still not enough cards, could be a rare game state.
            gameListener?.onDeckEmpty() // Signal that the deck is critically low.
            gameListener?.onGameLog("Warning: Draw pile is very low or empty after reshuffle attempt.")
        }
    }

    private fun reshuffleDiscardPile() {
        gameListener?.onGameLog("Draw pile empty. Reshuffling discard pile.")
        if (discardPile.size <= 1) { // Need at least one card to keep on discard, rest to shuffle
            gameListener?.onGameLog("Not enough cards in discard pile to reshuffle.")
            return // Cannot reshuffle
        }
        val topCard = discardPile.pop() // Keep the current top card
        val cardsToShuffle = mutableListOf<Card>()
        while(discardPile.isNotEmpty()) {
            cardsToShuffle.add(discardPile.pop())
        }
        drawPile.addCards(cardsToShuffle)
        drawPile.shuffle()
        discardPile.push(topCard) // Put the top card back
        gameListener?.onGameLog("Discard pile reshuffled into draw pile. Draw pile size: ${drawPile.size}")
    }

    fun getPlayerState(playerId: String): Player? {
        return players.find { it.id == playerId }
    }

    fun getTopCard(): Card? {
        return if (discardPile.isNotEmpty()) discardPile.peek() else null
    }

    // This is for when a player plays a Wild card and needs to specify the color.
    // This method might be called by the UI after a Wild is played.
    // The playCard method was updated to take newColorForWild.
    // This explicit function might still be useful if the choice is decoupled.
    fun setWildCardColor(playerId: String, color: Color) {
        if (isGameOver || playerId != getCurrentPlayer().id) {
            gameListener?.onGameLog("Cannot set wild color: Not player's turn or game over.")
            return
        }
        val topCard = discardPile.peek()
        if (topCard.isWildCard()) {
            if (color != Color.WILD) {
                chosenWildColor = color
                gameListener?.onWildColorChosen(color)
                gameListener?.onGameLog("${getCurrentPlayer().name} chose color $color for the Wild card.")
                // Note: The turn progression should have already happened or will happen as part of playCard.
                // This function is purely for setting the color if it's a separate step.
            } else {
                gameListener?.onGameLog("Invalid color choice for Wild card (cannot choose WILD itself).")
            }
        } else {
            gameListener?.onGameLog("Cannot set wild color: Top card is not a Wild card.")
        }
    }
}
