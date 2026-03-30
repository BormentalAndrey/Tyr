package com.jbselfcompany.tyr.receiver

import android.content.ServiceConnection
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.util.Log
import com.jbselfcompany.tyr.data.PeerInfo
import com.jbselfcompany.tyr.service.YggmailService

/**
 * Broadcast receiver for periodic maintenance tasks.
 * Works with AlarmManager for Doze Mode compatibility.
 *
 * Battery optimization: Instead of continuous WakeLock renewal,
 * we use AlarmManager to wake up periodically (every 15 minutes)
 * for lightweight maintenance tasks.
 */
class MaintenanceReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "MaintenanceReceiver"
        private const val ACTION_MAINTENANCE = "com.jbselfcompany.tyr.ACTION_MAINTENANCE"
        private const val MAINTENANCE_INTERVAL_MS = 15 * 60 * 1000L // 15 minutes

        /**
         * Schedule periodic maintenance using AlarmManager.
         * Compatible with Doze Mode via setExactAndAllowWhileIdle().
         */
        fun scheduleMaintenance(context: Context) {
            try {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val intent = Intent(context, MaintenanceReceiver::class.java).apply {
                    action = ACTION_MAINTENANCE
                }
                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                // Calculate next trigger time
                val triggerTime = System.currentTimeMillis() + MAINTENANCE_INTERVAL_MS

                // Check if we can schedule exact alarms (Android 12+)
                val canScheduleExactAlarms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    alarmManager.canScheduleExactAlarms()
                } else {
                    true
                }

                if (!canScheduleExactAlarms) {
                    Log.w(TAG, "Cannot schedule exact alarms - permission not granted. Using inexact alarm.")
                    // Fallback to inexact alarm (will work but less precise)
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerTime,
                        pendingIntent
                    )
                    Log.d(TAG, "Scheduled maintenance in ~15 minutes (inexact)")
                    return
                }

                // Use setExactAndAllowWhileIdle for Doze Mode compatibility
                // This guarantees execution even in Doze Mode (up to 9 times per 15 min window)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerTime,
                        pendingIntent
                    )
                    Log.d(TAG, "Scheduled maintenance in 15 minutes (Doze-compatible)")
                } else {
                    alarmManager.setExact(
                        AlarmManager.RTC_WAKEUP,
                        triggerTime,
                        pendingIntent
                    )
                    Log.d(TAG, "Scheduled maintenance in 15 minutes")
                }
            } catch (e: SecurityException) {
                // Android 12+ may throw SecurityException if SCHEDULE_EXACT_ALARM not granted
                Log.e(TAG, "SecurityException scheduling maintenance - exact alarm permission not granted", e)
                // Service will continue to work, just without precise maintenance scheduling
            } catch (e: Exception) {
                Log.e(TAG, "Error scheduling maintenance", e)
            }
        }

        /**
         * Cancel scheduled maintenance.
         */
        fun cancelMaintenance(context: Context) {
            try {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val intent = Intent(context, MaintenanceReceiver::class.java).apply {
                    action = ACTION_MAINTENANCE
                }
                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                alarmManager.cancel(pendingIntent)
                Log.d(TAG, "Maintenance scheduling cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "Error cancelling maintenance", e)
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_MAINTENANCE) {
            return
        }

        Log.d(TAG, "Maintenance task triggered")

        // Acquire a brief WakeLock for the duration of this maintenance
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "Tyr:Maintenance"
        )

        try {
            // Acquire WakeLock for maximum 30 seconds
            wakeLock.acquire(30_000)

            // Check if service is running
            if (!YggmailService.isRunning) {
                Log.d(TAG, "Service not running, skipping maintenance")
                return
            }

            // Bind to service and check peer connectivity
            // Use a static reference via application context
            val serviceIntent = Intent(context, YggmailService::class.java)
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: android.content.ComponentName?, binder: android.os.IBinder?) {
                    try {
                        val service = (binder as? YggmailService.LocalBinder)?.getService()
                        if (service == null) {
                            Log.w(TAG, "Could not get service reference")
                            return
                        }

                        // Check peer connections
                        val connections = service.getPeerConnections()
                        val hasConnectedPeer = connections?.any { it.up } == true

                        if (!hasConnectedPeer) {
                            Log.w(TAG, "No connected peers detected - triggering reconnection")
                            // Hot reload peers to trigger reconnection via Yggdrasil core
                            service.hotReloadPeers()
                            Log.i(TAG, "Peer reconnection triggered")
                        } else {
                            Log.d(TAG, "Peers OK: ${connections?.count { it.up }} connected")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error during peer connectivity check", e)
                    } finally {
                        try { context.unbindService(this) } catch (_: Exception) {}
                    }
                }

                override fun onServiceDisconnected(name: android.content.ComponentName?) {}
            }

            try {
                val bound = context.bindService(
                    serviceIntent,
                    connection,
                    Context.BIND_AUTO_CREATE
                )
                if (!bound) {
                    Log.w(TAG, "Could not bind to service for maintenance")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error binding to service", e)
            }

            Log.d(TAG, "Maintenance peer check initiated")

        } catch (e: Exception) {
            Log.e(TAG, "Error during maintenance", e)
        } finally {
            // Always release WakeLock
            if (wakeLock.isHeld) {
                wakeLock.release()
            }

            // Reschedule next maintenance
            scheduleMaintenance(context)
        }
    }
}
