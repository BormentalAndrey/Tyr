package com.jbselfcompany.tyr.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.jbselfcompany.tyr.R
import com.jbselfcompany.tyr.TyrApplication
import com.jbselfcompany.tyr.data.PeerInfo
import com.jbselfcompany.tyr.receiver.MaintenanceReceiver
import com.jbselfcompany.tyr.ui.MainActivity
import mobile.LogCallback
import mobile.YggmailService as MobileYggmailService
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Foreground service that runs Yggmail server.
 * Manages lifecycle of Yggmail service and provides status updates.
 *
 * Battery optimization: Uses timed WakeLock with periodic renewal
 * to balance connectivity and power consumption.
 */
class YggmailService : Service(), LogCallback {

    companion object {
        private const val TAG = "YggmailService"
        private const val NOTIFICATION_ID = 1001

        // WakeLock constants for battery optimization
        // Differentiate WakeLock usage by operation type
        private const val WAKELOCK_SEND_TIMEOUT_MS = 60 * 1000L  // 1 minute for active sending
        private const val WAKELOCK_IDLE_TIMEOUT_MS = 5 * 60 * 1000L  // 5 minutes for idle maintenance
        private const val WAKELOCK_IDLE_THRESHOLD_MS = 2 * 60 * 1000L  // 2 minutes of inactivity
        private const val WAKELOCK_GRACE_PERIOD_MS = 5 * 1000L  // 5-second grace period before release

        const val ACTION_START = "com.jbselfcompany.tyr.START"
        const val ACTION_STOP = "com.jbselfcompany.tyr.STOP"
        const val ACTION_SOFT_STOP = "com.jbselfcompany.tyr.SOFT_STOP"

        /**
         * Check if service is currently running
         */
        var isRunning = false
            private set

        /**
         * Start the Yggmail service
         */
        fun start(context: Context) {
            val intent = Intent(context, YggmailService::class.java).apply {
                action = ACTION_START
            }
            try {
                context.startForegroundService(intent)
            } catch (e: Exception) {
                // Android 12+ may throw ForegroundServiceStartNotAllowedException
                // if app is in background or doesn't meet other foreground service requirements
                Log.e(TAG, "Failed to start foreground service", e)
                // Service will not start, but we don't crash the app
            }
        }

        /**
         * Stop the Yggmail service
         */
        fun stop(context: Context) {
            val intent = Intent(context, YggmailService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        /**
         * Soft stop the Yggmail service (gracefully disconnect peers first)
         */
        fun softStop(context: Context) {
            val intent = Intent(context, YggmailService::class.java).apply {
                action = ACTION_SOFT_STOP
            }
            context.startService(intent)
        }

        /**
         * Delete the Yggmail database to regenerate keys
         */
        fun deleteDatabase(context: Context): Boolean {
            val dbFile = File(context.filesDir, "yggmail.db")
            return if (dbFile.exists()) {
                dbFile.delete()
            } else {
                true // Already doesn't exist
            }
        }
    }

    // Service state
    private var yggmailService: MobileYggmailService? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val configRepository by lazy { TyrApplication.instance.configRepository }

    // Threading
    private lateinit var serviceThread: HandlerThread
    private lateinit var serviceHandler: Handler
    private val mainHandler = Handler(Looper.getMainLooper())

    // Notification
    private lateinit var notificationManager: NotificationManager

    // Service status
    private var serviceStatus = ServiceStatus.STOPPED
    private var lastError: String? = null
    private val statusListeners = mutableListOf<ServiceStatusListener>()

    // Connection status tracking
    private var lastConnectionStatus: String? = null
    private var connectionCheckRunnable: Runnable? = null

    // Battery optimization state
    private var isAppActive = false // Track if app is in foreground
    private var isCharging = false // Track if device is charging
    private var isDozing = false // Track if device is in Doze Mode

    // Battery optimization: Track active send operations
    private var activeSendOperations = java.util.concurrent.atomic.AtomicInteger(0)
    private var lastSendActivity = System.currentTimeMillis()

    // Doze Mode and Battery receivers
    private val dozeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> {
                    val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                    val wasDozing = isDozing
                    isDozing = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        powerManager.isDeviceIdleMode
                    } else {
                        false
                    }

                    if (wasDozing != isDozing) {
                        Log.d(TAG, "[Battery] Doze mode changed: $isDozing")
                        updateNativeServicePowerState()
                    }
                }
            }
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_POWER_CONNECTED -> {
                    isCharging = true
                    Log.d(TAG, "[Battery] Device charging started")
                    updateNativeServicePowerState()
                }
                Intent.ACTION_POWER_DISCONNECTED -> {
                    isCharging = false
                    Log.d(TAG, "[Battery] Device on battery")
                    updateNativeServicePowerState()
                }
            }
        }
    }

    // Mail activity monitoring for adaptive heartbeat
    // No periodic polling needed - yggmail library handles adaptive heartbeat internally
    // We only notify on actual SMTP/IMAP activity via setAppActive() and notifyMailActivity()

    // Binder for local service binding
    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): YggmailService = this@YggmailService
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate")

        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Create service thread
        serviceThread = HandlerThread("YggmailServiceThread").apply { start() }
        serviceHandler = Handler(serviceThread.looper)

        // Acquire wake lock (will be used only for critical operations, not continuously)
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "Tyr::YggmailService"
        ).apply {
            setReferenceCounted(false)
        }

        // Register Doze Mode receiver for battery optimization
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val dozeFilter = IntentFilter(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
            registerReceiver(dozeReceiver, dozeFilter)
            Log.d(TAG, "[Battery] Doze Mode receiver registered")
        }

        // Register battery charging receiver
        val batteryFilter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        registerReceiver(batteryReceiver, batteryFilter)
        Log.d(TAG, "[Battery] Battery receiver registered")

        // Check initial charging state
        val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        batteryIntent?.let {
            val status = it.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL
            Log.d(TAG, "[Battery] Initial charging state: $isCharging")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service onStartCommand: ${intent?.action}")

        when (intent?.action) {
            ACTION_START -> {
                if (!isRunning) {
                    startForegroundWithNotification()
                    startYggmail()
                } else {
                    Log.w(TAG, "Service already running, ignoring START action")
                }
            }
            ACTION_STOP -> {
                // Post stopSelf() to happen AFTER stopYggmail completes on serviceHandler thread
                // This prevents race condition where onDestroy() is called while stop is in progress
                serviceHandler.post {
                    stopYggmailSync()
                    // Call stopSelf on main thread after cleanup completes
                    mainHandler.post {
                        stopSelf()
                    }
                }
            }
            ACTION_SOFT_STOP -> {
                // Soft stop - gracefully disconnect peers first, then stop
                serviceHandler.post {
                    performSoftStopSync()
                    // Call stopSelf on main thread after cleanup completes
                    mainHandler.post {
                        stopSelf()
                    }
                }
            }
            else -> {
                // Service restarted by system
                if (!isRunning) {
                    startForegroundWithNotification()
                    startYggmail()
                }
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onDestroy() {
        Log.d(TAG, "Service onDestroy")

        // Cancel maintenance scheduling
        MaintenanceReceiver.cancelMaintenance(this)

        // Unregister receivers
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                unregisterReceiver(dozeReceiver)
                Log.d(TAG, "[Battery] Doze Mode receiver unregistered")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering doze receiver", e)
        }

        try {
            unregisterReceiver(batteryReceiver)
            Log.d(TAG, "[Battery] Battery receiver unregistered")
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering battery receiver", e)
        }

        // Only stop if not already stopped (prevent duplicate stop calls)
        if (isRunning) {
            Log.w(TAG, "Service destroyed while still running - forcing cleanup")
            // Post to handler and wait for completion to avoid race conditions
            val latch = java.util.concurrent.CountDownLatch(1)
            serviceHandler.post {
                try {
                    stopYggmailSync()
                } finally {
                    latch.countDown()
                }
            }
            // Wait up to 5 seconds for cleanup to complete
            try {
                latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
            } catch (e: InterruptedException) {
                Log.e(TAG, "Interrupted while waiting for service cleanup", e)
            }
        }

        // Now safe to quit the thread
        serviceThread.quitSafely()
        try {
            // Wait for thread to actually terminate (max 2 seconds)
            serviceThread.join(2000)
        } catch (e: InterruptedException) {
            Log.e(TAG, "Interrupted while waiting for service thread termination", e)
        }

        releaseWakeLock()

        // Ensure notification is removed
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        notificationManager.cancel(NOTIFICATION_ID)

        super.onDestroy()
    }

    /**
     * Start foreground service with notification
     */
    private fun startForegroundWithNotification() {
        try {
            val notification = createNotification(ServiceStatus.STARTING)
            startForeground(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            // Handle potential exceptions from startForeground()
            // (e.g., on Android 12+ if foreground service type restrictions are violated)
            Log.e(TAG, "Failed to start foreground with notification", e)
            // Update status to ERROR and stop service
            lastError = "Failed to start foreground service: ${e.message}"
            updateStatus(ServiceStatus.ERROR)
            stopSelf()
        }
    }

    /**
     * Start Yggmail service on background thread
     */
    private fun startYggmail() {
        serviceHandler.post {
            startYggmailSync()
        }
    }

    /**
     * Synchronous start logic (called from handler thread)
     */
    private fun startYggmailSync() {
        try {
            Log.i(TAG, "Starting Yggmail service...")
            updateStatus(ServiceStatus.STARTING)

            // Get configuration
            val password = configRepository.getPassword()
            if (password.isNullOrEmpty()) {
                throw IllegalStateException("Password not configured")
            }

            val peers = configRepository.getPeersString()

            Log.d(TAG, "Peers: '$peers'")

            // Database path
            val dbPath = File(filesDir, "yggmail.db").absolutePath
            Log.d(TAG, "Database path: $dbPath")

            // SMTP and IMAP addresses (localhost only, for DeltaChat)
            val smtpAddr = "127.0.0.1:1025"
            val imapAddr = "127.0.0.1:1143"

            // Create Yggmail service
            // Always set LogCallback, but onLog() will check if logging is enabled
            yggmailService = mobile.Mobile.newYggmailService(dbPath, smtpAddr, imapAddr).apply {
                setLogCallback(this@YggmailService)
            }

            // Initialize (creates/loads keys)
            yggmailService?.initialize()
            Log.d(TAG, "Yggmail initialized")

            // Set password
            yggmailService?.setPassword(password)
            Log.d(TAG, "Password configured")

            // Save mail address for display
            val mailAddress = yggmailService?.getMailAddress() ?: ""
            val publicKey = yggmailService?.getPublicKey() ?: ""
            configRepository.saveMailAddress(mailAddress)
            configRepository.savePublicKey(publicKey)
            Log.i(TAG, "Mail address: $mailAddress")

            // Start with configured peers
            yggmailService?.start(peers)
            Log.i(TAG, "Yggmail service started successfully")

            // Schedule periodic maintenance using AlarmManager (Doze-compatible)
            MaintenanceReceiver.scheduleMaintenance(this@YggmailService)
            Log.d(TAG, "[Battery] Maintenance scheduling started")

            // Update native service with current power state
            updateNativeServicePowerState()

            isRunning = true
            updateStatus(ServiceStatus.RUNNING)

            // Start periodic connection status check for notification updates
            startConnectionStatusCheck()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Yggmail service", e)
            lastError = e.message
            updateStatus(ServiceStatus.ERROR)
            mainHandler.post {
                stopSelf()
            }
        }
    }

    /**
     * Stop Yggmail service on background thread
     * Note: For normal stop operations, use ACTION_STOP intent instead.
     * This method is kept for backward compatibility and emergency cleanup.
     */
    private fun stopYggmail() {
        serviceHandler.post {
            stopYggmailSync()
        }
    }

    /**
     * Synchronous stop logic (called from handler thread)
     * Thread-safe with proper exception handling for native library cleanup
     * Includes comprehensive panic/crash recovery for native library issues
     */
    private fun stopYggmailSync() {
        // Stop periodic connection status check
        stopConnectionStatusCheck()

        // Prevent concurrent stop operations
        synchronized(this) {
            if (!isRunning) {
                Log.w(TAG, "Service already stopped, ignoring stop request")
                return
            }

            // Mark as stopping immediately to prevent new operations
            isRunning = false
            updateStatus(ServiceStatus.STOPPING)
            Log.i(TAG, "Stopping Yggmail service...")
        }

        // Track if cleanup was successful
        var cleanupSuccessful = false
        var stopError: Throwable? = null
        var closeError: Throwable? = null

        try {
            // Acquire WakeLock for shutdown process to prevent interruption
            // This is critical to ensure complete cleanup
            acquireWakeLockForOperation("shutdown", 15_000)

            // Step 1: Stop the service (closes network connections)
            // Wrap in try-catch for ALL throwables (including native crashes/panics)
            try {
                yggmailService?.let { service ->
                    Log.d(TAG, "Calling native stop()...")

                    // Call stop() in a timeout-protected block
                    val stopLatch = CountDownLatch(1)
                    var stopResult: Throwable? = null

                    val stopThread = Thread {
                        try {
                            service.stop()
                            Log.d(TAG, "Native stop() completed successfully")
                        } catch (t: Throwable) {
                            stopResult = t
                            Log.e(TAG, "Exception during native stop()", t)
                        } finally {
                            stopLatch.countDown()
                        }
                    }

                    stopThread.name = "YggmailStopThread"
                    stopThread.start()

                    // Wait with timeout
                    if (!stopLatch.await(10, TimeUnit.SECONDS)) {
                        Log.e(TAG, "Native stop() timed out after 10 seconds")
                        stopThread.interrupt()
                        stopError = Exception("Native stop() timeout")
                    } else if (stopResult != null) {
                        stopError = stopResult
                    }

                    // Give network connections time to close gracefully
                    Thread.sleep(500)
                }
            } catch (t: Throwable) {
                // Catch ALL throwables including crashes from native code
                stopError = t
                Log.e(TAG, "Critical error calling native stop()", t)
                // Continue with close() even if stop() failed critically
            }

            // Step 2: Close the service (releases all resources)
            // Only attempt close if we still have a valid reference
            try {
                yggmailService?.let { service ->
                    Log.d(TAG, "Calling native close()...")

                    // Call close() in a timeout-protected block
                    val closeLatch = CountDownLatch(1)
                    var closeResult: Throwable? = null

                    val closeThread = Thread {
                        try {
                            service.close()
                            Log.d(TAG, "Native close() completed successfully")
                        } catch (t: Throwable) {
                            closeResult = t
                            Log.e(TAG, "Exception during native close()", t)
                        } finally {
                            closeLatch.countDown()
                        }
                    }

                    closeThread.name = "YggmailCloseThread"
                    closeThread.start()

                    // Wait with timeout
                    if (!closeLatch.await(5, TimeUnit.SECONDS)) {
                        Log.e(TAG, "Native close() timed out after 5 seconds")
                        closeThread.interrupt()
                        closeError = Exception("Native close() timeout")
                    } else if (closeResult != null) {
                        closeError = closeResult
                    }

                    // Give Go runtime time to finalize
                    Thread.sleep(500)
                }
            } catch (t: Throwable) {
                // Catch ALL throwables including crashes from native code
                closeError = t
                Log.e(TAG, "Critical error calling native close()", t)
                // Continue cleanup even if close() failed critically
            }

            // Step 3: Clear reference to native service
            yggmailService = null

            // Step 4: Force garbage collection to help release Go resources
            // This is important for gomobile-generated code
            Log.d(TAG, "Requesting garbage collection...")
            System.gc()
            System.runFinalization()

            // Step 5: Wait for ports to be fully released
            // TCP sockets may remain in TIME_WAIT state
            Log.d(TAG, "Waiting for port release...")
            Thread.sleep(1000)

            cleanupSuccessful = (stopError == null && closeError == null)
            Log.i(TAG, "Yggmail service stopped (cleanup ${if (cleanupSuccessful) "successful" else "with errors"})")

        } catch (t: Throwable) {
            // Final catch-all for any unexpected issues
            Log.e(TAG, "Unexpected critical error during service shutdown", t)
            lastError = "Critical shutdown error: ${t.message}"
        } finally {
            // Always release WakeLock in finally block
            releaseWakeLock()

            // Always clear service reference to prevent future use
            yggmailService = null

            // Update status based on cleanup result
            if (cleanupSuccessful) {
                lastError = null
                updateStatus(ServiceStatus.STOPPED)
            } else {
                lastError = buildString {
                    append("Service stopped with errors")
                    stopError?.let { append(". Stop: ${it.javaClass.simpleName}: ${it.message}") }
                    closeError?.let { append(". Close: ${it.javaClass.simpleName}: ${it.message}") }
                }
                updateStatus(ServiceStatus.ERROR)
            }

            // Remove foreground notification when stopped
            mainHandler.post {
                try {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                    } else {
                        @Suppress("DEPRECATION")
                        stopForeground(true)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error removing foreground notification", e)
                }
            }
        }
    }

    /**
     * Release WakeLock safely
     */
    private fun releaseWakeLock() {
        try {
            wakeLock?.let { lock ->
                if (lock.isHeld) {
                    lock.release()
                    Log.d(TAG, "WakeLock released")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing WakeLock", e)
        }
    }

    /**
     * Update native service with current power state for adaptive battery optimization.
     * This informs the yggmail library about device state so it can adjust:
     * - QUIC keep-alive intervals (60s on battery, 15s when charging, 5s when active)
     * - IMAP heartbeat intervals (3-29min on battery, more frequent when charging)
     */
    private fun updateNativeServicePowerState() {
        serviceHandler.post {
            try {
                yggmailService?.let { service ->
                    // Update active state (foreground vs background)
                    service.setActive(isAppActive && !isDozing)

                    // Update charging state
                    service.setCharging(isCharging)

                    Log.d(TAG, "[Battery] Power state updated - Active: ${isAppActive && !isDozing}, Charging: $isCharging, Dozing: $isDozing")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error updating power state", e)
            }
        }
    }

    /**
     * Acquire WakeLock for a critical operation only.
     * Battery optimization: WakeLock is NOT held continuously, only for specific operations.
     *
     * @param operation Name of the operation for logging
     * @param durationMs Maximum duration to hold the lock (default 30 seconds)
     */
    private fun acquireWakeLockForOperation(operation: String, durationMs: Long = 30_000) {
        try {
            wakeLock?.let { lock ->
                if (lock.isHeld) {
                    lock.release()
                }
                lock.acquire(durationMs)
                Log.d(TAG, "[Battery] WakeLock acquired for $operation (${durationMs}ms)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error acquiring WakeLock", e)
        }
    }

    /**
     * Notify that a message send operation started.
     * Acquires brief WakeLock for the operation.
     * Battery optimization: WakeLock only for 30 seconds, not continuously.
     */
    fun notifyMessageSendStarted() {
        activeSendOperations.incrementAndGet()
        lastSendActivity = System.currentTimeMillis()

        serviceHandler.post {
            acquireWakeLockForOperation("message_send", 30_000)
        }
    }

    /**
     * Notify that a message send operation completed.
     * WakeLock will automatically release after timeout.
     */
    fun notifyMessageSendCompleted() {
        activeSendOperations.decrementAndGet()
        Log.d(TAG, "[Battery] Message send completed, active operations: ${activeSendOperations.get()}")
    }

    /**
     * Update service status and notification
     */
    private fun updateStatus(status: ServiceStatus) {
        serviceStatus = status

        mainHandler.post {
            // Update notification
            val notification = createNotification(status)
            notificationManager.notify(NOTIFICATION_ID, notification)

            // Notify listeners
            statusListeners.forEach { it.onStatusChanged(status, lastError) }
        }
    }

    /**
     * Get connection status based on peer connections
     */
    private fun getConnectionStatus(): String {
        return when (serviceStatus) {
            ServiceStatus.STARTING -> getString(R.string.connection_connecting)
            ServiceStatus.STOPPING -> getString(R.string.service_stopping)
            ServiceStatus.STOPPED -> getString(R.string.connection_offline)
            ServiceStatus.ERROR -> lastError ?: getString(R.string.service_error)
            ServiceStatus.RUNNING -> {
                // Check if any peers are connected
                val connections = getPeerConnections()
                val hasConnectedPeer = connections?.any { it.up } == true
                if (hasConnectedPeer) {
                    getString(R.string.connection_online)
                } else {
                    getString(R.string.connection_offline)
                }
            }
        }
    }

    /**
     * Start periodic connection status check to update notification
     * Checks every 30 seconds if connection status has changed
     */
    private fun startConnectionStatusCheck() {
        stopConnectionStatusCheck() // Clear any existing checks

        connectionCheckRunnable = object : Runnable {
            override fun run() {
                try {
                    val currentStatus = getConnectionStatus()
                    if (currentStatus != lastConnectionStatus) {
                        lastConnectionStatus = currentStatus
                        // Update notification on main thread
                        mainHandler.post {
                            val notification = createNotification(serviceStatus)
                            notificationManager.notify(NOTIFICATION_ID, notification)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error checking connection status", e)
                }

                // Schedule next check in 30 seconds
                if (serviceStatus == ServiceStatus.RUNNING) {
                    mainHandler.postDelayed(this, 30_000)
                }
            }
        }

        // Start first check after 5 seconds (give time for connections to establish)
        mainHandler.postDelayed(connectionCheckRunnable!!, 5_000)
    }

    /**
     * Stop periodic connection status check
     */
    private fun stopConnectionStatusCheck() {
        connectionCheckRunnable?.let {
            mainHandler.removeCallbacks(it)
        }
        connectionCheckRunnable = null
        lastConnectionStatus = null
    }

    /**
     * Create notification for current service status
     * Optimized for low battery usage with PRIORITY_MIN
     * Shows connection status based on peer connections instead of service status
     */
    private fun createNotification(status: ServiceStatus): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val statusText = getConnectionStatus()

        return NotificationCompat.Builder(this, TyrApplication.CHANNEL_ID_SERVICE)
            .setContentTitle(statusText)
            .setSmallIcon(R.drawable.ic_notification_icon)
            .setContentIntent(pendingIntent)
            .setOngoing(status == ServiceStatus.RUNNING || status == ServiceStatus.STARTING)
            .setPriority(NotificationCompat.PRIORITY_MIN) // Optimized: was PRIORITY_LOW
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setShowWhen(false) // Hide timestamp for cleaner notification
            .build()
    }

    /**
     * LogCallback implementation for Yggmail logs
     * Only logs if log collection is enabled in settings
     */
    override fun onLog(level: String, tag: String, message: String) {
        // Check if log collection is enabled
        if (!configRepository.isLogCollectionEnabled()) {
            return
        }

        val logTag = "YggmailService"
        val logMessage = "[$tag] $message"

        when (level.uppercase()) {
            "ERROR", "E" -> Log.e(logTag, logMessage)
            "WARN", "W" -> Log.w(logTag, logMessage)
            "INFO", "I" -> Log.i(logTag, logMessage)
            "DEBUG", "D" -> Log.d(logTag, logMessage)
            "VERBOSE", "V" -> Log.v(logTag, logMessage)
            else -> Log.d(logTag, logMessage)
        }
    }

    /**
     * Add service status listener
     */
    fun addStatusListener(listener: ServiceStatusListener) {
        statusListeners.add(listener)
        // Immediately notify with current status
        listener.onStatusChanged(serviceStatus, lastError)
    }

    /**
     * Remove service status listener
     */
    fun removeStatusListener(listener: ServiceStatusListener) {
        statusListeners.remove(listener)
    }

    /**
     * Get current service status
     */
    fun getStatus(): ServiceStatus = serviceStatus

    /**
     * Get last error message
     */
    fun getLastError(): String? = lastError

    /**
     * Get peer connection information from native Yggmail service
     */
    fun getPeerConnections(): List<PeerConnectionInfo>? {
        return try {
            val jsonString = yggmailService?.getPeerConnectionsJSON() ?: return null
            parsePeerConnectionsJSON(jsonString)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting peer connections", e)
            null
        }
    }

    /**
     * Parse JSON string to list of PeerConnectionInfo
     */
    private fun parsePeerConnectionsJSON(json: String): List<PeerConnectionInfo> {
        if (json.isEmpty() || json == "[]") {
            return emptyList()
        }

        val peers = mutableListOf<PeerConnectionInfo>()
        try {
            // Simple JSON parsing without external library
            // New format: [{"uri":"tls://...","up":true,"inbound":false,"lastError":"","key":"...","uptime":120,"latencyMs":45,"rxBytes":1024,"txBytes":2048,"rxRate":10,"txRate":20},...]
            val jsonArray = json.trim().removeSurrounding("[", "]")
            if (jsonArray.isEmpty()) return emptyList()

            // Split by },{
            val peerObjects = jsonArray.split("},")
            for (peerStr in peerObjects) {
                var obj = peerStr.trim()
                if (!obj.startsWith("{")) obj = "{$obj"
                if (!obj.endsWith("}")) obj = "$obj}"

                // Extract fields from new format
                val uri = extractJSONString(obj, "uri")
                val up = extractJSONBoolean(obj, "up")
                val inbound = extractJSONBoolean(obj, "inbound")
                val lastError = extractJSONString(obj, "lastError")
                val key = extractJSONString(obj, "key")
                val uptime = extractJSONLong(obj, "uptime")
                val latencyMs = extractJSONLong(obj, "latencyMs")
                val rxBytes = extractJSONLong(obj, "rxBytes")
                val txBytes = extractJSONLong(obj, "txBytes")
                val rxRate = extractJSONLong(obj, "rxRate")
                val txRate = extractJSONLong(obj, "txRate")

                if (uri.isNotEmpty()) {
                    peers.add(PeerConnectionInfo(
                        uri = uri,
                        up = up,
                        inbound = inbound,
                        lastError = lastError,
                        key = key,
                        uptime = uptime,
                        latencyMs = latencyMs,
                        rxBytes = rxBytes,
                        txBytes = txBytes,
                        rxRate = rxRate,
                        txRate = txRate
                    ))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing peer connections JSON: $json", e)
        }
        return peers
    }

    private fun extractJSONString(json: String, key: String): String {
        val pattern = """"$key":"([^"]*)"""".toRegex()
        return pattern.find(json)?.groupValues?.get(1) ?: ""
    }

    private fun extractJSONInt(json: String, key: String): Int {
        val pattern = """"$key":(\d+)""".toRegex()
        return pattern.find(json)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    private fun extractJSONLong(json: String, key: String): Long {
        val pattern = """"$key":(\d+)""".toRegex()
        return pattern.find(json)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
    }

    private fun extractJSONBoolean(json: String, key: String): Boolean {
        val pattern = """"$key":(true|false)""".toRegex()
        return pattern.find(json)?.groupValues?.get(1) == "true"
    }

    /**
     * Data class for peer connection information
     */
    data class PeerConnectionInfo(
        val uri: String,           // Peer URI (e.g., "tls://1.2.3.4:7743")
        val up: Boolean,           // Connection is active
        val inbound: Boolean,      // True if peer initiated connection
        val lastError: String,     // Last error message (empty if no error)
        val key: String,           // Peer's public key (hex)
        val uptime: Long,          // Connection uptime in seconds
        val latencyMs: Long,       // Latency in milliseconds
        val rxBytes: Long,         // Received bytes
        val txBytes: Long,         // Transmitted bytes
        val rxRate: Long,          // Receive rate (bytes/sec)
        val txRate: Long           // Transmit rate (bytes/sec)
    ) {
        // Helper properties for backward compatibility
        val host: String
            get() = extractHostFromUri(uri)

        val port: Int
            get() = extractPortFromUri(uri)

        val connected: Boolean
            get() = up

        private fun extractHostFromUri(uri: String): String {
            return try {
                // Extract host from URI like "tls://1.2.3.4:7743" or "tcp://[::1]:7743"
                val withoutScheme = uri.substringAfter("://")
                if (withoutScheme.startsWith("[")) {
                    // IPv6 address
                    withoutScheme.substringAfter("[").substringBefore("]")
                } else {
                    // IPv4 address or hostname
                    withoutScheme.substringBefore(":")
                }
            } catch (e: Exception) {
                uri
            }
        }

        private fun extractPortFromUri(uri: String): Int {
            return try {
                uri.substringAfterLast(":").toIntOrNull() ?: 0
            } catch (e: Exception) {
                0
            }
        }
    }

    /**
     * Notify service that app is in foreground (active).
     * This triggers more responsive network intervals in the native library.
     * Battery optimization: Updates native library power state for adaptive behavior.
     */
    fun setAppActive(active: Boolean) {
        try {
            isAppActive = active
            Log.d(TAG, "[Battery] App activity state changed to: $active")

            // Update native service with new power state
            updateNativeServicePowerState()

        } catch (e: Exception) {
            Log.e(TAG, "Error setting app activity state", e)
        }
    }

    /**
     * Notify service about mail activity (sending/receiving)
     * This triggers aggressive mode for immediate delivery
     * Battery optimization: Update activity timestamp
     */
    fun notifyMailActivity() {
        try {
            lastSendActivity = System.currentTimeMillis()
            yggmailService?.recordMailActivity()
            Log.d(TAG, "Mail activity recorded")
        } catch (e: Exception) {
            Log.e(TAG, "Error recording mail activity", e)
        }
    }

    /**
     * Hot reload peers without restarting the entire service
     * Uses Yggdrasil Core's AddPeer/RemovePeer for live updates without reconnection
     */
    fun hotReloadPeers() {
        serviceHandler.post {
            try {
                Log.i(TAG, "Hot reloading peers...")

                // Get updated configuration
                val peers = configRepository.getPeersString()

                // Update peers using Yggdrasil Core's AddPeer/RemovePeer
                // This approach doesn't close the transport, avoiding ErrClosed errors
                yggmailService?.updatePeers(peers)

                Log.i(TAG, "Peers updated successfully using live configuration")

            } catch (e: Exception) {
                Log.e(TAG, "Error updating peers", e)
            }
        }
    }

    /**
     * Synchronous soft stop (must be called from service handler thread)
     */
    private fun performSoftStopSync() {
        try {
            Log.i(TAG, "Performing soft stop...")
            updateStatus(ServiceStatus.STOPPING)

            // First, gracefully disconnect all peers by updating to empty peer list
            // This uses Yggdrasil Core's RemovePeer for clean disconnection
            yggmailService?.updatePeers("")
            Log.i(TAG, "All peers disconnected gracefully")

            // Give a short delay for graceful disconnection to complete
            Thread.sleep(500)

            // Now perform normal stop
            stopYggmailSync()

        } catch (e: Exception) {
            Log.e(TAG, "Error during soft stop, falling back to normal stop", e)
            // Fallback to normal stop if soft stop fails
            stopYggmailSync()
        }
    }

    /**
     * Set peer discovery batching parameters
     * @param batchSize Number of peers to check in each batch
     * @param concurrency Number of concurrent checks
     * @param pauseMs Pause duration between batches in milliseconds
     */
    fun setPeerBatchingParams(batchSize: Int, concurrency: Int, pauseMs: Int) {
        serviceHandler.post {
            try {
                yggmailService?.setPeerBatchingParams(batchSize.toLong(), concurrency.toLong(), pauseMs.toLong())
                Log.d(TAG, "Peer batching params set: batchSize=$batchSize, concurrency=$concurrency, pauseMs=$pauseMs")
            } catch (e: Exception) {
                Log.e(TAG, "Error setting peer batching params", e)
            }
        }
    }

    /**
     * Find available peers asynchronously
     * @param protocols Comma-separated protocol list (e.g., "tcp,tls,quic")
     * @param region Region filter (empty for all regions)
     * @param maxRTTMs Maximum RTT in milliseconds
     * @param callback Callback for progress and results
     */
    fun findAvailablePeersAsync(
        protocols: String,
        region: String,
        maxRTTMs: Int,
        callback: mobile.PeerDiscoveryCallback
    ) {
        serviceHandler.post {
            try {
                yggmailService?.findAvailablePeersAsync(protocols, region, maxRTTMs.toLong(), callback)
                Log.d(TAG, "Peer discovery started: protocols=$protocols, region=$region, maxRTT=${maxRTTMs}ms")
            } catch (e: Exception) {
                Log.e(TAG, "Error starting peer discovery", e)
            }
        }
    }

    /**
     * Get available regions for peer filtering
     * @return JSON array of region names
     */
    fun getAvailableRegions(): String? {
        val latch = CountDownLatch(1)
        var result: String? = null
        var error: Exception? = null

        serviceHandler.post {
            try {
                result = yggmailService?.availableRegions
            } catch (e: Exception) {
                error = e
            } finally {
                latch.countDown()
            }
        }

        if (!latch.await(10, TimeUnit.SECONDS)) {
            Log.e(TAG, "Timeout getting available regions")
            return null
        }

        if (error != null) {
            Log.e(TAG, "Error getting available regions", error)
            return null
        }

        return result
    }

    /**
     * Soft stop: Gracefully disconnect peers before stopping the service
     * This method disconnects all peers cleanly to avoid ErrClosed errors in logs
     *
     * Unlike immediate stop, this approach:
     * - First disconnects all peers using updatePeers("") - empty peer list
     * - Gives time for graceful disconnection
     * - Then performs normal service shutdown
     * - Avoids ErrClosed errors in logs
     */
    fun softStop() {
        serviceHandler.post {
            performSoftStopSync()
        }
    }

    // ========== Quota Management ==========

    /**
     * Set maximum message size in megabytes
     * @param maxSizeMB Maximum message size in megabytes
     * @return true on success, false on error
     */
    fun setMaxMessageSizeMB(maxSizeMB: Long): Boolean {
        val latch = CountDownLatch(1)
        var success = false
        var error: Exception? = null

        serviceHandler.post {
            try {
                yggmailService?.setMaxMessageSizeMB(maxSizeMB)
                success = true
                Log.i(TAG, "Max message size set to ${maxSizeMB}MB")
            } catch (e: Exception) {
                error = e
                Log.e(TAG, "Error setting max message size", e)
            } finally {
                latch.countDown()
            }
        }

        if (!latch.await(5, TimeUnit.SECONDS)) {
            Log.e(TAG, "Timeout setting max message size")
            return false
        }

        return success && error == null
    }

    /**
     * Data class for message size limit information
     */
    data class MaxMessageSizeInfo(
        val maxSizeMB: Long  // Maximum message size limit in MB
    )

    /**
     * Get message size limit information
     * @return MaxMessageSizeInfo object, or null on error
     */
    fun getMaxMessageSizeInfo(): MaxMessageSizeInfo? {
        val latch = CountDownLatch(1)
        var result: MaxMessageSizeInfo? = null
        var error: Exception? = null

        serviceHandler.post {
            try {
                val info = yggmailService?.maxMessageSizeInfo
                if (info != null) {
                    result = MaxMessageSizeInfo(
                        maxSizeMB = info.maxSizeMB
                    )
                }
            } catch (e: Exception) {
                error = e
                Log.e(TAG, "Error getting max message size info", e)
            } finally {
                latch.countDown()
            }
        }

        if (!latch.await(5, TimeUnit.SECONDS)) {
            Log.e(TAG, "Timeout getting max message size info")
            return null
        }

        if (error != null) {
            return null
        }

        return result
    }

    // ========== Storage Statistics ==========

    /**
     * Data class for mail storage statistics
     */
    data class MailStorageStats(
        val dbSizeMB: Double,     // Database BLOB size in MB
        val fileSizeMB: Double,   // File storage size in MB
        val totalSizeMB: Double   // Total storage size in MB
    )

    /**
     * Get mail storage statistics
     * @return MailStorageStats object, or null on error
     */
    fun getMailStorageStats(): MailStorageStats? {
        val latch = CountDownLatch(1)
        var result: MailStorageStats? = null
        var error: Exception? = null

        serviceHandler.post {
            try {
                val stats = yggmailService?.mailStorageStats
                if (stats != null) {
                    // Convert bytes to MB using auto-generated getter methods
                    // Gomobile converts DbSize -> getDbSize(), FileSize -> getFileSize()
                    val dbSizeMB = stats.dbSize / (1024.0 * 1024.0)
                    val fileSizeMB = stats.fileSize / (1024.0 * 1024.0)
                    val totalSizeMB = dbSizeMB + fileSizeMB

                    result = MailStorageStats(
                        dbSizeMB = dbSizeMB,
                        fileSizeMB = fileSizeMB,
                        totalSizeMB = totalSizeMB
                    )
                }
            } catch (e: Exception) {
                error = e
                Log.e(TAG, "Error getting mail storage stats", e)
            } finally {
                latch.countDown()
            }
        }

        if (!latch.await(5, TimeUnit.SECONDS)) {
            Log.e(TAG, "Timeout getting mail storage stats")
            return null
        }

        if (error != null) {
            return null
        }

        return result
    }

}

/**
 * Service status enum
 */
enum class ServiceStatus {
    STARTING,
    RUNNING,
    STOPPING,
    STOPPED,
    ERROR
}

/**
 * Interface for listening to service status changes
 */
interface ServiceStatusListener {
    fun onStatusChanged(status: ServiceStatus, error: String?)
}
