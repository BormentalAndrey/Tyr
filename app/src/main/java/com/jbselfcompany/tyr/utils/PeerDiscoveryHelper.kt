package com.jbselfcompany.tyr.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.jbselfcompany.tyr.utils.TyrLogger
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
            var tempService: mobile.YggmailService? = null
            try {
                val tempDbPath = File(context.cacheDir, "temp_peer_discovery.db").absolutePath
                val tempSmtpAddr = "127.0.0.1:0"  // Port 0 = don't bind
                val tempImapAddr = "127.0.0.1:0"  // Port 0 = don't bind

                tempService = mobile.Mobile.newYggmailService(tempDbPath, tempSmtpAddr, tempImapAddr)

                if (tempService == null) {
                    TyrLogger.e(TAG,"Failed to create temporary service for peer discovery")
                    Handler(Looper.getMainLooper()).post { callback.onProgress(0, 0, 0) }
                    return@Thread
                }

                tempService.setPeerBatchingParams(batchSize.toLong(), concurrency.toLong(), pauseMs.toLong())

                // Capture as val so the lambda can reference it safely
                val svc = tempService

                // Wrap callback to release native resources when discovery completes
                val wrappedCallback = object : PeerDiscoveryCallback {
                    override fun onProgress(current: Long, total: Long, availableCount: Long) {
                        callback.onProgress(current, total, availableCount)
                        if (total > 0 && current >= total) {
                            closeTempService(svc, context)
                        }
                    }
                    override fun onPeerAvailable(peerJSON: String) {
                        callback.onPeerAvailable(peerJSON)
                    }
                }

                tempService.findAvailablePeersAsync(protocols, region, maxRTTMs.toLong(), wrappedCallback)
                TyrLogger.d(TAG,"Peer discovery started: protocols=$protocols, region=$region, maxRTT=${maxRTTMs}ms")

            } catch (e: Exception) {
                TyrLogger.e(TAG,"Error starting peer discovery", e)
                closeTempService(tempService, context)
                Handler(Looper.getMainLooper()).post { callback.onProgress(0, 0, 0) }
            }
        }.apply { name = "PeerDiscovery" }.start()
    }

    private fun closeTempService(service: mobile.YggmailService?, context: android.content.Context? = null) {
        if (service == null) return
        Thread {
            try { service.stop() } catch (e: Exception) { TyrLogger.w(TAG,"Error stopping temp service", e) }
            try { service.close() } catch (e: Exception) { TyrLogger.w(TAG,"Error closing temp service", e) }
            // Delete the temporary DB so no key material is left on disk
            try {
                val tempDb = if (context != null) {
                    java.io.File(context.cacheDir, "temp_peer_discovery.db")
                } else null
                tempDb?.delete()
                TyrLogger.d(TAG,"Temporary peer discovery DB deleted")
            } catch (e: Exception) {
                TyrLogger.w(TAG,"Failed to delete temporary peer discovery DB", e)
            }
        }.apply { name = "PeerDiscovery-Close"; start() }
    }
}
