package com.jbselfcompany.tyr

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.jbselfcompany.tyr.data.ConfigRepository
import com.jbselfcompany.tyr.receiver.NetworkChangeReceiver
import com.jbselfcompany.tyr.utils.LocaleHelper
import com.jbselfcompany.tyr.utils.TyrLogger

/**
 * Application class for Tyr.
 * Initializes global application state and notification channels.
 *
 * Battery optimization: Registers NetworkCallback with 15-second delay
 * to avoid unnecessary network monitoring during app startup
 */
class TyrApplication : Application() {

    companion object {
        const val CHANNEL_ID_SERVICE = "yggmail_service"
        const val CHANNEL_ID_MAIL = "mail_notifications"
        const val CHANNEL_ID_CHAT = "chat_notifications"

        lateinit var instance: TyrApplication
            private set
    }

    lateinit var configRepository: ConfigRepository
        private set

    var yggmailServiceBinder: com.jbselfcompany.tyr.service.YggmailService.LocalBinder? = null

    private var networkCallback: NetworkChangeReceiver? = null
    private val networkCallbackHandler = Handler(Looper.getMainLooper())
    private var networkCallbackRunnable: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Initialize configuration repository
        configRepository = ConfigRepository(this)

        // Initialize logger state from persisted preference (seamless toggle without restart)
        TyrLogger.setEnabled(configRepository.isLogCollectionEnabled())

        // Apply theme preference
        LocaleHelper.applyTheme(this)

        // Create notification channels
        createNotificationChannels()

        // Only register network callback if service is enabled and auto-start is on
        // Battery optimization: Don't monitor network if service won't be running
        if (configRepository.isServiceEnabled() && configRepository.isAutoStartEnabled()) {
            scheduleNetworkCallbackRegistration()
        }
    }

    private fun scheduleNetworkCallbackRegistration() {
        networkCallbackRunnable = Runnable {
            networkCallback = NetworkChangeReceiver(this)
            networkCallback?.register()
        }
        networkCallbackHandler.postDelayed(networkCallbackRunnable!!, 15000)
    }

    fun cancelNetworkCallbackRegistration() {
        networkCallbackRunnable?.let {
            networkCallbackHandler.removeCallbacks(it)
        }
    }

    override fun attachBaseContext(base: Context) {
        // Apply language preference before attaching base context
        super.attachBaseContext(LocaleHelper.applyLanguage(base))
    }

    override fun onTerminate() {
        // Cancel pending network callback registration
        cancelNetworkCallbackRegistration()
        // Unregister network callback
        networkCallback?.unregister()
        super.onTerminate()
    }

    /**
     * Create notification channels for Android O and above
     */
    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)

            // Service notification channel (battery optimized with IMPORTANCE_MIN)
            val serviceChannel = NotificationChannel(
                CHANNEL_ID_SERVICE,
                getString(R.string.notification_channel_service),
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = getString(R.string.notification_channel_service_desc)
                setShowBadge(false)
            }

            // Mail notification channel
            val mailChannel = NotificationChannel(
                CHANNEL_ID_MAIL,
                getString(R.string.notification_channel_mail),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = getString(R.string.notification_channel_mail_desc)
                setShowBadge(true)
            }

            // Chat notification channel (high priority for heads-up notifications)
            val chatChannel = NotificationChannel(
                CHANNEL_ID_CHAT,
                getString(R.string.notification_channel_chat),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.notification_channel_chat_desc)
                setShowBadge(true)
            }

            notificationManager.createNotificationChannel(serviceChannel)
            notificationManager.createNotificationChannel(mailChannel)
            notificationManager.createNotificationChannel(chatChannel)
        }
    }
}
