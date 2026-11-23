package dev.hamza.watchfiles.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Wear OS normally routes traffic over Bluetooth through the phone, which can't reach the
 * home LAN directly and is very slow. While the app is open we ask the system for Wi-Fi and
 * send all of the app's sockets over it.
 */
class WifiNetwork(context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val _network = MutableStateFlow<Network?>(null)
    val network: StateFlow<Network?> = _network.asStateFlow()

    private var callback: ConnectivityManager.NetworkCallback? = null

    fun start() {
        if (callback != null) return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                cm.bindProcessToNetwork(network)
                _network.value = network
            }

            override fun onLost(network: Network) {
                if (_network.value == network) {
                    cm.bindProcessToNetwork(null)
                    _network.value = null
                }
            }
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        runCatching { cm.requestNetwork(request, cb) }.onSuccess { callback = cb }
    }

    fun stop() {
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        callback = null
        cm.bindProcessToNetwork(null)
        _network.value = null
    }
}
