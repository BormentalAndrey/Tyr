package com.jbselfcompany.tyr.ui

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.jbselfcompany.tyr.R
import com.jbselfcompany.tyr.TyrApplication
import com.jbselfcompany.tyr.databinding.ActivityMainBinding
import com.jbselfcompany.tyr.service.ServiceStatus
import com.jbselfcompany.tyr.service.ServiceStatusListener
import com.jbselfcompany.tyr.service.YggmailService
import com.jbselfcompany.tyr.ui.onboarding.OnboardingActivity
import com.jbselfcompany.tyr.ui.settings.SettingsActivity
import com.jbselfcompany.tyr.utils.AutoconfigServer
import com.jbselfcompany.tyr.utils.NetworkStatsMonitor
import com.jbselfcompany.tyr.utils.PermissionManager
import android.util.Log
import android.graphics.Bitmap
import android.graphics.Color
import android.widget.ImageView
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

/**
 * Main activity displaying service status and mail configuration.
 * Shows SMTP/IMAP connection information for DeltaChat.
 */
class MainActivity : BaseActivity(), ServiceStatusListener {

    private lateinit var binding: ActivityMainBinding
    private val configRepository by lazy { TyrApplication.instance.configRepository }
    private val autoconfigServer by lazy { AutoconfigServer(this) }
    private val networkStatsMonitor by lazy { NetworkStatsMonitor(this) }

    private var yggmailService: YggmailService? = null
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as YggmailService.LocalBinder
            yggmailService = binder.getService()
            serviceBound = true

            // Store binder in TyrApplication for global access
            TyrApplication.instance.yggmailServiceBinder = binder

            yggmailService?.addStatusListener(this@MainActivity)

            // Update storage and quota info when service connects
            updateStorageInfo()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            yggmailService?.removeStatusListener(this@MainActivity)
            yggmailService = null
            serviceBound = false
            TyrApplication.instance.yggmailServiceBinder = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Check if onboarding is needed
        if (!configRepository.isOnboardingCompleted()) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        setupUI()
        bindService()

