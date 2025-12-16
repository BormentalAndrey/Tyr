package com.jbselfcompany.tyr.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.json.JSONObject

/**
 * Represents a discovered Yggdrasil peer from the public peer list
 */
data class DiscoveredPeer(
    val address: String,      // "tls://host:port"
    val protocol: String,     // "tcp", "tls", "quic", "ws", "wss"
    val region: String,       // "germany", "france", etc.
    val rtt: Long,           // RTT in milliseconds
    val available: Boolean,
    val responseMs: Int,     // Response time from publicnodes.json
    val lastSeen: Long       // Unix timestamp
) {
    /**
     * Get formatted RTT string (e.g., "45ms")
     */
    fun getRttFormatted(): String {
        return "${rtt}ms"
    }

    /**
     * Convert to PeerInfo for use with existing configuration
     */
    fun toPeerInfo(): PeerInfo {
        return PeerInfo(
            uri = address,
            isEnabled = true,
            tag = PeerInfo.PeerTag.CUSTOM
        )
    }

    companion object {
        /**
         * Parse DiscoveredPeer from JSON object
         */
        fun fromJson(json: JSONObject): DiscoveredPeer {
            return DiscoveredPeer(
                address = json.getString("address"),
                protocol = json.getString("protocol"),
                region = json.optString("region", ""),
                rtt = json.getLong("rtt"),
                available = json.getBoolean("available"),
                responseMs = json.getInt("response_ms"),
                lastSeen = json.getLong("last_seen")
            )
        }
    }
}

/**
 * Network utility functions for peer discovery
 */
object NetworkUtils {
    /**
     * Get optimal batching parameters based on network type
     * Returns Triple(batchSize, concurrency, pauseMs)
     *
     * Default values match yggpeers.DefaultBatchSize/Concurrency/PauseMs (40, 20, 150)
     * WiFi/Ethernet: (30, 25, 150) - more aggressive for fast connections
     * Mobile: (15, 10, 250) - conservative for battery saving
     * Default: (40, 20, 150) - balanced for unknown network types
     */
    fun getBatchingParams(context: Context): Triple<Int, Int, Int> {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return Triple(40, 20, 150) // Default from yggpeers

        val network = connectivityManager.activeNetwork ?: return Triple(40, 20, 150)
        val capabilities = connectivityManager.getNetworkCapabilities(network)
            ?: return Triple(40, 20, 150)

        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ->
                Triple(30, 25, 150) // WiFi - more aggressive

            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ->
                Triple(15, 10, 250) // Mobile - conservative for battery

            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ->
                Triple(30, 25, 150) // Ethernet - aggressive like WiFi

            else ->
                Triple(40, 20, 150) // Default from yggpeers
        }
    }

    /**
     * Check if network is available
     */
    fun isNetworkAvailable(context: Context): Boolean {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false

        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false

        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
