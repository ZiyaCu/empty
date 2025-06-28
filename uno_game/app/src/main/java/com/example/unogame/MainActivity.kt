package com.example.unogame

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.unogame.adapters.CardAdapter
import com.example.unogame.models.Card
import com.example.unogame.models.Color as UnoColor
import com.example.unogame.models.Game
import com.example.unogame.models.GameListener // Interface
import com.example.unogame.models.Player
import com.example.unogame.network.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.util.UUID

class MainActivity : AppCompatActivity(), GameListener {

    private lateinit var game: Game // Main game logic instance
    private var localPlayerId: String = UUID.randomUUID().toString() // Unique ID for this app instance/player
    private var localPlayerName: String = "Player" // Default name, can be changed

    // UI Elements
    private lateinit var buttonHostGame: Button
    private lateinit var buttonDiscoverGames: Button
    private lateinit var buttonDisconnect: Button
    private lateinit var textViewConnectionStatus: TextView

    private lateinit var textViewOpponent1Name: TextView
    private lateinit var textViewOpponent2Name: TextView
    private lateinit var textViewOpponent3Name: TextView
    private lateinit var imageViewDrawPile: ImageView
    private lateinit var imageViewDiscardPile: ImageView
    private lateinit var textViewCurrentTurnInfo: TextView
    private lateinit var textViewChosenWildColor: TextView
    private lateinit var recyclerViewPlayerHand: RecyclerView
    private lateinit var buttonDrawCard: Button
    private lateinit var buttonUno: Button // Should be contextually visible
    private lateinit var buttonPassTurn: Button // Added pass turn button
    private lateinit var linearLayoutWildColorPicker: LinearLayout
    private lateinit var buttonPickRed: Button
    private lateinit var buttonPickGreen: Button
    private lateinit var buttonPickBlue: Button
    private lateinit var buttonPickYellow: Button
    private lateinit var textViewGameLog: TextView

    private lateinit var cardAdapter: CardAdapter

    // Networking components
    private lateinit var nsdHelper: NsdHelper
    private var gameHost: UnoGameHost? = null
    private var gameClient: UnoGameClient? = null

    private var isHost: Boolean = false
    private val discoveredGames = mutableListOf<GameServiceInfo>()
    private var serviceName: String = "UnoGame" // Default, can be user's name + game

