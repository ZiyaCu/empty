package com.example.unogame.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log

interface IGameAnnouncer {
    fun startBroadcastingGame(gameName: String, port: Int, onRegistered: (Boolean, String?) -> Unit)
    fun stopBroadcastingGame()
}

interface IGameFinder {
    fun startDiscoveringGames(
        onGameFound: (GameServiceInfo) -> Unit,
        onGameLost: (GameServiceInfo) -> Unit,
        onDiscoveryFailed: (Int) -> Unit
    )
    fun stopDiscoveringGames()
}

data class GameServiceInfo(
    val serviceName: String,
    val hostAddress: String?, // Nullable as it might not be resolved immediately or fail
    val port: Int,
    internal val rawNsdServiceInfo: NsdServiceInfo? = null // Keep it internal or private if only for NsdHelper
)

class NsdHelper(private val context: Context) : IGameAnnouncer, IGameFinder {
    private val nsdManager: NsdManager? by lazy {
        context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    }
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private val activeResolveListeners = mutableMapOf<String, NsdManager.ResolveListener>()


    private var wifiManager: WifiManager? = null
    private var multicastLock: WifiManager.MulticastLock? = null


    private val discoveredServicesCache = mutableMapOf<String, GameServiceInfo>()


    private val SERVICE_TYPE = "_uno._tcp" // No trailing dot for registration/discovery calls typically
    private var currentBroadcastedServiceName: String? = null

    companion object {
        const val TAG = "NsdHelper"
    }

    private fun acquireMulticastLock() {
        if (multicastLock == null) {
            wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifiManager?.createMulticastLock("unoGameMulticastLock")
            multicastLock?.setReferenceCounted(true)
        }
        try {
            multicastLock?.acquire()
            Log.d(TAG, "Multicast lock acquired.")
        } catch (e: Exception) {
            Log.e(TAG, "Error acquiring multicast lock", e)
        }
    }