        // Don't request permissions automatically on first launch
        // They will be shown as Snackbars in onResume() instead
    }

    override fun onResume() {
        super.onResume()
        // Show permission warnings if any permissions are missing
        showPermissionWarnings()
        // Notify service that app is active for optimized heartbeat
        yggmailService?.setAppActive(true)
        // Start network monitoring
        startNetworkMonitoring()
        // Update storage info
        updateStorageInfo()
    }

    override fun onPause() {
        super.onPause()
        // Notify service that app went to background
        yggmailService?.setAppActive(false)
        // Stop network monitoring to save battery
        stopNetworkMonitoring()
    }

    override fun onDestroy() {
        super.onDestroy()
        unbindService()
        // Stop autoconfig server when activity is destroyed
        autoconfigServer.stop()
    }

    private fun setupUI() {
        // Service control button
        binding.buttonToggleService.setOnClickListener {
            if (YggmailService.isRunning) {
                // Perform soft stop instead of immediate stop
                // This gracefully disconnects peers before stopping to avoid ErrClosed errors
                yggmailService?.softStop()
            } else {
                YggmailService.start(this)
            }
        }

        // Setup DeltaChat button with DCACCOUNT link
        binding.buttonSetupDeltachat.setOnClickListener {
            setupDeltaChat()
        }

        // Copy mail address button (legacy support)
        binding.buttonCopyAddress.setOnClickListener {
            val address = configRepository.getMailAddress()
            if (!address.isNullOrEmpty()) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("Mail Address", address)
                clipboard.setPrimaryClip(clip)
                com.google.android.material.snackbar.Snackbar.make(
                    binding.root,
                    R.string.address_copied,
                    com.google.android.material.snackbar.Snackbar.LENGTH_SHORT
                ).show()
            }
        }

        // Show QR code button
        binding.buttonShowQr.setOnClickListener {
            val address = configRepository.getMailAddress()
            if (!address.isNullOrEmpty()) {
                showQrCodeDialog(address)
            }
        }

        // DeltaChat setup instructions card
        binding.cardDeltachatSetup.setOnClickListener {
            showDeltaChatInstructionsDialog()
        }

        // Email client setup instructions card
        binding.cardEmailClientSetup.setOnClickListener {
            showEmailClientInstructionsDialog()
        }

        updateUI()
    }

    private fun bindService() {
        val intent = Intent(this, YggmailService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun unbindService() {
        if (serviceBound) {
            yggmailService?.removeStatusListener(this)
            unbindService(serviceConnection)
            serviceBound = false
        }
    }

    private fun updateUI() {
        val mailAddress = configRepository.getMailAddress()
        val isRunning = YggmailService.isRunning

        // Mail configuration - only show when service is running
        if (!mailAddress.isNullOrEmpty() && isRunning) {
            binding.textMailAddress.text = mailAddress
            binding.textMailAddress.visibility = View.VISIBLE
            binding.buttonSetupDeltachat.visibility = View.VISIBLE
            binding.buttonCopyAddress.visibility = View.VISIBLE
            binding.buttonShowQr.visibility = View.VISIBLE
        } else {
            binding.textMailAddress.visibility = View.GONE
            binding.buttonSetupDeltachat.visibility = View.GONE
            binding.buttonCopyAddress.visibility = View.GONE
            binding.buttonShowQr.visibility = View.GONE
        }

        // SMTP/IMAP info
        binding.textSmtpServer.text = getString(R.string.smtp_server, "127.0.0.1", "1025")
        binding.textImapServer.text = getString(R.string.imap_server, "127.0.0.1", "1143")

        // Service button
        if (isRunning) {
            binding.buttonToggleService.text = getString(R.string.stop_service)
            binding.buttonToggleService.setIconResource(R.drawable.ic_stop)
        } else {
            binding.buttonToggleService.text = getString(R.string.start_service)
            binding.buttonToggleService.setIconResource(R.drawable.ic_play_arrow)
        }

        // Update storage info
        // This function will handle visibility based on service state
        updateStorageInfo()
    }

    private fun setupDeltaChat() {
        try {
            // Get credentials
            val email = configRepository.getMailAddress()
            val password = configRepository.getPassword()

            if (email.isNullOrEmpty() || password.isNullOrEmpty()) {
                Snackbar.make(
                    binding.root,
                    R.string.dcaccount_error,
                    Snackbar.LENGTH_LONG
                ).show()
                return
            }

            // Generate DCLOGIN URL (simpler, doesn't require HTTPS)
            // DCLOGIN embeds credentials directly in the URI
            val dcloginUrl = autoconfigServer.generateDcloginUrl(email, password)
            Log.d("MainActivity", "Generated DCLOGIN URL: $dcloginUrl")

            // Check if DeltaChat/ArcaneChat is installed (try multiple package names)
            val deltaChatPackages = mapOf(
                "com.b44t.messenger" to "DeltaChat",
                "chat.delta" to "DeltaChat",
                "chat.delta.lite" to "ArcaneChat",
                "com.github.arcanechat" to "ArcaneChat"
            )

            // Find all installed packages
            val installedApps = deltaChatPackages.filter { (packageName, _) ->
                try {
                    packageManager.getPackageInfo(packageName, 0)
                    Log.d("MainActivity", "Found package: $packageName")
                    true
                } catch (e: Exception) {
                    Log.d("MainActivity", "Package not found: $packageName")
                    false
                }
            }

            Log.d("MainActivity", "Installed apps: ${installedApps.keys}")

            when {
                installedApps.isEmpty() -> {
                    // No apps installed - show message
                    MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.deltachat_not_installed_title)
                        .setMessage(R.string.deltachat_not_installed_message)
                        .setPositiveButton(R.string.ok, null)
                        .show()
                }
                installedApps.size == 1 -> {
                    // Only one app installed - open it directly
                    val packageName = installedApps.keys.first()
                    openEmailClient(packageName, dcloginUrl)
                }
                else -> {
                    // Multiple apps installed - show selection dialog
                    showAppSelectionDialog(installedApps, dcloginUrl)
                }
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error setting up DeltaChat", e)
            Snackbar.make(
                binding.root,
                R.string.dcaccount_error,
                Snackbar.LENGTH_LONG
            ).show()
        }
    }

    private fun showAppSelectionDialog(apps: Map<String, String>, dcloginUrl: String) {
        val appNames = apps.values.toTypedArray()
        val packageNames = apps.keys.toTypedArray()

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.select_email_client_title)
            .setItems(appNames) { _, which ->
                val selectedPackage = packageNames[which]
                openEmailClient(selectedPackage, dcloginUrl)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun openEmailClient(packageName: String, dcloginUrl: String) {
        try {
            // First, try with package specified
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse(dcloginUrl)
                setPackage(packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)

            Snackbar.make(
                binding.root,
                R.string.dcaccount_opened,
                Snackbar.LENGTH_SHORT
            ).show()
        } catch (e: Exception) {
            Log.w("MainActivity", "Failed to open with package $packageName, trying without", e)
            // Try without package specification
            try {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse(dcloginUrl)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)

                Snackbar.make(
                    binding.root,
                    R.string.dcaccount_opened,
                    Snackbar.LENGTH_SHORT
                ).show()
            } catch (e2: Exception) {
                Log.e("MainActivity", "Failed to open DCLOGIN URL", e2)
                // Fallback: copy to clipboard
                copyDcloginToClipboard(dcloginUrl)
            }
        }
    }

    private fun copyDcloginToClipboard(dcloginUrl: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("DCLOGIN", dcloginUrl)
        clipboard.setPrimaryClip(clip)

        Snackbar.make(
            binding.root,
            R.string.dcaccount_copied,
            Snackbar.LENGTH_LONG
        ).show()
    }

    private fun showQrCodeDialog(mailAddress: String) {
        try {
            // Generate mailto URL
            val mailtoUrl = "mailto:$mailAddress"

            // Generate QR code
            val qrBitmap = generateQrCode(mailtoUrl, 512, 512)

            // Create ImageView for QR code
            val imageView = ImageView(this).apply {
                setImageBitmap(qrBitmap)
                setPadding(32, 32, 32, 32)
            }

            // Show dialog
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.qr_code_title)
                .setView(imageView)
                .setPositiveButton(R.string.ok, null)
                .setNeutralButton(R.string.share_mailto) { _, _ ->
                    shareMailto(mailtoUrl)
                }
                .show()
        } catch (e: Exception) {
            Log.e("MainActivity", "Error generating QR code", e)
            Snackbar.make(
                binding.root,
                R.string.qr_code_error,
                Snackbar.LENGTH_SHORT
            ).show()
        }
    }

    private fun shareMailto(mailtoUrl: String) {
        try {
            val sendIntent = Intent().apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_TEXT, mailtoUrl)
                type = "text/plain"
            }
            val shareIntent = Intent.createChooser(sendIntent, null)
            startActivity(shareIntent)
        } catch (e: Exception) {
            Log.e("MainActivity", "Error sharing mailto URL", e)
            Snackbar.make(
                binding.root,
                R.string.qr_code_error,
                Snackbar.LENGTH_SHORT
            ).show()
        }
    }

    private fun generateQrCode(content: String, width: Int, height: Int): Bitmap {
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)

        for (x in 0 until width) {
            for (y in 0 until height) {
                bitmap.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }

        return bitmap
    }

    private fun showDeltaChatInstructionsDialog() {
        val mailAddress = configRepository.getMailAddress() ?: getString(R.string.your_email_address)

        val instructions = getString(R.string.deltachat_instructions, mailAddress)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.deltachat_setup)
            .setMessage(instructions)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun showEmailClientInstructionsDialog() {
        val mailAddress = configRepository.getMailAddress() ?: getString(R.string.your_email_address)
        val password = getString(R.string.your_password)

        val instructions = getString(R.string.email_client_instructions, mailAddress, password)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.email_client_setup_title)
            .setMessage(instructions)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onStatusChanged(status: ServiceStatus, error: String?) {
        runOnUiThread {
            when (status) {
                ServiceStatus.STARTING -> {
                    binding.textStatus.text = getString(R.string.status_starting)
                    binding.textStatus.setTextColor(getColor(R.color.status_starting))
                    binding.progressBar.visibility = View.VISIBLE
                }
                ServiceStatus.RUNNING -> {
                    binding.textStatus.text = getString(R.string.status_running)
                    binding.textStatus.setTextColor(getColor(R.color.status_running))
                    binding.progressBar.visibility = View.GONE
                    // Update mail address when service starts
                    updateUI()
                }
                ServiceStatus.STOPPING -> {
                    binding.textStatus.text = getString(R.string.status_stopping)
                    binding.textStatus.setTextColor(getColor(R.color.status_stopping))
                    binding.progressBar.visibility = View.VISIBLE
                }
                ServiceStatus.STOPPED -> {
                    binding.textStatus.text = getString(R.string.status_stopped)
                    binding.textStatus.setTextColor(getColor(R.color.status_stopped))
                    binding.progressBar.visibility = View.GONE
                }
                ServiceStatus.ERROR -> {
                    binding.textStatus.text = getString(R.string.status_error, error ?: "")
                    binding.textStatus.setTextColor(getColor(R.color.status_error))
                    binding.progressBar.visibility = View.GONE
                }
            }
            updateUI()
        }
    }

    // Removed automatic permission requests on first launch
    // Permissions are now only shown as Snackbar warnings in onResume()

    /**
     * Show Snackbar warnings for missing permissions (like in Mimir app)
     * Called in onResume() to check permissions every time activity becomes visible
     */
    private fun showPermissionWarnings() {
        // Check if notifications are allowed (Android 8.0+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!areNotificationsEnabled()) {
                val root = findViewById<View>(android.R.id.content)
                Snackbar.make(root, getString(R.string.allow_notifications_snack), Snackbar.LENGTH_INDEFINITE)
                    .setAction(getString(R.string.allow)) {
                        try {
                            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                            }
                            startActivity(intent)
                        } catch (e: ActivityNotFoundException) {
                            e.printStackTrace()
                        }
                    }
                    .setTextMaxLines(3)
                    .show()
            }
        }

        // Check if app is battery optimized
        if (!isBatteryOptimizationDisabled()) {
            val root = findViewById<View>(android.R.id.content)
            Snackbar.make(root, getString(R.string.add_to_power_exceptions), Snackbar.LENGTH_INDEFINITE)
                .setAction(getString(R.string.allow)) {
                    val action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                    try {
                        val intent = Intent(action, Uri.parse("package:$packageName"))
                        startActivity(intent)
                    } catch (e: ActivityNotFoundException) {
                        e.printStackTrace()
                        // Fallback: open the generic battery-settings screen
                        try {
                            startActivity(Intent(action))
                        } catch (ex: ActivityNotFoundException) {
                            ex.printStackTrace()
                        }
                    }
                }
                .setTextMaxLines(3)
                .show()
        }
    }

    /**
     * Check if notifications are enabled for this app
     */
    private fun areNotificationsEnabled(): Boolean {
        return NotificationManagerCompat.from(this).areNotificationsEnabled()
    }

    /**
     * Check if battery optimization is disabled for this app
     */
    private fun isBatteryOptimizationDisabled(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            pm.isIgnoringBatteryOptimizations(packageName)
        } else {
            true // Battery optimization doesn't exist on Android 5 and below
        }
    }

    /**
     * Start network statistics monitoring
     * Only runs when app is in foreground to save battery
     * Latency information comes directly from Yggdrasil transport layer
     */
    private fun startNetworkMonitoring() {
        Log.d("MainActivity", "Starting network monitoring")
        networkStatsMonitor.start(object : NetworkStatsMonitor.NetworkStatsListener {
            override fun onStatsUpdated(stats: NetworkStatsMonitor.NetworkStats) {
                Log.d("MainActivity", "onStatsUpdated called with ${stats.peers.size} peers")
                stats.peers.forEach { peer ->
                    Log.d("MainActivity", "  Peer: ${peer.host}:${peer.port}, connected=${peer.connected}, latencyMs=${peer.latencyMs}")
                }
                updateNetworkStatsUI(stats)
            }
        }, enableLatencyMeasurement = true) // Parameter kept for API compatibility
    }

    /**
     * Stop network statistics monitoring
     */
    private fun stopNetworkMonitoring() {
        networkStatsMonitor.stop()
    }

    /**
     * Update UI with network statistics
     */
    private fun updateNetworkStatsUI(stats: NetworkStatsMonitor.NetworkStats) {
        Log.d("MainActivity", "updateNetworkStatsUI called, updating UI")
        // Connection type
        binding.textConnectionType.text = stats.connectionType

        // Update peers list
        updatePeersList(stats.peers)
    }

    /**
     * Update the list of peers with latency information
     */
    private fun updatePeersList(peers: List<NetworkStatsMonitor.PeerInfo>) {
        Log.d("MainActivity", "updatePeersList called with ${peers.size} peers")
        // Clear existing views
        binding.peersContainer.removeAllViews()

        if (peers.isEmpty()) {
            // Show "no peers" message
            val noPeersText = android.widget.TextView(this).apply {
                text = getString(R.string.no_active_peer)
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
                // Use Material3 color that adapts to light/dark theme
                val typedValue = android.util.TypedValue()
                context.theme.resolveAttribute(
                    com.google.android.material.R.attr.colorOnSurfaceVariant,
                    typedValue,
                    true
                )
                setTextColor(typedValue.data)
            }
            binding.peersContainer.addView(noPeersText)
        } else {
            // Add peer info views
            for (peer in peers) {
                val peerView = android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = 8.dpToPx()
                        bottomMargin = 8.dpToPx()
                    }
                    setPadding(12.dpToPx(), 12.dpToPx(), 12.dpToPx(), 12.dpToPx())
                    setBackgroundResource(R.drawable.peer_item_background)
                }

                // First row: Peer address and status
                val firstRow = android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }

                // Peer host:port
                val peerNameText = android.widget.TextView(this).apply {
                    text = "${peer.host}:${peer.port}"
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        0,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                    )
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                    setTypeface(null, android.graphics.Typeface.BOLD)
                }

                // Connection status
                val statusText = android.widget.TextView(this).apply {
                    text = if (peer.connected) getString(R.string.peer_connected) else getString(R.string.peer_disconnected)
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)

                    // Color code status
                    setTextColor(
                        if (peer.connected)
                            getColor(R.color.status_running)
                        else
                            getColor(R.color.status_error)
                    )
                }

                firstRow.addView(peerNameText)
                firstRow.addView(statusText)

                // Second row: Latency
                val latencyText = android.widget.TextView(this).apply {
                    text = if (peer.latencyMs >= 0) {
                        getString(R.string.peer_latency_format, peer.latencyMs)
                    } else {
                        getString(R.string.peer_latency_unknown)
                    }
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = 4.dpToPx()
                    }
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
                    // Use Material3 color that adapts to light/dark theme
                    val typedValue = android.util.TypedValue()
                    context.theme.resolveAttribute(
                        com.google.android.material.R.attr.colorOnSurfaceVariant,
                        typedValue,
                        true
                    )
                    setTextColor(typedValue.data)
                }

                peerView.addView(firstRow)
                peerView.addView(latencyText)

                binding.peersContainer.addView(peerView)
            }
        }
    }

    /**
     * Convert dp to pixels
     */
    private fun Int.dpToPx(): Int {
        return (this * resources.displayMetrics.density).toInt()
    }

    /**
     * Update storage and quota info card
     * Shows message size limit and storage statistics
     */
    private fun updateStorageInfo() {
        if (!YggmailService.isRunning) {
            binding.cardStorageQuota.visibility = View.GONE
            return
        }

        // Show card immediately if service is running
        binding.cardStorageQuota.visibility = View.VISIBLE

        Thread {
            val quotaInfo = yggmailService?.getUnreadQuotaInfo()
            val storageStats = yggmailService?.getMailStorageStats()

            runOnUiThread {
                if (quotaInfo == null || storageStats == null) {
                    // Keep card visible but don't update data
                    return@runOnUiThread
                }

                // Show only quota limit (maximum file size)
                val quotaText = getString(R.string.max_file_size, quotaInfo.quotaMB)
                binding.textQuotaInfo.text = quotaText

                // Hide progress bar
                binding.quotaInfoProgress.visibility = View.GONE

                // Update storage statistics
                val storageText = buildString {
                    append(getString(R.string.storage_db_size, storageStats.dbSizeMB))
                    append("\n")
                    append(getString(R.string.storage_file_size, storageStats.fileSizeMB))
                    append("\n")
                    append(getString(R.string.storage_total_size, storageStats.totalSizeMB))
                }
                binding.textStorageInfo.text = storageText
            }
        }.start()
    }
}
