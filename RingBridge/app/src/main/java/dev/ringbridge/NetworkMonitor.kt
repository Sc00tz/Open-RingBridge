package dev.ringbridge

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager

/** Watches internet connectivity and fires callbacks on changes. */
class NetworkMonitor(private val context: Context) {

    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    var onConnected: (() -> Unit)? = null

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (isUsable()) onConnected?.invoke()
        }
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            if (isUsable()) onConnected?.invoke()
        }
    }

    fun start() {
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.registerNetworkCallback(req, callback)
    }

    fun stop() {
        try { cm.unregisterNetworkCallback(callback) } catch (_: Exception) {}
    }

    /** True if the device has internet access and, if wifiOnly is set, is on Wi-Fi. */
    fun isUsable(wifiOnly: Boolean = false): Boolean {
        val net  = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        if (wifiOnly && !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return false
        return true
    }
}
