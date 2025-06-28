package com.example.unogame.network

import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket
import java.net.SocketException
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.SerializationException
import android.util.Log

interface IGameClient {
    fun connect(hostAddress: String, port: Int, desiredName: String, existingPlayerId: String?)
    fun disconnect(notifyHost: Boolean = true) // Added flag to control sending disconnect message
    fun sendMessage(message: GameMessage)
    fun isConnected(): Boolean
}

class UnoGameClient(
    private val onMessageReceived: (GameMessage) -> Unit, // For game state updates, chat, errors from host
    private val onConnectionStatusChanged: (connected: Boolean, message: String, assignedId: String?) -> Unit // success/failure, message, assignedPlayerId
) : IGameClient {
    companion object { const val TAG = "UnoGameClient" }

    private var socket: Socket? = null
    private var writer: PrintWriter? = null
    private var readerJob: Job? = null
    private val clientScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var currentHost: String? = null
    private var myPlayerId: String? = null // Assigned by host

    private var lastPingSent: Long = 0
    private var lastPongReceived: Long = 0
    private var keepAliveJob: Job? = null


    override fun connect(hostAddress: String, port: Int, desiredName: String, existingPlayerId: String?) {
        if (isConnected()) {
            Log.w(TAG, "Already connected or connecting to $currentHost.")
            // onConnectionStatusChanged(true, "Already connected to $currentHost.", myPlayerId)
            return
        }
        currentHost = "$hostAddress:$port"
        // Reset player ID for new connection attempt
        myPlayerId = existingPlayerId

        readerJob = clientScope.launch {
            try {
                Log.d(TAG, "Attempting to connect to $hostAddress:$port")
                socket = Socket(hostAddress, port)
                socket?.keepAlive = true // Enable TCP keep-alive
                writer = PrintWriter(socket!!.getOutputStream(), true)
                val reader = BufferedReader(InputStreamReader(socket!!.getInputStream()))
                Log.d(TAG, "Socket connected. Sending ConnectionRequest for name: $desiredName, existingId: $myPlayerId")

                val connectionRequest = GameMessage.ConnectionRequest(desiredName, myPlayerId)
                writer!!.println(Json.encodeToString<GameMessage>(connectionRequest))
                onConnectionStatusChanged(false, "Connection request sent, awaiting response...", null)


                val responseJson = withContext(Dispatchers.IO) { reader.readLine() }
                if (responseJson == null) {
                    throw SocketException("Host closed connection before sending ConnectionResponse.")
                }
                Log.d(TAG, "Received response from host: $responseJson")
                val response = Json.decodeFromString<GameMessage>(responseJson)

                if (response is GameMessage.ConnectionResponse) {
                    if (response.accepted) {
                        myPlayerId = response.assignedPlayerId // Crucial: Get ID from Host
                        Log.i(TAG, "Successfully connected to host: ${response.message}. Assigned ID: $myPlayerId")
                        onConnectionStatusChanged(true, "Connected: ${response.message}", myPlayerId)

                        response.initialGameState?.let {
                            withContext(Dispatchers.Main) { onMessageReceived(it) }
                        }

                        startKeepAlive() // Start sending pings

                        // Start listening for messages
                        while (isActive && socket?.isConnected == true) {
                            val messageJson = withContext(Dispatchers.IO) { reader.readLine() }
                            if (messageJson == null) {
                                Log.i(TAG, "Host disconnected (readLine is null).")
                                break
                            }
                            // Log.d(TAG, "Client received: $messageJson") // Can be very verbose
                            try {
                                val gameMessage = Json.decodeFromString<GameMessage>(messageJson)
                                if (gameMessage is GameMessage.Pong) {
                                    lastPongReceived = System.currentTimeMillis()
                                    // Log.v(TAG, "Pong received. Latency: ${lastPongReceived - gameMessage.timestamp}ms")
                                } else {
                                     withContext(Dispatchers.Main) { // Switch to Main thread for UI updates via onMessageReceived
                                        onMessageReceived(gameMessage)
                                    }
                                }
                            } catch (e: SerializationException) {
                                Log.e(TAG, "Deserialization error: ${e.message}. JSON: $messageJson")
                            }
                        }
                    } else {
                        Log.w(TAG, "Connection refused by host: ${response.message}")
                        onConnectionStatusChanged(false, "Connection refused: ${response.message}", null)
                    }
                } else {
                    Log.w(TAG, "Unexpected response from host: $response")
                    onConnectionStatusChanged(false, "Unexpected response from host.", null)
                }

            } catch (e: SocketException) {
                 Log.e(TAG, "Client SocketException with $hostAddress:$port : ${e.message}")
                 onConnectionStatusChanged(false, "Network error: ${e.message}", null)
            } catch (e: Exception) {
                Log.e(TAG, "Client connection error to $hostAddress:$port : ${e.message}", e)
                onConnectionStatusChanged(false, "Connection error: ${e.message}", null)
            } finally {
                Log.d(TAG, "Client connection processing ended for $currentHost.")
                val wasConnected = isConnected() // Check status before cleanup
                cleanUpClientSocketAndStreams()
                stopKeepAlive()
                // Notify connection status change only if it was previously connected or if error is new
                if (isActive && wasConnected) { // If the job is still active and was connected, means it was an unexpected disconnect
                    onConnectionStatusChanged(false, "Disconnected from host.", null)
                } else if (!isActive && !wasConnected && currentHost != null){
                    // If job was cancelled (disconnect called) and it wasn't connected, means connection failed before fully establishing
                    // onConnectionStatusChanged(false, "Connection attempt failed or cancelled.", null)
                }
                currentHost = null
                // myPlayerId = null; // Keep player ID for potential reconnects unless explicitly cleared
            }
        }
    }

    private fun startKeepAlive() {
        stopKeepAlive() // Ensure no previous job is running
        keepAliveJob = clientScope.launch {
            while (isActive && isConnected()) {
                delay(5000) // Send ping every 5 seconds
                if (isConnected()) {
                    val pingMsg = GameMessage.Ping(System.currentTimeMillis())
                    try {
                        writer?.println(Json.encodeToString<GameMessage>(pingMsg))
                        lastPingSent = pingMsg.timestamp
                        // Log.v(TAG, "Ping sent.")

                        // Check for timeout if pong not received
                        delay(3000) // Wait 3 seconds for pong
                        if (lastPongReceived < lastPingSent && isConnected()) {
                            Log.w(TAG, "Pong not received in time. Connection might be stale.")
                            // Consider this a disconnection or trigger more aggressive checks
                            // For now, we'll let the TCP layer or next readLine failure handle actual disconnect.
                        }
                    } catch (e: Exception) {
                         Log.e(TAG, "Error sending ping: ${e.message}")
                         // This likely means connection is dead. Trigger disconnect.
                         // disconnect(notifyHost = false) // Avoid feedback loop if already disconnecting
                         // Let the main read loop handle the actual disconnection.
                         break // Exit keepAlive loop
                    }
                }
            }
            Log.d(TAG, "KeepAlive job ended.")
        }
    }

    private fun stopKeepAlive() {
        keepAliveJob?.cancel()
        keepAliveJob = null
    }


    override fun sendMessage(message: GameMessage) {
        if (!isConnected() || writer == null) {
            Log.w(TAG, "Cannot send message, not connected. Message: ${message::class.simpleName}")
            return
        }
        // Ensure critical info like playerId is set for messages originating from client
        // (e.g. PlayerAction types)
        // This should be done by the caller of sendMessage.

        clientScope.launch {
            try {
                val messageJson = Json.encodeToString(message)
                // Log.d(TAG, "Client sending: $messageJson") // Can be verbose
                writer?.println(messageJson)
            } catch (e: Exception) {
                Log.e(TAG, "Error sending message (${message::class.simpleName}): ${e.message}", e)
                // This error often means the socket is dead. Trigger disconnect handling.
                // Let the main read loop or keepAlive detect and handle the actual disconnect.
                // Forcing disconnect here might be too aggressive or cause race conditions.
                // Consider if this should call disconnect().
            }
        }
    }

    private fun cleanUpClientSocketAndStreams() {
        Log.d(TAG, "Cleaning up client socket and streams for $currentHost.")
        try { writer?.close() } catch (e: Exception) {Log.w(TAG, "Exc closing writer", e)}
        try { socket?.close() } catch (e: Exception) {Log.w(TAG, "Exc closing socket", e)}
        writer = null
        socket = null
    }

    override fun disconnect(notifyHost: Boolean) {
        Log.i(TAG, "disconnect() called. Notify host: $notifyHost. Current status: connected=${isConnected()}")

        // if (notifyHost && isConnected() && myPlayerId != null) {
        //     // Send a graceful disconnect message to the host (optional)
        //     // val disconnectMsg = GameMessage.PlayerActionDisconnect(myPlayerId!!)
        //     // sendMessage(disconnectMsg)
        //     // Give it a moment to send, then proceed with local disconnect.
        //     // This is tricky; if socket is already bad, this won't work.
        // }

        readerJob?.cancel() // Cancels the connection and listening coroutine. Finally block will clean up.
        stopKeepAlive()

        // If readerJob was null or not active (never fully connected), still ensure cleanup and status update
        if (readerJob == null || !readerJob!!.isActive || !readerJob!!.isCompleted) {
             cleanUpClientSocketAndStreams() // Explicit cleanup if job wasn't running / didn't cleanup
             if (currentHost != null || myPlayerId != null) { // Only if a connection was attempted
                onConnectionStatusChanged(false, "Manually disconnected.", null)
             }
        }
        currentHost = null
        // myPlayerId = null; // Keep for reconnect attempts? Or clear? Let's clear.
        // myPlayerId = null; // User might want to reconnect with same ID if host supports.
        readerJob = null // Ensure job reference is cleared
    }

    override fun isConnected(): Boolean = socket?.isConnected == true && writer != null && readerJob?.isActive == true
}