    private val PERMISSION_REQUEST_CODE = 1001
    private val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION) // For Wi-Fi state access on older Android
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Get unique player ID, could be stored in SharedPreferences for persistence
        val prefs = getSharedPreferences("UnoPlayer", MODE_PRIVATE)
        localPlayerId = prefs.getString("playerId", localPlayerId) ?: localPlayerId
        prefs.edit().putString("playerId", localPlayerId).apply()
        localPlayerName = prefs.getString("playerName", "Player${localPlayerId.substring(0,4)}") ?: "Player${localPlayerId.substring(0,4)}"


        initializeUI()
        setupInitialPlayerName() // Prompt for player name
        // Game setup is deferred until hosting or joining

        nsdHelper = NsdHelper(this)
        checkAndRequestPermissions()
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                Log.i("Permissions", "Required permissions granted.")
                // You can now initialize network features that depend on these permissions
            } else {
                Log.w("Permissions", "Some required permissions were denied.")
                Toast.makeText(this, "Permissions required for network features were denied.", Toast.LENGTH_LONG).show()
                // Disable network buttons or inform user
                buttonHostGame.isEnabled = false
                buttonDiscoverGames.isEnabled = false
            }
        }
    }


    private fun initializeUI() {
        buttonHostGame = findViewById(R.id.buttonHostGame)
        buttonDiscoverGames = findViewById(R.id.buttonDiscoverGames)
        buttonDisconnect = findViewById(R.id.buttonDisconnect)
        textViewConnectionStatus = findViewById(R.id.textViewConnectionStatus)

        textViewOpponent1Name = findViewById(R.id.textViewOpponent1Name)
        textViewOpponent2Name = findViewById(R.id.textViewOpponent2Name)
        textViewOpponent3Name = findViewById(R.id.textViewOpponent3Name)
        // Hide opponents initially
        textViewOpponent1Name.visibility = View.GONE
        textViewOpponent2Name.visibility = View.GONE
        textViewOpponent3Name.visibility = View.GONE


        imageViewDrawPile = findViewById(R.id.imageViewDrawPile)
        imageViewDiscardPile = findViewById(R.id.imageViewDiscardPile)
        textViewCurrentTurnInfo = findViewById(R.id.textViewCurrentTurnInfo)
        textViewChosenWildColor = findViewById(R.id.textViewChosenWildColor)
        recyclerViewPlayerHand = findViewById(R.id.recyclerViewPlayerHand)
        buttonDrawCard = findViewById(R.id.buttonDrawCard)
        buttonUno = findViewById(R.id.buttonUno)
        buttonPassTurn = findViewById(R.id.buttonPassTurn) // Initialize this new button
        linearLayoutWildColorPicker = findViewById(R.id.linearLayoutWildColorPicker)
        buttonPickRed = findViewById(R.id.buttonPickRed)
        buttonPickGreen = findViewById(R.id.buttonPickGreen)
        buttonPickBlue = findViewById(R.id.buttonPickBlue)
        buttonPickYellow = findViewById(R.id.buttonPickYellow)
        textViewGameLog = findViewById(R.id.textViewGameLog)
        textViewGameLog.movementMethod = ScrollingMovementMethod()

        cardAdapter = CardAdapter(emptyList()) { selectedCard -> handleCardClick(selectedCard) }
        recyclerViewPlayerHand.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        recyclerViewPlayerHand.adapter = cardAdapter

        setupClickListeners()
        setGameControlsEnabled(false) // Disable game controls until game starts
    }

    private fun setupInitialPlayerName() {
        val editText = EditText(this).apply { setText(localPlayerName) }
        AlertDialog.Builder(this)
            .setTitle("Enter Your Name")
            .setView(editText)
            .setPositiveButton("Save") { dialog, _ ->
                val name = editText.text.toString().trim()
                if (name.isNotEmpty()) {
                    localPlayerName = name
                    serviceName = "$localPlayerName's Uno Game"
                    getSharedPreferences("UnoPlayer", MODE_PRIVATE).edit().putString("playerName", localPlayerName).apply()
                    logToScreen("Player name set to: $localPlayerName")
                }
                dialog.dismiss()
            }
            .setCancelable(false)
            .show()
    }


    private fun setupClickListeners() {
        buttonHostGame.setOnClickListener { if (!isHost && gameClient == null) startHostingGame() }
        buttonDiscoverGames.setOnClickListener { if (!isHost && gameClient == null) discoverGames() }
        buttonDisconnect.setOnClickListener { disconnectFromGame() }

        imageViewDrawPile.setOnClickListener { handleDrawCardClick() }
        buttonDrawCard.setOnClickListener { handleDrawCardClick() }
        buttonPassTurn.setOnClickListener { handlePassTurnClick() }

        buttonUno.setOnClickListener {
            // This button's primary role could be to penalize others for not calling Uno,
            // or for the player to call Uno if auto-detection isn't desired.
            // For now, it's a manual Uno call.
            if (::game.isInitialized && game.getPlayerState(localPlayerId)?.hasUno() == true) {
                logToScreen("You declared UNO!")
                // Network message if explicit call needed by rules, e.g. GameMessage.PlayerActionDeclareUno
                gameClient?.sendMessage(GameMessage.PlayerActionDeclareUno(localPlayerId))
                // Host directly calls game logic or its listener
                if (isHost) game.onUnoCalled(game.getPlayerState(localPlayerId)!!) // Example direct call
                buttonUno.visibility = View.GONE
            } else {
                logToScreen("Cannot call UNO right now.")
            }
        }

        buttonPickRed.setOnClickListener { completeWildCardPlay(UnoColor.RED) }
        buttonPickGreen.setOnClickListener { completeWildCardPlay(UnoColor.GREEN) }
        buttonPickBlue.setOnClickListener { completeWildCardPlay(UnoColor.BLUE) }
        buttonPickYellow.setOnClickListener { completeWildCardPlay(UnoColor.YELLOW) }
    }

    private fun handleCardClick(selectedCard: Card) {
        if (!::game.isInitialized || game.getCurrentPlayer().id != localPlayerId || game.isGameOver) {
            logToScreen("Cannot play: Not your turn or game is over.")
            return
        }

        if (selectedCard.isWildCard()) {
            promptForWildColor(selectedCard)
        } else {
            if (isHost) {
                game.playCard(localPlayerId, selectedCard.id, null)
            } else {
                gameClient?.sendMessage(GameMessage.PlayerActionPlayCard(localPlayerId, selectedCard.id, null))
            }
        }
    }

    private fun handleDrawCardClick() {
        if (!::game.isInitialized || game.getCurrentPlayer().id != localPlayerId || game.isGameOver) {
            logToScreen("Cannot draw: Not your turn or game is over.")
            return
        }
        if (isHost) {
            game.drawCard(localPlayerId)
        } else {
            gameClient?.sendMessage(GameMessage.PlayerActionDrawCard(localPlayerId))
        }
    }

    private fun handlePassTurnClick() {
        if (!::game.isInitialized || game.getCurrentPlayer().id != localPlayerId || game.isGameOver) {
            logToScreen("Cannot pass: Not your turn or game is over.")
            return
        }
        if (isHost) {
            game.passTurn(localPlayerId)
        } else {
            gameClient?.sendMessage(GameMessage.PlayerActionPassTurn(localPlayerId))
        }
    }

    private var pendingWildCard: Card? = null
    private fun promptForWildColor(wildCard: Card) {
        pendingWildCard = wildCard
        linearLayoutWildColorPicker.visibility = View.VISIBLE
        setGameControlsEnabled(false, enableWildPicker = false) // Disable other game actions
    }

    private fun completeWildCardPlay(chosenColor: UnoColor) {
        linearLayoutWildColorPicker.visibility = View.GONE
        setGameControlsEnabled(true) // Re-enable game controls based on turn

        pendingWildCard?.let { wildCard ->
            if (isHost) {
                game.playCard(localPlayerId, wildCard.id, chosenColor)
            } else {
                gameClient?.sendMessage(GameMessage.PlayerActionPlayCard(localPlayerId, wildCard.id, chosenColor))
            }
        }
        pendingWildCard = null
    }

    // --- Networking Logic ---
    private fun startHostingGame() {
        isHost = true
        buttonHostGame.isEnabled = false
        buttonDiscoverGames.isEnabled = false
        buttonDisconnect.visibility = View.VISIBLE
        textViewConnectionStatus.text = "Status: Starting Host..."

        // Game instance for the host
        Card.resetNextId() // Reset card ID generation for a new game
        game = Game(hostPlayerId = localPlayerId, hostPlayerName = localPlayerName) // GameHost will be listener

        gameHost = UnoGameHost(
            onHostEvent = { event -> runOnUiThread { logToScreen("Host: $event") } },
            onClientConnected = { clientDto -> runOnUiThread {
                logToScreen("Host: Client ${clientDto.name} connected.")
                // Host's game logic adds this player
                game.addPlayer(clientDto.id, clientDto.name)
                // If enough players, host can start the game
                if (game.players.size >= 2 && game.discardPile.isEmpty()) { // Example: start with 2 players
                    game.prepareAndStartGame()
                }
            }},
            onClientDisconnected = { clientId, reason -> runOnUiThread {
                logToScreen("Host: Client $clientId disconnected: $reason")
                game.removePlayer(clientId)
            }},
            onGameActionByHost = { gameMessage -> /* Host UI might react to its own actions if needed */ }
        )
        game.addGameListener(this) // MainActivity also listens for local UI updates
        game.addGameListener(gameHost!!) // GameHost listens to broadcast

        // Start hosting on a specific port (0 means choose any available)
        gameHost!!.startHosting(0, game, localPlayerName) { success ->
            if (success) {
                val actualPort = gameHost!!.serverSocket?.localPort ?: 0 // Need accessor in GameHost
                if (actualPort > 0) {
                    runOnUiThread { textViewConnectionStatus.text = "Status: Hosting on port $actualPort. Broadcasting..." }
                    nsdHelper.startBroadcastingGame(serviceName, actualPort) { registered, name ->
                        runOnUiThread {
                            if (registered) {
                                textViewConnectionStatus.text = "Status: Hosting & Broadcasting as '$name'"
                                logToScreen("Broadcasting game: $name")
                                setGameControlsEnabled(true) // Host can play once game starts
                            } else {
                                textViewConnectionStatus.text = "Status: Hosting, Broadcast failed."
                                logToScreen("Failed to broadcast game.")
                            }
                        }
                    }
                } else {
                     runOnUiThread {
                        textViewConnectionStatus.text = "Status: Host started but port unknown."
                        logToScreen("Host started but failed to get port.")
                        stopHostingGame() // Clean up
                     }
                }
            } else {
                runOnUiThread {
                    textViewConnectionStatus.text = "Status: Failed to start host."
                    logToScreen("Failed to start game host.")
                    stopHostingGame() // Clean up
                }
            }
        }
         updateUI() // Initial UI for host
    }

    private fun stopHostingGame() {
        isHost = false
        nsdHelper.stopBroadcastingGame()
        gameHost?.stopHosting()
        gameHost = null
        buttonHostGame.isEnabled = true
        buttonDiscoverGames.isEnabled = true
        buttonDisconnect.visibility = View.GONE
        textViewConnectionStatus.text = "Status: Idle"
        logToScreen("Hosting stopped.")
        setGameControlsEnabled(false)
        // Reset game UI elements if needed
        cardAdapter.updateCards(emptyList())
        imageViewDiscardPile.setImageResource(R.drawable.ic_card_placeholder)
    }

    private fun discoverGames() {
        buttonHostGame.isEnabled = false
        buttonDiscoverGames.isEnabled = false
        buttonDisconnect.visibility = View.VISIBLE
        textViewConnectionStatus.text = "Status: Discovering games..."
        discoveredGames.clear()

        nsdHelper.startDiscoveringGames(
            onGameFound = { gameInfo -> runOnUiThread {
                Log.d("MainActivity", "Discovered: ${gameInfo.serviceName} at ${gameInfo.hostAddress}:${gameInfo.port}")
                if (!discoveredGames.any { it.serviceName == gameInfo.serviceName }) {
                    discoveredGames.add(gameInfo)
                    // For now, auto-connect to first found for simplicity in this example
                    // In a real app, show a list
                    if (gameClient == null && discoveredGames.size == 1 && gameInfo.hostAddress != null) {
                        // Connect to the first valid game found
                        connectToGame(gameInfo)
                    } else if (gameInfo.hostAddress == null) {
                        logToScreen("Found game ${gameInfo.serviceName} but address is missing.")
                    }
                }
            }},
            onGameLost = { gameInfo -> runOnUiThread {
                discoveredGames.removeAll { it.serviceName == gameInfo.serviceName }
                logToScreen("Lost game: ${gameInfo.serviceName}")
            }},
            onDiscoveryFailed = { errorCode -> runOnUiThread {
                textViewConnectionStatus.text = "Status: Discovery failed ($errorCode)."
                buttonHostGame.isEnabled = true
                buttonDiscoverGames.isEnabled = true
                buttonDisconnect.visibility = View.GONE
            }}
        )
    }

    private fun connectToGame(serviceInfo: GameServiceInfo) {
        if (serviceInfo.hostAddress == null || serviceInfo.port <= 0) {
            logToScreen("Cannot connect: Invalid game info for ${serviceInfo.serviceName}")
            return
        }
        nsdHelper.stopDiscoveringGames() // Stop discovery once connecting
        textViewConnectionStatus.text = "Status: Connecting to ${serviceInfo.serviceName}..."

        gameClient = UnoGameClient(
            onMessageReceived = { message -> CoroutineScope(Dispatchers.Main).launch { handleNetworkMessage(message) } },
            onConnectionStatusChanged = { connected, message, assignedId -> runOnUiThread {
                textViewConnectionStatus.text = "Status: $message"
                if (connected) {
                    localPlayerId = assignedId ?: localPlayerId // Use ID assigned by host
                    logToScreen("Connected to host. My player ID: $localPlayerId")
                    buttonDisconnect.visibility = View.VISIBLE
                    buttonHostGame.isEnabled = false
                    buttonDiscoverGames.isEnabled = false
                    setGameControlsEnabled(true) // Enable game play for client
                } else {
                    logToScreen("Connection failed or lost: $message")
                    disconnectFromGame(false) // Clean up client, don't try to notify host if connection failed
                }
            }}
        )
        // Send my localPlayerName and current localPlayerId (host might reassign)
        gameClient!!.connect(serviceInfo.hostAddress!!, serviceInfo.port, localPlayerName, localPlayerId)
    }

    private fun disconnectFromGame(notifyHost: Boolean = true) {
        if (isHost) {
            stopHostingGame()
        } else {
            gameClient?.disconnect(notifyHost) // Pass true to attempt sending disconnect msg
            gameClient = null
            buttonHostGame.isEnabled = true
            buttonDiscoverGames.isEnabled = true
            buttonDisconnect.visibility = View.GONE
            textViewConnectionStatus.text = "Status: Idle"
            logToScreen("Disconnected.")
            setGameControlsEnabled(false)
            cardAdapter.updateCards(emptyList())
            imageViewDiscardPile.setImageResource(R.drawable.ic_card_placeholder)
        }
        // Clear game state related UI too
        textViewOpponent1Name.visibility = View.GONE
        textViewOpponent2Name.visibility = View.GONE
        textViewOpponent3Name.visibility = View.GONE
        textViewCurrentTurnInfo.text = "Turn: -"
        textViewGameLog.text = "Disconnected."
        // Game instance should be reset or nulled
        // if (::game.isInitialized) { /* Potentially nullify or reset game instance */ }
    }


    private fun handleNetworkMessage(message: GameMessage) {
        // Ensure game is initialized, especially for clients who receive GameStateUpdate first
        if (!::game.isInitialized && message is GameMessage.ConnectionResponse && message.initialGameState != null) {
            // Client is receiving initial game state. Create a local Game instance.
            // This local Game instance for a client is primarily a data holder. Logic is driven by host.
            // The list of players in initialGameState.players will be DTOs.
            // We need to create actual Player objects for the local Game instance.
            // This is a simplification; client-side game model might be simpler.
            val initialPlayers = message.initialGameState.players.map { dto -> Pair(dto.id, dto.name) }
            if (initialPlayers.isNotEmpty()) {
                Card.resetNextId() // Important for client if it reconstructs cards based on IDs from host
                game = Game(initialPlayers, this) // Client's MainActivity also listens
                game.isGameOver = message.initialGameState.isGameOver // Apply initial game over state
                // Client's game logic doesn't run startGame(), state is set by host.
                // Update client's game model based on initialGameState
                updateLocalGameFromState(message.initialGameState)
            } else {
                logToScreen("Error: Initial game state from host has no players.")
                return
            }
        } else if (!::game.isInitialized && message !is GameMessage.ConnectionResponse) {
            logToScreen("Error: Game not initialized but received message: ${message::class.simpleName}")
            return
        }


        when (message) {
            is GameMessage.GameStateUpdate -> {
                updateLocalGameFromState(message)
            }
            is GameMessage.PlayerJoined -> { // Host sends this
                logToScreen("Player ${message.newPlayer.name} joined.")
                if (isHost) { // Host already added player to gameLogic, just update UI
                     // game.addPlayer(message.newPlayer.id, message.newPlayer.name) //This is done by host logic now
                } else { // Client updates its list of players
                    game.players.clear()
                    message.allPlayers.forEach{ dto -> game.players.add(Player(dto.id, dto.name, false))}
                }
                updateUI()
            }
            is GameMessage.PlayerLeft -> { // Host sends this
                logToScreen("Player ${message.playerId} left.")
                 if (isHost) {
                    // game.removePlayer(message.playerId) // Done by host logic
                 } else {
                    game.players.clear()
                    message.allPlayers.forEach{ dto -> game.players.add(Player(dto.id, dto.name, false))}
                 }
                updateUI()
            }
            is GameMessage.ChatMessage -> {
                logToScreen("${message.senderName}: ${message.message}")
            }
            is GameMessage.ErrorMessage -> {
                logToScreen("Error from host: ${message.errorText}")
                if (message.isCritical) {
                    Toast.makeText(this, "Critical error from host: ${message.errorText}", Toast.LENGTH_LONG).show()
                    disconnectFromGame(false)
                }
            }
            // Other message types (like ConnectionResponse) are handled in connect/host logic
            else -> {
                // logToScreen("Received unhandled message type: ${message::class.simpleName}")
            }
        }
    }

    private fun updateLocalGameFromState(state: GameMessage.GameStateUpdate) {
        if (!::game.isInitialized) return // Should be initialized by now for client

        // This is crucial for clients. Host's game is the source of truth.
        // Client's local 'game' object is updated to reflect this state.

        // Update players (name, handsize). Be careful about full replacement vs update.
        // For simplicity, replace if list differs significantly, otherwise update.
        // This needs robust synchronization of player list.
        game.players.clear() // Simplest: clear and re-add from DTOs
        state.players.forEach { dto ->
            val player = Player(dto.id, dto.name)
            // Client doesn't know actual hand cards, only size, unless host sends full hand for local player
            // For now, client's Player objects won't have actual cards, only hand size for UI.
            // The local player's hand IS managed locally by cardAdapter from their specific hand updates.
            if (dto.id != localPlayerId) { // For opponents, just update hand size for display
                 // We need a way to set hand size on Player model if it doesn't hold cards
            }
            game.players.add(player)
        }

        game.chosenWildColor = state.chosenWildColor
        game.currentDirection = state.gameDirection
        game.isGameOver = state.isGameOver

        // Find current player index based on ID
        val newCurrentPlayerIndex = game.players.indexOfFirst { it.id == state.currentPlayerId }
        if (newCurrentPlayerIndex != -1) {
            // game.currentPlayerIndex = newCurrentPlayerIndex // Game.currentPlayerIndex is private
            // This means Game.kt needs a way to set current player, or this is just for UI update.
            // For client, this info is for UI. Game logic is on host.
        } else if (!game.isGameOver) {
            logToScreen("Error: Current player ID ${state.currentPlayerId} not found in local player list.")
        }

        // Update discard pile (only top card for now for client)
        game.discardPile.clear()
        state.discardPileTopCard?.let { game.discardPile.push(it) }

        // Update draw pile size (for UI indication)
        // game.drawPile.size = state.drawPileSize // DrawPile needs a way to set size or cards

        // If this is the local player, update their actual hand from a more specific message if needed,
        // or if GameStateUpdate contained it. For now, GameStateUpdate is high-level.
        // The local player's hand is updated via GameListener.onHandUpdated for the host,
        // and for the client, if the host sends the client's specific hand.
        // This current GameStateUpdate doesn't send individual hands.

        state.gameLogUpdate?.let { logToScreen(it) }
        updateUI() // Refresh entire UI based on new state
    }


    // --- UI Update Logic ---
    private fun updateUI() {
        if (!::game.isInitialized) {
            setGameControlsEnabled(false)
            // Clear opponent names etc.
            textViewOpponent1Name.visibility = View.GONE
            textViewOpponent2Name.visibility = View.GONE
            textViewOpponent3Name.visibility = View.GONE
            textViewCurrentTurnInfo.text = "Turn: -"
            imageViewDiscardPile.setImageResource(R.drawable.ic_card_placeholder)
            cardAdapter.updateCards(emptyList())
            return
        }

        val currentPlayerInGame = game.getCurrentPlayer() // This might be problematic if players list is empty
        val localPlayerInstance = game.getPlayerState(localPlayerId)

        // Update player hand display
        localPlayerInstance?.let { cardAdapter.updateCards(it.hand.toList()) } // Send copy

        // Update Uno button visibility
        buttonUno.visibility = if (localPlayerInstance?.hasUno() == true && currentPlayerInGame.id == localPlayerId) View.VISIBLE else View.GONE

        // Update Pass Turn button (visible if it's player's turn and maybe after drawing)
        buttonPassTurn.visibility = if (currentPlayerInGame.id == localPlayerId && !game.isGameOver) View.VISIBLE else View.GONE


        // Update discard pile image
        val topDiscardCard = game.getTopCard()
        if (topDiscardCard != null) {
            // This should ideally use the same rendering logic as CardAdapter
            imageViewDiscardPile.setBackgroundColor(getAndroidColorForUnoCard(topDiscardCard.color, game.chosenWildColor))
            // For a real app, you'd draw the card face here using a helper or custom view.
            // imageViewDiscardPile.setImageDrawable(CardImageGenerator.getDrawableForCard(this, topDiscardCard))
        } else {
            imageViewDiscardPile.setImageResource(R.drawable.ic_card_placeholder)
            imageViewDiscardPile.setBackgroundColor(android.graphics.Color.LTGRAY)
        }

        // Update turn info
        textViewCurrentTurnInfo.text = "Turn: ${currentPlayerInGame.name}"
        if (currentPlayerInGame.id == localPlayerId && !game.isGameOver) {
            textViewCurrentTurnInfo.append(" (Your Turn)")
            setGameControlsEnabled(true) // Enable controls for local player's turn
        } else {
            setGameControlsEnabled(false) // Disable controls if not local player's turn
        }
        if (game.isGameOver) {
            setGameControlsEnabled(false)
            game.players.find { it.hasWon() }?.let { winner ->
                val winMsg = if (winner.id == localPlayerId) "You won!" else "${winner.name} won!"
                textViewCurrentTurnInfo.text = "Game Over! $winMsg"
                Toast.makeText(this, "Game Over! $winMsg", Toast.LENGTH_LONG).show()
            } ?: run { textViewCurrentTurnInfo.text = "Game Over!" }
        }


        // Update chosen wild color display
        if (game.chosenWildColor != null && game.getTopCard()?.isWildCard() == true) {
            textViewChosenWildColor.text = "Wild Color: ${game.chosenWildColor?.name}"
            textViewChosenWildColor.visibility = View.VISIBLE
        } else {
            textViewChosenWildColor.visibility = View.GONE
        }

        // Update opponent info displays
        val opponents = game.players.filter { it.id != localPlayerId }
        val opponentTextViews = listOf(textViewOpponent1Name, textViewOpponent2Name, textViewOpponent3Name)
        opponentTextViews.forEach { it.visibility = View.GONE } // Hide all first
        opponents.take(3).forEachIndexed { index, opponent ->
            val playerStateFromGame = game.getPlayerState(opponent.id) // Get authoritative state
            opponentTextViews[index].text = "${playerStateFromGame?.name ?: "Opponent"} (${playerStateFromGame?.getHandSize() ?: "?"} cards)"
            opponentTextViews[index].visibility = View.VISIBLE
        }
    }

    private fun setGameControlsEnabled(enabled: Boolean, enableWildPicker: Boolean = true) {
        imageViewDrawPile.isEnabled = enabled
        buttonDrawCard.isEnabled = enabled
        buttonPassTurn.isEnabled = enabled
        // Uno button is contextually managed by its own logic in updateUI

        // RecyclerView items are clickable via adapter logic, this doesn't directly disable them.
        // Adapter needs to know if interaction is allowed.
        // For simplicity, we rely on turn check in handleCardClick.

        if (!enableWildPicker) { // If specifically disabling wild picker (e.g. during choice)
            linearLayoutWildColorPicker.visibility = View.GONE
        } else if (pendingWildCard == null) { // Only hide wild picker if no pending choice
             linearLayoutWildColorPicker.visibility = View.GONE
        }
        // If 'enabled' is false, it means not player's turn or game over, so hide wild picker too.
        if (!enabled && pendingWildCard == null) {
             linearLayoutWildColorPicker.visibility = View.GONE
        }
    }


    private fun getAndroidColorForUnoCard(unoColor: UnoColor, chosenWildColorContext: UnoColor?): Int {
        val effectiveColor = if (unoColor == UnoColor.WILD) chosenWildColorContext ?: UnoColor.WILD else unoColor
        return when (effectiveColor) {
            UnoColor.RED -> android.graphics.Color.parseColor("#FFD32F2F")
            UnoColor.YELLOW -> android.graphics.Color.parseColor("#FFFBC02D")
            UnoColor.GREEN -> android.graphics.Color.parseColor("#FF388E3C")
            UnoColor.BLUE -> android.graphics.Color.parseColor("#FF1976D2")
            UnoColor.WILD -> android.graphics.Color.parseColor("#FF000000") // Black for Wild
            else -> android.graphics.Color.LTGRAY // Should not happen
        }
    }

    private fun logToScreen(message: String) {
        if (textViewGameLog.text.lines().size > 100) { // Keep log from growing too large
            val lines = textViewGameLog.text.toString().lines()
            textViewGameLog.text = lines.takeLast(50).joinToString("\n")
        }
        textViewGameLog.append("\n$message")
        // Auto scroll to bottom
        val scrollAmount = textViewGameLog.layout?.getLineTop(textViewGameLog.lineCount) ?: 0 - textViewGameLog.height
        if (scrollAmount > 0)
            textViewGameLog.scrollTo(0, scrollAmount)
        else
            textViewGameLog.scrollTo(0,0)
    }

    // --- Game.GameListener Implementation (for MainActivity's local UI updates) ---
    // Note: UnoGameHost also implements GameListener for broadcasting.
    // This means Game.kt must support multiple listeners.

    override fun onTurnChanged(newPlayerId: String) { runOnUiThread { updateUI() } }
    override fun onCardPlayed(player: Player, card: Card) { runOnUiThread { updateUI() } }
    override fun onCardDrawn(player: Player, card: Card) { runOnUiThread { updateUI() } }
    override fun onPlayerWon(player: Player) { runOnUiThread { updateUI() } }
    override fun onWildColorChosen(color: UnoColor) { runOnUiThread { updateUI() } }
    override fun onUnoCalled(player: Player) { runOnUiThread { updateUI() } }
    override fun onNextPlayerTurn(player: Player) { runOnUiThread { /* updateUI() potentially for pre-turn hints */ } }
    override fun onInvalidPlay(player: Player, card: Card) {
        runOnUiThread {
            if (player.id == localPlayerId) Toast.makeText(this, "Invalid Play!", Toast.LENGTH_SHORT).show()
            updateUI()
        }
    }
    override fun onDeckEmpty() { runOnUiThread { updateUI() } }
    override fun onHandUpdated(player: Player) {
        runOnUiThread {
            if (player.id == localPlayerId) {
                cardAdapter.updateCards(player.hand.toList()) // Ensure local player's hand is up-to-date
            }
            updateUI() // For opponent card counts etc.
        }
    }
    override fun onGameLog(message: String) { runOnUiThread { logToScreen("GameEngine: $message") } }
    override fun onPlayerAdded(player: Player, allPlayers: List<Player>) { runOnUiThread { updateUI() } }
    override fun onPlayerRemoved(playerId: String, allPlayers: List<Player>) { runOnUiThread { updateUI() } }
    override fun onGameStateChanged() { runOnUiThread { updateUI() } }


    override fun onDestroy() {
        super.onDestroy()
        nsdHelper.tearDown()
        gameHost?.stopHosting()
        gameClient?.disconnect(true) // Try to notify host on destroy
        // Cancel coroutines if any are directly managed by MainActivity scope
    }
}