    private fun releaseMulticastLock() {
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
                Log.d(TAG, "Multicast lock released.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing multicast lock", e)
        }
    }


    override fun startBroadcastingGame(gameName: String, port: Int, onRegistered: (Boolean, String?) -> Unit) {
        if (nsdManager == null) {
            Log.e(TAG, "NsdManager not available.")
            onRegistered(false, null)
            return
        }
        stopBroadcastingGame() // Stop any previous broadcast
        acquireMulticastLock()

        val serviceInfo = NsdServiceInfo().apply {
            this.serviceName = gameName // This is what others will see
            this.serviceType = SERVICE_TYPE
            this.port = port
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(nsdServiceInfo: NsdServiceInfo) {
                currentBroadcastedServiceName = nsdServiceInfo.serviceName
                Log.d(TAG, "Service registered: $currentBroadcastedServiceName on port ${nsdServiceInfo.port}")
                onRegistered(true, currentBroadcastedServiceName)
            }
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Service registration failed. Error code: $errorCode for ${serviceInfo.serviceName}")
                currentBroadcastedServiceName = null
                releaseMulticastLock()
                onRegistered(false, null)
            }
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service unregistered: ${serviceInfo.serviceName}")
                // currentBroadcastedServiceName = null // Keep it to allow re-registration check
                releaseMulticastLock()
            }
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Service unregistration failed. Error code: $errorCode for ${serviceInfo.serviceName}")
                // Potentially still release lock if this means the service is effectively gone
                releaseMulticastLock()
            }
        }
        try {
            nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            Log.e(TAG, "Exception during registerService", e)
            onRegistered(false, null)
            releaseMulticastLock()
        }
    }

    override fun stopBroadcastingGame() {
        registrationListener?.let {
            if (currentBroadcastedServiceName != null) { // Only unregister if a service name was successfully set
                try {
                    nsdManager?.unregisterService(it)
                    Log.d(TAG, "Unregistering service: $currentBroadcastedServiceName")
                } catch (e: IllegalArgumentException) {
                    Log.w(TAG, "Error unregistering service (already unregistered or invalid): ${e.message}")
                } catch (e: Exception) {
                    Log.e(TAG, "Exception during unregisterService", e)
                }
            }
            registrationListener = null // Clear listener regardless
            currentBroadcastedServiceName = null
        }
        releaseMulticastLock() // Ensure lock is released
    }

    override fun startDiscoveringGames(
        onGameFound: (GameServiceInfo) -> Unit,
        onGameLost: (GameServiceInfo) -> Unit,
        onDiscoveryFailed: (Int) -> Unit
    ) {
        if (nsdManager == null) {
            Log.e(TAG, "NsdManager not available.")
            onDiscoveryFailed(-1) // Custom error code for NsdManager unavailable
            return
        }
        stopDiscoveringGames() // Stop any previous discovery
        acquireMulticastLock()
        discoveredServicesCache.clear()

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.d(TAG, "Service discovery started for type: $regType")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                Log.d(TAG, "Service found: Name=${service.serviceName}, Type=${service.serviceType}, Host=${service.host}, Port=${service.port}")
                if (service.serviceType == SERVICE_TYPE || service.serviceType == "$SERVICE_TYPE.") {
                    if (service.serviceName == currentBroadcastedServiceName) {
                        Log.d(TAG, "Ignoring own broadcasted service: ${service.serviceName}")
                        return
                    }
                    if (activeResolveListeners.containsKey(service.serviceName)) {
                        Log.d(TAG, "Already attempting to resolve: ${service.serviceName}")
                        return
                    }

                    val resolveListener = object : NsdManager.ResolveListener {
                        override fun onResolveFailed(failedServiceInfo: NsdServiceInfo, errorCode: Int) {
                            Log.e(TAG, "Resolve failed for ${failedServiceInfo.serviceName}. Error: $errorCode")
                            activeResolveListeners.remove(failedServiceInfo.serviceName)
                        }

                        override fun onServiceResolved(resolvedServiceInfo: NsdServiceInfo) {
                            Log.i(TAG, "Service resolved: Name=${resolvedServiceInfo.serviceName}, Host=${resolvedServiceInfo.host}, Port=${resolvedServiceInfo.port}")
                            val gameInfo = GameServiceInfo(
                                serviceName = resolvedServiceInfo.serviceName,
                                hostAddress = resolvedServiceInfo.host?.hostAddress,
                                port = resolvedServiceInfo.port,
                                rawNsdServiceInfo = resolvedServiceInfo
                            )
                            if (gameInfo.hostAddress != null) { // Ensure host address is valid
                                discoveredServicesCache[resolvedServiceInfo.serviceName] = gameInfo
                                onGameFound(gameInfo)
                            } else {
                                Log.w(TAG, "Resolved service ${resolvedServiceInfo.serviceName} but host address is null.")
                            }
                            activeResolveListeners.remove(resolvedServiceInfo.serviceName)
                        }
                    }
                    activeResolveListeners[service.serviceName] = resolveListener
                    nsdManager?.resolveService(service, resolveListener)
                } else {
                     Log.d(TAG, "Found service of different type: ${service.serviceType}")
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                Log.w(TAG, "Service lost: ${service.serviceName}")
                activeResolveListeners.remove(service.serviceName) // Stop trying to resolve if it's lost
                val removedService = discoveredServicesCache.remove(service.serviceName)
                removedService?.let { onGameLost(it) }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.i(TAG, "Discovery stopped: $serviceType")
                releaseMulticastLock()
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Discovery failed to start. Error: $errorCode for type: $serviceType")
                releaseMulticastLock()
                onDiscoveryFailed(errorCode)
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Discovery failed to stop. Error: $errorCode for type: $serviceType")
                // Lock might still need to be released if stopping failed but discovery isn't active
                releaseMulticastLock()
            }
        }
        try {
            nsdManager?.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            Log.e(TAG, "Exception during discoverServices", e)
            onDiscoveryFailed(-2) // Custom error for exception
            releaseMulticastLock()
        }
    }

    override fun stopDiscoveringGames() {
        discoveryListener?.let {
            try {
                nsdManager?.stopServiceDiscovery(it)
                Log.d(TAG, "Stopping service discovery.")
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Error stopping discovery (already stopped or invalid): ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "Exception during stopServiceDiscovery", e)
            }
            discoveryListener = null
        }
        // Clear any pending resolve listeners as well
        activeResolveListeners.clear()
        // Don't clear discoveredServicesCache here, it might be useful for UI until explicitly refreshed
        releaseMulticastLock() // Ensure lock is released
    }

    // Call this in Activity's onDestroy or when network operations should permanently cease
    fun tearDown() {
        Log.d(TAG, "Tearing down NsdHelper.")
        stopBroadcastingGame() // This will also release lock if held for broadcasting
        stopDiscoveringGames() // This will also release lock if held for discovery
        // Explicitly release lock if it's somehow still held (should be covered by stop methods)
        releaseMulticastLock()
    }
}
