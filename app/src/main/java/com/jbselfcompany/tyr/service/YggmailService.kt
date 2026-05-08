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
import com.jbselfcompany.tyr.utils.TyrLogger
import androidx.core.app.NotificationCompat
import com.jbselfcompany.tyr.R
import com.jbselfcompany.tyr.TyrApplication
import com.jbselfcompany.tyr.data.PeerInfo
import com.jbselfcompany.tyr.receiver.MaintenanceReceiver
import com.jbselfcompany.tyr.ui.MainActivity
import com.jbselfcompany.tyr.chat.data.ChatRepository
import com.jbselfcompany.tyr.chat.network.ImapFetcher
import com.jbselfcompany.tyr.chat.network.SmtpSender
import mobile.LogCallback
import mobile.YggmailService as MobileYggmailService
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that runs Yggmail server.
 * Manages lifecycle of Yggmail service and provides status updates.
 *
 * Battery optimization: Uses timed WakeLock with periodic renewal
 * to balance connectivity and power consumption.
 */
class YggmailService : Service(), LogCallback, mobile.MailCallback {

    companion object {
        private const val TAG = "YggmailService"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.jbselfcompany.tyr.START"
        const val ACTION_STOP = "com.jbselfcompany.tyr.STOP"
        const val ACTION_SOFT_STOP = "com.jbselfcompany.tyr.SOFT_STOP"
        const val ACTION_RECONNECT_PEERS = "com.jbselfcompany.tyr.RECONNECT_PEERS"
        const val ACTION_MAINTENANCE_CHECK = "com.jbselfcompany.tyr.MAINTENANCE_CHECK"
        const val ACTION_NEW_CHAT_MESSAGES = "com.jbselfcompany.tyr.NEW_CHAT_MESSAGES"
        private const val CHAT_POLL_INTERVAL_MS = 2 * 60 * 1000L // 2 minutes
        private const val CHAT_NOTIFICATION_BASE_ID = 2000
        private const val STARTUP_GRACE_PERIOD_MS = 10_000L // ignore reconnect requests for 10s after start

        /**
         * Check if service is currently running
         */
        @Volatile
        var isRunning = false
            private set

        /**
         * Running service instance for direct API access (e.g., sendChatMessage).
         * Null when service is not running.
         */
        @Volatile
        var instance: YggmailService? = null
            private set

        /**
         * Start the Yggmail service
         */
        fun start(context: Context) {
            val intent = Intent(context, YggmailService::class.java).apply {
                action = ACTION_START
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                // Android 12+ may throw ForegroundServiceStartNotAllowedException
                // if app is in background or doesn't meet other foreground service requirements
                TyrLogger.e(TAG,"Failed to start foreground service", e)
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
         * Cancel the chat notification for a specific sender.
         * Call this when the user opens a conversation to dismiss the notification.
         */
        fun cancelChatNotificationForSender(context: Context, senderAddress: String) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(CHAT_NOTIFICATION_BASE_ID + senderAddress.hashCode())
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

    // Coroutine scope for I/O-bound operations (IMAP polling, DB access)
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Shared ChatRepository instance — created once, reuses the same SQLite connection
    private val chatRepository by lazy { ChatRepository(this) }

    // Service state
    @Volatile private var serviceStartTime = 0L
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
    private val statusListeners = java.util.concurrent.CopyOnWriteArrayList<ServiceStatusListener>()

    // Connection status tracking
    private var lastConnectionStatus: String? = null
    private var connectionCheckRunnable: Runnable? = null

    // Background chat polling (initialized on serviceThread in onCreate)
    private lateinit var chatPollHandler: Handler
    private var chatPollRunnable: Runnable? = null

    // Battery optimization state
    // Start as active so initial peer connections use 5s QUIC keepalive.
    // Doze Mode receiver will switch to inactive when the device enters Doze.
    // onPause() deliberately does NOT call setAppActive(false) — see MainActivity.
    private var isAppActive = true // Track if app is in foreground
    private var isCharging = false // Track if device is charging
    private var isDozing = false // Track if device is in Doze Mode

    // Battery optimization: Track active send operations
    private var activeSendOperations = java.util.concurrent.atomic.AtomicInteger(0)
    private var lastSendActivity = System.currentTimeMillis()

    // Guard against concurrent IMAP poll runs (e.g. timer + onNewMail firing together)
    private val isFetchInProgress = java.util.concurrent.atomic.AtomicBoolean(false)

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
                        TyrLogger.d(TAG,"[Battery] Doze mode changed: $isDozing")
                        updateNativeServicePowerState()
                        // When exiting Doze with app active, QUIC connections were closed
                        // during Doze (60s keepAlive → 5s keepAlive transition). Force
                        // immediate peer reconnection instead of waiting for yggdrasil's
                        // internal reconnect backoff timer.
                        if (!isDozing && isAppActive) {
                            TyrLogger.d(TAG,"[Reconnect] Exiting Doze, forcing immediate peer reconnection")
                            hotReloadPeers()
                        }
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
                    TyrLogger.d(TAG,"[Battery] Device charging started")
                    updateNativeServicePowerState()
                }
                Intent.ACTION_POWER_DISCONNECTED -> {
                    isCharging = false
                    TyrLogger.d(TAG,"[Battery] Device on battery")
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
        TyrLogger.d(TAG,"Service onCreate")
        instance = this

        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Create service thread
        serviceThread = HandlerThread("YggmailServiceThread").apply { start() }
        serviceHandler = Handler(serviceThread.looper)
        // Use the same background thread for chat polling to avoid tying up main looper
        chatPollHandler = Handler(serviceThread.looper)

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
            TyrLogger.d(TAG,"[Battery] Doze Mode receiver registered")
        }

        // Register battery charging receiver
        val batteryFilter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        registerReceiver(batteryReceiver, batteryFilter)
        TyrLogger.d(TAG,"[Battery] Battery receiver registered")

        // Check initial charging state
        val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        batteryIntent?.let {
            val status = it.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL
            TyrLogger.d(TAG,"[Battery] Initial charging state: $isCharging")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        TyrLogger.d(TAG,"Service onStartCommand: ${intent?.action}")

        when (intent?.action) {
            ACTION_START -> {
                if (!isRunning) {
                    startForegroundWithNotification()
                    startYggmail()
                } else {
                    TyrLogger.w(TAG,"Service already running, ignoring START action")
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
            ACTION_RECONNECT_PEERS -> {
                // Triggered by network change (WiFi <-> Mobile switch)
                if (isRunning) {
                    val timeSinceStart = System.currentTimeMillis() - serviceStartTime
                    if (serviceStartTime > 0 && timeSinceStart < STARTUP_GRACE_PERIOD_MS) {
                        TyrLogger.d(TAG,"Ignoring reconnect request during startup grace period (${timeSinceStart}ms since start)")
                    } else {
                        TyrLogger.i(TAG,"Network changed - reconnecting peers")
                        hotReloadPeers()
                    }
                }
            }
            ACTION_MAINTENANCE_CHECK -> {
                // Triggered by MaintenanceReceiver — check peers and reconnect if needed
                if (isRunning) {
                    val timeSinceStart = System.currentTimeMillis() - serviceStartTime
                    if (serviceStartTime > 0 && timeSinceStart < STARTUP_GRACE_PERIOD_MS) {
                        TyrLogger.d(TAG,"Ignoring maintenance check during startup grace period (${timeSinceStart}ms since start)")
                    } else {
                        serviceHandler.post {
                            try {
                                val connections = getPeerConnections()
                                val hasConnectedPeer = connections?.any { it.up } == true
                                if (!hasConnectedPeer) {
                                    TyrLogger.w(TAG,"Maintenance: no connected peers — triggering reconnection")
                                    hotReloadPeers()
                                } else {
                                    TyrLogger.d(TAG,"Maintenance: peers OK (${connections?.count { it.up }} connected)")
                                }
                            } catch (e: Exception) {
                                TyrLogger.e(TAG,"Error during maintenance check", e)
                            }
                        }
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
        TyrLogger.d(TAG,"Service onDestroy")
        instance = null

        // Cancel maintenance scheduling
        MaintenanceReceiver.cancelMaintenance(this)

        // Unregister receivers
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                unregisterReceiver(dozeReceiver)
                TyrLogger.d(TAG,"[Battery] Doze Mode receiver unregistered")
            }
        } catch (e: Exception) {
            TyrLogger.e(TAG,"Error unregistering doze receiver", e)
        }

        try {
            unregisterReceiver(batteryReceiver)
            TyrLogger.d(TAG,"[Battery] Battery receiver unregistered")
        } catch (e: Exception) {
            TyrLogger.e(TAG,"Error unregistering battery receiver", e)
        }

        // Only stop if not already stopped (prevent duplicate stop calls)
        if (isRunning) {
            TyrLogger.w(TAG,"Service destroyed while still running - forcing cleanup")
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
                TyrLogger.e(TAG,"Interrupted while waiting for service cleanup", e)
            }
        }

        // Now safe to quit the thread
        serviceThread.quitSafely()
        try {
            // Wait for thread to actually terminate (max 2 seconds)
            serviceThread.join(2000)
        } catch (e: InterruptedException) {
            TyrLogger.e(TAG,"Interrupted while waiting for service thread termination", e)
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

        // Cancel all coroutines launched in this service
        serviceScope.cancel()

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
            TyrLogger.e(TAG,"Failed to start foreground with notification", e)
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
            TyrLogger.i(TAG,"Starting Yggmail service...")
            updateStatus(ServiceStatus.STARTING)

            // Get configuration
            val password = configRepository.getPassword()
            if (password.isNullOrEmpty()) {
                throw IllegalStateException("Password not configured")
            }

            val peers = configRepository.getPeersString()

            TyrLogger.d(TAG,"Peers: '$peers'")

            // Database path
            val dbPath = File(filesDir, "yggmail.db").absolutePath
            TyrLogger.d(TAG,"Database path: $dbPath")

            // SMTP and IMAP addresses (localhost only, for DeltaChat)
            val smtpAddr = "127.0.0.1:1025"
            val imapAddr = "127.0.0.1:1143"

            // Create Yggmail service
            // Always set LogCallback, but onLog() will check if logging is enabled
            yggmailService = mobile.Mobile.newYggmailService(dbPath, smtpAddr, imapAddr).apply {
                setLogCallback(this@YggmailService)
                // Register mail callback so the Go layer can notify us immediately
                // when a new TyrChat (or INBOX) message arrives, instead of waiting
                // for the next 2-minute poll cycle.
                setMailCallback(this@YggmailService)
            }

            // Initialize (creates/loads keys)
            yggmailService?.initialize()
            TyrLogger.d(TAG,"Yggmail initialized")

            // Set password
            yggmailService?.setPassword(password)
            TyrLogger.d(TAG,"Password configured")

            // Save mail address for display
            val mailAddress = yggmailService?.getMailAddress() ?: ""
            val publicKey = yggmailService?.getPublicKey() ?: ""
            configRepository.saveMailAddress(mailAddress)
            configRepository.savePublicKey(publicKey)
            TyrLogger.i(TAG,"Mail address: $mailAddress")

            // Start with configured peers
            yggmailService?.start(peers)
            TyrLogger.i(TAG,"Yggmail service started successfully")

            // Schedule periodic maintenance using AlarmManager (Doze-compatible)
            MaintenanceReceiver.scheduleMaintenance(this@YggmailService)
            TyrLogger.d(TAG,"[Battery] Maintenance scheduling started")

            // Update native service with current power state
            updateNativeServicePowerState()

            isRunning = true
            serviceStartTime = System.currentTimeMillis()
            updateStatus(ServiceStatus.RUNNING)

            // Start periodic connection status check for notification updates
            startConnectionStatusCheck()
            // Start background IMAP polling for chat notifications
            startChatPolling()

        } catch (e: Exception) {
            TyrLogger.e(TAG,"Failed to start Yggmail service", e)
            lastError = e.message
            updateStatus(ServiceStatus.ERROR)
            mainHandler.post {
                stopSelf()
            }
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
        // Stop background chat polling
        stopChatPolling()

        // Prevent concurrent stop operations
        synchronized(this) {
            if (!isRunning) {
                TyrLogger.w(TAG,"Service already stopped, ignoring stop request")
                return
            }

            // Mark as stopping immediately to prevent new operations
            isRunning = false
            serviceStartTime = 0L
            updateStatus(ServiceStatus.STOPPING)
            TyrLogger.i(TAG,"Stopping Yggmail service...")
        }

        // Track if cleanup was successful
        var cleanupSuccessful = false
        var stopError: Throwable? = null
        var closeError: Throwable? = null

        try {
            // Acquire WakeLock for shutdown process to prevent interruption
            // This is critical to ensure complete cleanup
            acquireWakeLockForOperation("shutdown", 20_000)

            // Step 1: Stop the service (closes network connections)
            // Wrap in try-catch for ALL throwables (including native crashes/panics)
            try {
                yggmailService?.let { service ->
                    TyrLogger.d(TAG,"Calling native stop()...")

                    // Call stop() in a timeout-protected block
                    val stopLatch = CountDownLatch(1)
                    var stopResult: Throwable? = null

                    val stopThread = Thread {
                        try {
                            service.stop()
                            TyrLogger.d(TAG,"Native stop() completed successfully")
                        } catch (t: Throwable) {
                            stopResult = t
                            TyrLogger.e(TAG,"Exception during native stop()", t)
                        } finally {
                            stopLatch.countDown()
                        }
                    }

                    stopThread.name = "YggmailStopThread"
                    stopThread.start()

                    // Wait with timeout
                    if (!stopLatch.await(10, TimeUnit.SECONDS)) {
                        TyrLogger.e(TAG,"Native stop() timed out after 10 seconds")
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
                TyrLogger.e(TAG,"Critical error calling native stop()", t)
                // Continue with close() even if stop() failed critically
            }

            // Step 2: Close the service (releases all resources)
            // Only attempt close if we still have a valid reference
            try {
                yggmailService?.let { service ->
                    TyrLogger.d(TAG,"Calling native close()...")

                    // Call close() in a timeout-protected block
                    val closeLatch = CountDownLatch(1)
                    var closeResult: Throwable? = null

                    val closeThread = Thread {
                        try {
                            service.close()
                            TyrLogger.d(TAG,"Native close() completed successfully")
                        } catch (t: Throwable) {
                            closeResult = t
                            TyrLogger.e(TAG,"Exception during native close()", t)
                        } finally {
                            closeLatch.countDown()
                        }
                    }

                    closeThread.name = "YggmailCloseThread"
                    closeThread.start()

                    // Wait with timeout
                    if (!closeLatch.await(5, TimeUnit.SECONDS)) {
                        TyrLogger.e(TAG,"Native close() timed out after 5 seconds")
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
                TyrLogger.e(TAG,"Critical error calling native close()", t)
                // Continue cleanup even if close() failed critically
            }

            // Step 3: Clear reference to native service
            yggmailService = null

            // Step 4: Wait for ports to be fully released
            // TCP sockets may remain in TIME_WAIT state
            TyrLogger.d(TAG,"Waiting for port release...")
            Thread.sleep(1000)

            cleanupSuccessful = (stopError == null && closeError == null)
            TyrLogger.i(TAG,"Yggmail service stopped (cleanup ${if (cleanupSuccessful) "successful" else "with errors"})")

        } catch (t: Throwable) {
            // Final catch-all for any unexpected issues
            TyrLogger.e(TAG,"Unexpected critical error during service shutdown", t)
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
                    TyrLogger.e(TAG,"Error removing foreground notification", e)
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
                    TyrLogger.d(TAG,"WakeLock released")
                }
            }
        } catch (e: Exception) {
            TyrLogger.e(TAG,"Error releasing WakeLock", e)
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

                    TyrLogger.d(TAG,"[Battery] Power state updated - Active: ${isAppActive && !isDozing}, Charging: $isCharging, Dozing: $isDozing")
                }
            } catch (e: Exception) {
                TyrLogger.e(TAG,"Error updating power state", e)
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
                TyrLogger.d(TAG,"[Battery] WakeLock acquired for $operation (${durationMs}ms)")
            }
        } catch (e: Exception) {
            TyrLogger.e(TAG,"Error acquiring WakeLock", e)
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
        TyrLogger.d(TAG,"[Battery] Message send completed, active operations: ${activeSendOperations.get()}")
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
     * Get connection status based on peer connections.
     * When running, returns the number of connected peers for display in the notification.
     */
    private fun getConnectionStatus(): String {
        return when (serviceStatus) {
            ServiceStatus.STARTING -> getString(R.string.connection_connecting)
            ServiceStatus.STOPPING -> getString(R.string.service_stopping)
            ServiceStatus.STOPPED -> getString(R.string.connection_offline)
            ServiceStatus.ERROR -> lastError ?: getString(R.string.service_error)
            ServiceStatus.RUNNING -> {
                val connections = getPeerConnections()
                val connected = connections?.filter { it.up } ?: emptyList()
                val connectedCount = connected.size
                if (connectedCount > 0) {
                    val avgLatency = connected.map { it.latencyMs }.filter { it > 0 }.let {
                        if (it.isNotEmpty()) it.average().toLong() else 0L
                    }
                    if (avgLatency > 0) {
                        getString(R.string.notification_peers_connected_ping, connectedCount, avgLatency)
                    } else {
                        getString(R.string.notification_peers_connected, connectedCount)
                    }
                } else {
                    getString(R.string.notification_no_connection)
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
                    TyrLogger.e(TAG,"Error checking connection status", e)
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
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_notification_icon)
            .setContentIntent(pendingIntent)
            .setOngoing(status == ServiceStatus.RUNNING || status == ServiceStatus.STARTING)
            .setPriority(NotificationCompat.PRIORITY_MIN) // Optimized: was PRIORITY_LOW
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setShowWhen(false) // Hide timestamp for cleaner notification
            .build()
    }

    /**
     * LogCallback implementation for Yggmail logs.
     * ERROR-level Go logs always appear; others respect the TyrLogger enabled flag.
     */
    override fun onLog(level: String, tag: String, message: String) {
        val logTag = "YggmailService"
        val logMessage = "[$tag] $message"

        when (level.uppercase()) {
            "ERROR", "E" -> TyrLogger.e(logTag, logMessage)
            "WARN", "W" -> TyrLogger.w(logTag, logMessage)
            "INFO", "I" -> TyrLogger.i(logTag, logMessage)
            "DEBUG", "D" -> TyrLogger.d(logTag, logMessage)
            "VERBOSE", "V" -> TyrLogger.d(logTag, logMessage)
            else -> TyrLogger.d(logTag, logMessage)
        }
    }

    // ---- mobile.MailCallback implementation ----

    /**
     * Called by the Go layer (session_remote.go) immediately after a message is
     * stored in any mailbox — including TyrChat.  We use this to kick the poll
     * cycle right away instead of waiting up to 2 minutes for the timer.
     */
    override fun onNewMail(mailbox: String, from: String, subject: String, mailID: Long) {
        TyrLogger.i(TAG, "onNewMail: mailbox=$mailbox from=$from mailID=$mailID — triggering immediate poll")
        // Remove any pending poll and post an immediate one. Do NOT manually re-arm
        // chatPollRunnable here — it re-arms itself via postDelayed at the end of its
        // own run(), so posting it again would cause double-polling.
        chatPollRunnable?.let { chatPollHandler.removeCallbacks(it) }
        chatPollHandler.post {
            if (isRunning) {
                pollInboxForNewMessages()
                // Re-arm the regular interval from this point so the timer resets to
                // now (avoids an extra poll firing immediately after the one above).
                chatPollRunnable?.let { chatPollHandler.postDelayed(it, CHAT_POLL_INTERVAL_MS) }
            }
        }
    }

    override fun onMailSent(to: String, subject: String) {
        TyrLogger.d(TAG, "onMailSent: to=$to")
    }

    override fun onMailError(to: String, subject: String, errorMsg: String) {
        TyrLogger.w(TAG, "onMailError: to=$to error=$errorMsg")
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
            TyrLogger.e(TAG,"Error getting peer connections", e)
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
            TyrLogger.e(TAG,"Error parsing peer connections JSON: $json", e)
        }
        return peers
    }

    private fun extractJSONString(json: String, key: String): String {
        val pattern = """"$key":"([^"]*)"""".toRegex()
        return pattern.find(json)?.groupValues?.get(1) ?: ""
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
            val wasEffectivelyActive = isAppActive && !isDozing
            isAppActive = active
            TyrLogger.d(TAG,"[Battery] App activity state changed to: $active")

            // Update native service with new power state
            updateNativeServicePowerState()

            // When becoming effectively active (e.g. app opened after Doze exit),
            // QUIC keepAlive changed (60s→5s), closing existing connections.
            // Force immediate peer reconnection instead of waiting for internal backoff.
            val isNowEffectivelyActive = isAppActive && !isDozing
            if (isNowEffectivelyActive && !wasEffectivelyActive) {
                TyrLogger.d(TAG,"[Reconnect] Became active, forcing immediate peer reconnection")
                hotReloadPeers()
            }

        } catch (e: Exception) {
            TyrLogger.e(TAG,"Error setting app activity state", e)
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
            TyrLogger.d(TAG,"Mail activity recorded")
        } catch (e: Exception) {
            TyrLogger.e(TAG,"Error recording mail activity", e)
        }
    }

    /**
     * Attempt to retry sending all queued messages for [destination].
     * Delegates to Go layer: resets backoff counters and triggers immediate
     * queue processing for this peer. [messageId] is informational only —
     * the Go queue processes all pending messages per destination together.
     * Returns true if the destination address is valid and retry was triggered.
     */
    fun retrySendMessage(destination: String, messageId: Int): Boolean {
        if (!isRunning) return false
        val latch = java.util.concurrent.CountDownLatch(1)
        var result = false
        serviceHandler.post {
            try {
                result = yggmailService?.flushQueueForDestination(destination) ?: false
            } catch (e: Exception) {
                TyrLogger.e(TAG,"retrySendMessage failed for $destination", e)
            } finally {
                latch.countDown()
            }
        }
        latch.await(5, TimeUnit.SECONDS)
        TyrLogger.d(TAG,"retrySendMessage: dest=$destination id=$messageId triggered=$result")
        return result
    }

    fun sendChatMessage(from: String, to: String, body: String, readReceiptUid: Long): String? {
        val latch = java.util.concurrent.CountDownLatch(1)
        var error: String? = null
        serviceHandler.post {
            try {
                yggmailService?.sendChatMessage(from, to, body, readReceiptUid)
            } catch (e: Exception) {
                error = e.message ?: "Unknown error"
            } finally {
                latch.countDown()
            }
        }
        latch.await(10, TimeUnit.SECONDS)
        return error
    }

    /**
     * Hot reload peers without restarting the entire service
     * Uses Yggdrasil Core's AddPeer/RemovePeer for live updates without reconnection
     */
    fun hotReloadPeers() {
        serviceHandler.post {
            try {
                TyrLogger.i(TAG,"Hot reloading peers...")

                // Get updated configuration
                val peers = configRepository.getPeersString()

                // Update peers using Yggdrasil Core's AddPeer/RemovePeer
                // This approach doesn't close the transport, avoiding ErrClosed errors
                yggmailService?.updatePeers(peers)

                TyrLogger.i(TAG,"Peers updated successfully using live configuration")

            } catch (e: Exception) {
                TyrLogger.e(TAG,"Error updating peers", e)
            }
        }
    }

    /**
     * Hot reload password without restarting the entire service
     */
    fun hotReloadPassword() {
        serviceHandler.post {
            try {
                TyrLogger.i(TAG, "Hot reloading password...")
                val password = configRepository.getPassword() ?: return@post
                yggmailService?.setPassword(password)
                TyrLogger.i(TAG, "Password updated successfully in running service")
            } catch (e: Exception) {
                TyrLogger.e(TAG, "Error updating password", e)
            }
        }
    }

    /**
     * Synchronous soft stop (must be called from service handler thread)
     */
    private fun performSoftStopSync() {
        try {
            TyrLogger.i(TAG,"Performing soft stop...")
            updateStatus(ServiceStatus.STOPPING)

            // First, gracefully disconnect all peers by updating to empty peer list
            // This uses Yggdrasil Core's RemovePeer for clean disconnection
            yggmailService?.updatePeers("")
            TyrLogger.i(TAG,"All peers disconnected gracefully")

            // Give a short delay for graceful disconnection to complete
            Thread.sleep(500)

            // Now perform normal stop
            stopYggmailSync()

        } catch (e: Exception) {
            TyrLogger.e(TAG,"Error during soft stop, falling back to normal stop", e)
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
                TyrLogger.d(TAG,"Peer batching params set: batchSize=$batchSize, concurrency=$concurrency, pauseMs=$pauseMs")
            } catch (e: Exception) {
                TyrLogger.e(TAG,"Error setting peer batching params", e)
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
                TyrLogger.d(TAG,"Peer discovery started: protocols=$protocols, region=$region, maxRTT=${maxRTTMs}ms")
            } catch (e: Exception) {
                TyrLogger.e(TAG,"Error starting peer discovery", e)
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
            TyrLogger.e(TAG,"Timeout getting available regions")
            return null
        }

        if (error != null) {
            TyrLogger.e(TAG,"Error getting available regions", error)
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
                TyrLogger.i(TAG,"Max message size set to ${maxSizeMB}MB")
            } catch (e: Exception) {
                error = e
                TyrLogger.e(TAG,"Error setting max message size", e)
            } finally {
                latch.countDown()
            }
        }

        if (!latch.await(5, TimeUnit.SECONDS)) {
            TyrLogger.e(TAG,"Timeout setting max message size")
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
                TyrLogger.e(TAG,"Error getting max message size info", e)
            } finally {
                latch.countDown()
            }
        }

        if (!latch.await(15, TimeUnit.SECONDS)) {
            TyrLogger.e(TAG,"Timeout getting max message size info")
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
                TyrLogger.e(TAG,"Error getting mail storage stats", e)
            } finally {
                latch.countDown()
            }
        }

        if (!latch.await(15, TimeUnit.SECONDS)) {
            TyrLogger.e(TAG,"Timeout getting mail storage stats")
            return null
        }

        if (error != null) {
            return null
        }

        return result
    }

    /**
     * Get count of messages in the outbound send queue.
     * Returns -1 if service is unavailable.
     */
    fun getOutboundQueueCount(): Int {
        val latch = CountDownLatch(1)
        var result = -1

        serviceHandler.post {
            try {
                result = yggmailService?.outboundQueueCount?.toInt() ?: -1
            } catch (e: Exception) {
                TyrLogger.e(TAG,"Error getting outbound queue count", e)
            } finally {
                latch.countDown()
            }
        }

        if (!latch.await(5, TimeUnit.SECONDS)) {
            TyrLogger.e(TAG,"Timeout getting outbound queue count")
            return -1
        }
        return result
    }

    /**
     * Clear all entries from the outbound send queue.
     * Returns the number of entries removed, or -1 on error.
     */
    fun clearOutboundQueue(): Int {
        val latch = CountDownLatch(1)
        var result = -1

        serviceHandler.post {
            try {
                result = yggmailService?.clearOutboundQueue()?.toInt() ?: -1
            } catch (e: Exception) {
                TyrLogger.e(TAG,"Error clearing outbound queue", e)
            } finally {
                latch.countDown()
            }
        }

        if (!latch.await(10, TimeUnit.SECONDS)) {
            TyrLogger.e(TAG,"Timeout clearing outbound queue")
            return -1
        }
        return result
    }

    // ---- Background chat polling ----

    private fun startChatPolling() {
        stopChatPolling()
        chatPollRunnable = object : Runnable {
            override fun run() {
                if (isRunning) {
                    pollInboxForNewMessages()
                    chatPollHandler.postDelayed(this, CHAT_POLL_INTERVAL_MS)
                }
            }
        }
        // First poll after grace period so service has time to connect peers,
        // subsequent polls use full interval
        chatPollHandler.postDelayed(chatPollRunnable!!, STARTUP_GRACE_PERIOD_MS)
    }

    private fun stopChatPolling() {
        if (!::chatPollHandler.isInitialized) return
        chatPollRunnable?.let { chatPollHandler.removeCallbacks(it) }
        chatPollRunnable = null
    }

    private fun pollInboxForNewMessages() {
        if (!isFetchInProgress.compareAndSet(false, true)) {
            TyrLogger.i(TAG, "Chat poll: fetch already in progress, skipping")
            return
        }
        val address = configRepository.getMailAddress() ?: run { isFetchInProgress.set(false); return }
        val password = configRepository.getPassword() ?: run { isFetchInProgress.set(false); return }

        serviceScope.launch {
            try {
                // Use the watermark (max of DB-derived UID and the persisted high-water mark).
                // The persisted value never decreases even when messages/contacts are deleted,
                // so removing a contact cannot cause its old IMAP messages to be re-fetched.
                val dbMaxUid = chatRepository.getMaxImapUid()
                val watermark = configRepository.getLastSeenImapUid()
                val sinceUid = maxOf(dbMaxUid, watermark)
                TyrLogger.i(TAG, "Chat poll: starting fetch sinceUid=$sinceUid (db=$dbMaxUid watermark=$watermark) address=$address")
                val attachmentsDir = File(
                    getExternalFilesDir(null) ?: filesDir, "attachments"
                ).also { it.mkdirs() }
                val result = ImapFetcher(cacheDir = cacheDir).fetchNewMessages(address, password, sinceUid, attachmentsDir)
                when (result) {
                    is ImapFetcher.Result.Error -> {
                        TyrLogger.e(TAG, "Chat poll: IMAP fetch error: ${result.message}")
                    }
                    is ImapFetcher.Result.Success -> {
                        TyrLogger.i(TAG, "Chat poll: fetch returned ${result.data.messages.size} messages, " +
                            "${result.data.deliveryReceiptTimestamps.size} delivery receipts, " +
                            "${result.data.readReceiptTimestamps.size} read receipts")

                        // Advance the watermark to the highest UID seen in this batch so that
                        // deleting contacts/messages later cannot roll back the fetch position.
                        val maxFetchedUid = result.data.messages.maxOfOrNull { it.imapUid } ?: -1L
                        if (maxFetchedUid > 0) configRepository.advanceLastSeenImapUid(maxFetchedUid)

                        val newMessages = mutableListOf<com.jbselfcompany.tyr.chat.data.ChatMessage>()
                        val acceptNonContacts = configRepository.getAcceptMessagesFromNonContacts()
                        val nicknameMap = result.data.nicknameUpdates.associate { it.senderAddr to it.nickname }

                        // Insert incoming messages we haven't seen yet
                        for (msg in result.data.messages) {
                            TyrLogger.d(TAG, "Chat poll: processing msg uid=${msg.imapUid} " +
                                "from=${msg.fromAddr} isSent=${msg.isSent} " +
                                "hasAttachment=${msg.hasAttachment} attachMime=${msg.attachmentMimeType} " +
                                "attachSize=${msg.attachmentSizeBytes} attachPath=${msg.attachmentPath}")
                            if (msg.isSent) {
                                TyrLogger.d(TAG, "Chat poll: skipping own sent msg uid=${msg.imapUid}")
                                continue
                            }
                            if (chatRepository.imapUidExists(msg.imapUid)) {
                                TyrLogger.d(TAG, "Chat poll: uid=${msg.imapUid} already in DB, skipping")
                                continue
                            }
                            if (chatRepository.isContactDeclined(msg.fromAddr)) {
                                TyrLogger.d(TAG, "Chat poll: from=${msg.fromAddr} is declined, skipping")
                                continue
                            }
                            when {
                                chatRepository.contactExists(msg.fromAddr) -> {
                                    TyrLogger.i(TAG, "Chat poll: inserting msg uid=${msg.imapUid} " +
                                        "from=${msg.fromAddr} hasAttachment=${msg.hasAttachment}")
                                    chatRepository.insertMessage(msg)
                                    newMessages.add(msg)
                                }
                                acceptNonContacts -> {
                                    // Store as pending contact with nickname if available
                                    TyrLogger.i(TAG, "Chat poll: new contact from=${msg.fromAddr}, " +
                                        "adding as pending and inserting msg uid=${msg.imapUid}")
                                    chatRepository.addPendingContact(
                                        com.jbselfcompany.tyr.chat.data.ChatContact(
                                            address = msg.fromAddr, name = nicknameMap[msg.fromAddr] ?: ""
                                        )
                                    )
                                    chatRepository.insertMessage(msg.copy(isRead = false))
                                    newMessages.add(msg)
                                }
                                else -> {
                                    // Sender not in contacts and acceptNonContacts=false.
                                    // Message is intentionally dropped per user settings.
                                    TyrLogger.w(TAG, "Chat poll: DROPPED msg uid=${msg.imapUid} " +
                                        "from=${msg.fromAddr} — sender not in contacts and " +
                                        "acceptNonContacts=false. Go to Contacts and add this address, " +
                                        "or enable 'Accept messages from unknown senders' in Settings.")
                                }
                            }
                        }

                        // Apply nickname updates to known contacts with blank names
                        for ((senderAddr, nickname) in nicknameMap) {
                            val contact = chatRepository.getContact(senderAddr)
                            if (contact != null && contact.name.isBlank()) {
                                chatRepository.updateContactName(senderAddr, nickname)
                            }
                        }

                        // Delivery receipts → single checkmark (only if still SENDING)
                        var deliveryReceiptsApplied = false
                        for (receipt in result.data.deliveryReceiptTimestamps) {
                            chatRepository.updateSentMessageStatusNearTimestamp(
                                address, receipt.senderAddr, receipt.originalTimestamp,
                                com.jbselfcompany.tyr.chat.data.ChatMessage.STATUS_SENT,
                                com.jbselfcompany.tyr.chat.data.ChatMessage.STATUS_SENDING
                            )
                            deliveryReceiptsApplied = true
                        }
                        // Broadcast whenever anything changed so ConversationActivity reloads
                        // immediately — previously only fired when newMessages was non-empty,
                        // which meant delivery-receipt status updates (SENDING→SENT) were
                        // invisible until the user manually triggered a reload.
                        if (deliveryReceiptsApplied) {
                            sendBroadcast(Intent(ACTION_NEW_CHAT_MESSAGES).apply {
                                setPackage(packageName)
                            })
                            TyrLogger.d(TAG, "Chat poll: delivery receipts applied, broadcast sent")
                        }
                        if (newMessages.isNotEmpty()) {
                            showChatNotifications(newMessages, chatRepository)
                            sendBroadcast(Intent(ACTION_NEW_CHAT_MESSAGES).apply {
                                setPackage(packageName)
                            })
                            TyrLogger.i(TAG, "Chat poll: ${newMessages.size} new message(s) inserted, broadcast sent")

                            // Send automatic delivery receipts so sender gets single checkmark
                            for (msg in newMessages) {
                                try {
                                    SmtpSender().send(
                                        fromAddress = address,
                                        password = password,
                                        toAddress = msg.fromAddr,
                                        body = "",
                                        deliveryReceiptTimestamp = msg.timestamp
                                    )
                                } catch (e: Exception) {
                                    TyrLogger.w(TAG, "Failed to send delivery receipt to ${msg.fromAddr}", e)
                                }
                            }
                        } else if (result.data.messages.isEmpty() && result.data.deliveryReceiptTimestamps.isEmpty()) {
                            TyrLogger.d(TAG, "Chat poll: nothing new")
                        }
                    }
                }
            } catch (e: Exception) {
                TyrLogger.e(TAG, "Error polling inbox for chat messages", e)
            } finally {
                isFetchInProgress.set(false)
            }
        }
    }

    private fun showChatNotifications(
        newMessages: List<com.jbselfcompany.tyr.chat.data.ChatMessage>,
        chatRepository: ChatRepository
    ) {
        val address = configRepository.getMailAddress() ?: return
        val bySender = newMessages.groupBy { it.fromAddr }
        for ((fromAddr, messages) in bySender) {
            // If the user is currently viewing this conversation, suppress the notification
            // and mark the messages as read immediately instead.
            if (fromAddr.equals(
                    com.jbselfcompany.tyr.chat.ui.ConversationActivity.activeChatAddress,
                    ignoreCase = true
                )
            ) {
                chatRepository.markConversationRead(address, fromAddr)
                TyrLogger.d(TAG, "Chat poll: suppressed notification for open conversation ($fromAddr)")
                continue
            }
            val contact = chatRepository.getContact(fromAddr)
            val senderName = if (!contact?.name.isNullOrBlank()) contact!!.name else fromAddr

            val conversationIntent = Intent(
                this,
                com.jbselfcompany.tyr.chat.ui.ConversationActivity::class.java
            ).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(
                    com.jbselfcompany.tyr.chat.ui.ConversationActivity.EXTRA_CONTACT_ADDRESS,
                    fromAddr
                )
                putExtra(
                    com.jbselfcompany.tyr.chat.ui.ConversationActivity.EXTRA_CONTACT_NAME,
                    contact?.name ?: ""
                )
            }
            val pendingIntent = PendingIntent.getActivity(
                this,
                CHAT_NOTIFICATION_BASE_ID + fromAddr.hashCode(),
                conversationIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val title = if (messages.size == 1)
                getString(R.string.notification_new_message_from, senderName)
            else
                getString(R.string.notification_new_messages_from, messages.size, senderName)
            val lastMsg = messages.last()
            val content = when {
                lastMsg.body.isNotBlank() -> lastMsg.body.take(100)
                lastMsg.hasAttachment -> {
                    val isImage = lastMsg.attachmentMimeType?.startsWith("image/") == true
                    val name = lastMsg.attachmentName
                    if (isImage) {
                        if (!name.isNullOrBlank()) "\uD83D\uDDBC $name" else getString(R.string.chat_preview_photo)
                    } else {
                        if (!name.isNullOrBlank()) "\uD83D\uDCCE $name" else getString(R.string.chat_preview_file)
                    }
                }
                else -> "\u2026"
            }

            val notification = androidx.core.app.NotificationCompat.Builder(this, TyrApplication.CHANNEL_ID_CHAT)
                .setSmallIcon(R.drawable.ic_notification_icon)
                .setContentTitle(title)
                .setContentText(content)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                .build()

            notificationManager.notify(CHAT_NOTIFICATION_BASE_ID + fromAddr.hashCode(), notification)
        }
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
