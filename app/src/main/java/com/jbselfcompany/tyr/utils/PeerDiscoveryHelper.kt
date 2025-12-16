package com.jbselfcompany.tyr.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import mobile.PeerDiscoveryCallback
import java.io.File

/**
 * Helper class for peer discovery that doesn't require a running service.
 * Creates a temporary YggmailService instance just for peer discovery.
 */
object PeerDiscoveryHelper {
    private const val TAG = "PeerDiscoveryHelper"

    /**
     * Start peer discovery asynchronously without requiring a running service
     *
     * @param context Application context
     * @param protocols Comma-separated protocol list (e.g., "tcp,tls,quic")
     * @param region Region filter (empty for all regions)
     * @param maxRTTMs Maximum RTT in milliseconds
     * @param callback Callback for progress and results
     * @param batchSize Batch size for peer checking (default: 20, optimal for 10-100 Mbps)
     * @param concurrency Number of concurrent peer checks (default: 20)
     * @param pauseMs Pause between batches in milliseconds (default: 200)
     */
    fun findAvailablePeersAsync(
        context: Context,
        protocols: String,
        region: String,
        maxRTTMs: Int,
        callback: PeerDiscoveryCallback,
        batchSize: Int = 20,
        concurrency: Int = 20,
        pauseMs: Int = 200
    ) {
        Thread {
            try {
                // Create a temporary YggmailService instance just for peer discovery
                // We use a temporary in-memory database path that doesn't need to exist
                val tempDbPath = File(context.cacheDir, "temp_peer_discovery.db").absolutePath
                val tempSmtpAddr = "127.0.0.1:0"  // Port 0 = don't bind
                val tempImapAddr = "127.0.0.1:0"  // Port 0 = don't bind

                val tempService = mobile.Mobile.newYggmailService(
                    tempDbPath,
                    tempSmtpAddr,
                    tempImapAddr
                )

                if (tempService == null) {
                    Log.e(TAG, "Failed to create temporary service for peer discovery")
                    return@Thread
                }

                // Set batching parameters
                tempService.setPeerBatchingParams(batchSize.toLong(), concurrency.toLong(), pauseMs.toLong())

                // Start peer discovery (doesn't require Initialize() or Start())
                tempService.findAvailablePeersAsync(protocols, region, maxRTTMs.toLong(), callback)

                Log.d(TAG, "Peer discovery started: protocols=$protocols, region=$region, maxRTT=${maxRTTMs}ms")

            } catch (e: Exception) {
                Log.e(TAG, "Error starting peer discovery", e)

                // Notify callback about error
                Handler(Looper.getMainLooper()).post {
                    // Signal completion with 0 results
                    callback.onProgress(0, 0, 0)
                }
            }
        }.start()
    }
}
