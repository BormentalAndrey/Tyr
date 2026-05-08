package com.jbselfcompany.tyr.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.MenuItem
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.jbselfcompany.tyr.R
import com.jbselfcompany.tyr.TyrApplication
import com.jbselfcompany.tyr.data.ConfigRepository
import com.jbselfcompany.tyr.data.PeerInfo
import com.jbselfcompany.tyr.databinding.ActivityPeersBinding
import com.jbselfcompany.tyr.service.ServiceStatus
import com.jbselfcompany.tyr.service.ServiceStatusListener
import com.jbselfcompany.tyr.service.YggmailService

class PeersActivity : BaseActivity(), ServiceStatusListener {

    private lateinit var binding: ActivityPeersBinding
    private val configRepository by lazy { TyrApplication.instance.configRepository }
    private val peers = mutableListOf<PeerInfo>()
    private lateinit var adapter: PeerAdapter
    private val mainHandler = Handler(Looper.getMainLooper())

    private val discoveredPeers = mutableListOf<com.jbselfcompany.tyr.data.DiscoveredPeer>()
    private var searchInProgress = false
    private var discoveryDialog: androidx.appcompat.app.AlertDialog? = null
    private var discoveryAdapter: DiscoveredPeerAdapter? = null
    private var discoveryProgressBar: com.google.android.material.progressindicator.LinearProgressIndicator? = null
    private var discoveryProgressText: android.widget.TextView? = null
    private var progressAnimator: android.animation.ValueAnimator? = null
    private var currentProgress = 0
    private var discoveryRecyclerView: androidx.recyclerview.widget.RecyclerView? = null

    private var yggmailService: YggmailService? = null
    private var serviceBound = false
    private var wasServiceRunning = false
    private var hasUnsavedChanges = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as YggmailService.LocalBinder
            yggmailService = binder.getService()
            serviceBound = true
            yggmailService?.addStatusListener(this@PeersActivity)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            yggmailService?.removeStatusListener(this@PeersActivity)
            yggmailService = null
            serviceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityPeersBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // Store if service was running when we entered the activity
        wasServiceRunning = YggmailService.isRunning

        setupRecyclerView()
        setupAddButton()
        setupApplyButton()
        loadPeers()
        bindToService()
    }

    override fun onDestroy() {
        super.onDestroy()
        unbindFromService()
    }

    override fun onContextItemSelected(item: android.view.MenuItem): Boolean {
        val position = adapter.getContextMenuPosition()
        return when (item.itemId) {
            PeerAdapter.MENU_EDIT -> {
                editPeer(position)
                true
            }
            PeerAdapter.MENU_DELETE -> {
                showDeleteConfirmation(position)
                true
            }
            else -> super.onContextItemSelected(item)
        }
    }

    private fun bindToService() {
        if (YggmailService.isRunning) {
            val intent = Intent(this, YggmailService::class.java)
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    private fun unbindFromService() {
        if (serviceBound) {
            yggmailService?.removeStatusListener(this)
            unbindService(serviceConnection)
            serviceBound = false
        }
    }


    private fun setupRecyclerView() {
        adapter = PeerAdapter(
            peers = peers,
            onEdit = { position -> editPeer(position) },
            onRemove = { position -> showDeleteConfirmation(position) },
            onToggle = { position, enabled -> togglePeer(position, enabled) }
        )

        binding.recyclerPeers.layoutManager = LinearLayoutManager(this)
        binding.recyclerPeers.adapter = adapter
        binding.recyclerPeers.addItemDecoration(
            DividerItemDecoration(this, DividerItemDecoration.VERTICAL)
        )

        // Register context menu for RecyclerView
        registerForContextMenu(binding.recyclerPeers)
    }

    private fun setupAddButton() {
        binding.btnAddPeer.setOnClickListener { view ->
            val popup = PopupMenu(this, view)
            popup.menuInflater.inflate(R.menu.menu_add_peer, popup.menu)
            popup.setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    R.id.action_add_manually -> {
                        showAddPeerDialog()
                        true
                    }
                    R.id.action_find_peers -> {
                        startPeerDiscovery()
                        true
                    }
                    else -> false
                }
            }
            popup.show()
        }
    }

    private fun setupApplyButton() {
        binding.btnApplyChanges.setOnClickListener {
            applyPeerChanges()
        }
    }

    private fun updateApplyButtonVisibility() {
        binding.btnApplyChanges.visibility = if (hasUnsavedChanges) View.VISIBLE else View.GONE
    }

    private fun loadPeers() {
        peers.clear()
        // Load all peers with enabled/disabled state
        peers.addAll(configRepository.getAllPeersInfo())
        adapter.notifyDataSetChanged()
    }

    private fun showAddPeerDialog() {
        showPeerDialog(title = getString(R.string.add_peer), existingPeer = null)
    }

    private fun editPeer(position: Int) {
        if (position < 0 || position >= peers.size) return

        val peer = peers[position]
        showPeerDialog(
            title = getString(R.string.edit_peer),
            existingPeer = peer,
            onSave = { newPeerUrl ->
                // Update the peer
                val updatedPeer = peer.copy(uri = newPeerUrl)
                peers[position] = updatedPeer

                // Remove old peer and save new one
                configRepository.removePeer(peer.uri)
                configRepository.savePeer(updatedPeer)

                adapter.notifyItemChanged(position)

                hasUnsavedChanges = true
                updateApplyButtonVisibility()

                Toast.makeText(this, R.string.peers_saved, Toast.LENGTH_SHORT).show()
            }
        )
    }

    private fun showPeerDialog(title: String, existingPeer: PeerInfo?, onSave: ((String) -> Unit)? = null) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_peer, null)
        val editPeerUrl = dialogView.findViewById<TextInputEditText>(R.id.edit_peer_url)

        // Pre-fill with existing peer URL if editing
        existingPeer?.let {
            editPeerUrl.setText(it.uri)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(dialogView)
            .setPositiveButton(if (existingPeer != null) R.string.save else R.string.add) { _, _ ->
                var peerUrl = editPeerUrl.text.toString().trim()

                if (peerUrl.isEmpty()) {
                    Toast.makeText(this, R.string.error_peers_empty, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                if (peerUrl.contains("\n")) {
                    Toast.makeText(this, R.string.error_peer_url_newlines, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                // Validate and fix peer URL format
                // Yggdrasil requires full URI with protocol (tcp://, tls://, quic://, etc.)
                if (!peerUrl.contains("://")) {
                    // Auto-add tcp:// prefix if no protocol specified
                    peerUrl = "tcp://$peerUrl"
                    Toast.makeText(this, R.string.peer_prefix_added, Toast.LENGTH_SHORT).show()
                }

                // Validate protocol and URL structure using shared validator
                if (!PeerInfo.isValidPeerUrl(peerUrl)) {
                    Toast.makeText(this, getString(R.string.error_invalid_peer_url), Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }

                // Check for duplicates, excluding the peer being edited
                if (peers.any { it.uri == peerUrl && it.uri != existingPeer?.uri }) {
                    Toast.makeText(this, R.string.peer_already_exists, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                if (existingPeer != null) {
                    // Edit mode
                    onSave?.invoke(peerUrl)
                } else {
                    // Add mode
                    val newPeer = PeerInfo(peerUrl, isEnabled = true, tag = PeerInfo.PeerTag.CUSTOM)
                    peers.add(newPeer)
                    adapter.notifyItemInserted(peers.size - 1)
                    configRepository.savePeer(newPeer)

                    hasUnsavedChanges = true
                    updateApplyButtonVisibility()

                    Toast.makeText(this, R.string.peers_saved, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showDeleteConfirmation(position: Int) {
        if (position < 0 || position >= peers.size) return

        val peer = peers[position]

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_peer)
            .setMessage(getString(R.string.delete_peer_confirmation, peer.uri))
            .setPositiveButton(R.string.delete_peer) { _, _ ->
                removePeer(position)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun removePeer(position: Int) {
        if (position < 0 || position >= peers.size) return

        val peerToRemove = peers[position]
        peers.removeAt(position)
        adapter.notifyItemRemoved(position)
        configRepository.removePeer(peerToRemove.uri)

        hasUnsavedChanges = true
        updateApplyButtonVisibility()

        Toast.makeText(this, R.string.peers_saved, Toast.LENGTH_SHORT).show()
    }

    private fun togglePeer(position: Int, enabled: Boolean) {
        if (position < 0 || position >= peers.size) return

        val peer = peers[position]
        peers[position] = peer.copy(isEnabled = enabled)
        configRepository.setPeerEnabled(peer.uri, enabled)

        hasUnsavedChanges = true
        updateApplyButtonVisibility()

        Toast.makeText(this, if (enabled) R.string.peer_enabled else R.string.peer_disabled, Toast.LENGTH_SHORT).show()
    }

    private fun applyPeerChanges() {
        if (!wasServiceRunning) {
            // Service was not running, just close the activity
            hasUnsavedChanges = false
            Toast.makeText(this, R.string.peers_applied, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Use hot reload instead of stop/start to prevent connection errors
        // This updates peer configuration without closing transport connections
        yggmailService?.hotReloadPeers()

        hasUnsavedChanges = false
        updateApplyButtonVisibility()

        Snackbar.make(
            binding.root,
            R.string.peers_applied,
            Snackbar.LENGTH_LONG
        ).show()
    }

    private fun showLoadingOverlay(show: Boolean) {
        binding.loadingOverlay.visibility = if (show) View.VISIBLE else View.GONE

        // Disable all interactive elements while loading
        binding.btnAddPeer.isEnabled = !show
        binding.btnApplyChanges.isEnabled = !show
        binding.recyclerPeers.isEnabled = !show
    }

    override fun onStatusChanged(status: ServiceStatus, error: String?) {
        mainHandler.post {
            if (error != null) {
                showLoadingOverlay(false)
                Snackbar.make(
                    binding.root,
                    getString(R.string.error_applying_peers) + ": $error",
                    Snackbar.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun startPeerDiscovery() {
        // Check network availability
        if (!com.jbselfcompany.tyr.data.NetworkUtils.isNetworkAvailable(this)) {
            showNoNetworkDialog()
            return
        }

        if (searchInProgress) {
            Toast.makeText(this, R.string.peer_discovery_in_progress, Toast.LENGTH_SHORT).show()
            return
        }

        // Clear previous results
        discoveredPeers.clear()

        searchInProgress = true

        // Show discovered peers dialog immediately with real-time updates
        showDiscoveredPeersDialogRealtime()

        // Set batching parameters based on network type
        val (batchSize, concurrency, pauseMs) = com.jbselfcompany.tyr.data.NetworkUtils.getBatchingParams(this)

        // Create callback
        val callback = object : mobile.PeerDiscoveryCallback {
            override fun onProgress(current: Long, total: Long, availableCount: Long) {
                mainHandler.post {
                    if (discoveryProgressBar == null || discoveryProgressText == null) return@post

                    // Update progress bar with smooth animation (1% increments)
                    val percentage = if (total > 0) ((current * 100) / total).toInt() else 0
                    animateProgressTo(percentage)
                }
            }

            override fun onPeerAvailable(peerJSON: String) {
                mainHandler.post {
                    try {
                        val peer = com.jbselfcompany.tyr.data.DiscoveredPeer.fromJson(
                            org.json.JSONObject(peerJSON)
                        )
                        discoveredPeers.add(peer)
                        // Add peer to adapter in real-time with sorting by RTT
                        discoveryAdapter?.addPeerSorted(peer)
                        // Always scroll to top to show the fastest peers
                        discoveryRecyclerView?.scrollToPosition(0)
                    } catch (e: Exception) {
                        // Ignore malformed JSON
                    }
                }
            }
        }

        // Start async discovery using helper (doesn't require running service)
        com.jbselfcompany.tyr.utils.PeerDiscoveryHelper.findAvailablePeersAsync(
            context = this,
            protocols = "tcp,tls,quic,ws,wss,unix,socks,sockstls",  // All protocols
            region = "",                  // All regions
            maxRTTMs = 500,              // Max 500ms RTT
            callback = callback,
            batchSize = batchSize,
            concurrency = concurrency,
            pauseMs = pauseMs
        )

        // Set timeout
        mainHandler.postDelayed({
            if (searchInProgress) {
                finishDiscovery()
            }
        }, 60000) // 60 seconds timeout
    }

    private fun finishDiscovery() {
        searchInProgress = false

        // Update progress bar to 100% with animation
        animateProgressTo(100)

        if (discoveredPeers.isEmpty()) {
            Toast.makeText(this, R.string.peer_discovery_no_peers_found, Toast.LENGTH_LONG).show()
            discoveryDialog?.dismiss()
            return
        }

        Toast.makeText(
            this,
            getString(R.string.peer_discovery_found, discoveredPeers.size),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun finishDiscoveryWithError(error: String) {
        searchInProgress = false
        discoveryDialog?.dismiss()

        Toast.makeText(
            this,
            getString(R.string.peer_discovery_error, error),
            Toast.LENGTH_LONG
        ).show()
    }

    private fun showNoNetworkDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.peer_discovery_no_network)
            .setMessage(R.string.peer_discovery_no_network_message)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun showDiscoveredPeersDialogRealtime() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_discovered_peers_realtime, null)
        val recyclerView = dialogView.findViewById<androidx.recyclerview.widget.RecyclerView>(
            R.id.recycler_discovered_peers
        )
        discoveryProgressBar = dialogView.findViewById(R.id.progress_discovery)
        discoveryProgressText = dialogView.findViewById(R.id.text_progress)
        discoveryRecyclerView = recyclerView

        // Reset progress
        currentProgress = 0
        progressAnimator?.cancel()
        discoveryProgressBar?.progress = 0
        discoveryProgressText?.text = getString(R.string.peer_discovery_progress_percent, 0)

        discoveryAdapter = DiscoveredPeerAdapter { selectionCount ->
            // Update selection count if needed
        }

        val layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this@PeersActivity)
        recyclerView.apply {
            this.layoutManager = layoutManager
            adapter = discoveryAdapter

            // Add divider between items for better visual separation
            addItemDecoration(
                DividerItemDecoration(
                    this@PeersActivity,
                    DividerItemDecoration.VERTICAL
                )
            )
        }

        discoveryDialog = MaterialAlertDialogBuilder(this, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog_Centered)
            .setTitle(R.string.peer_discovery_discovered_peers)
            .setView(dialogView)
            .setPositiveButton(R.string.peer_add_selected) { _, _ ->
                val selectedPeers = discoveryAdapter?.getSelectedPeers() ?: emptyList()
                addDiscoveredPeers(selectedPeers)
                cleanupDiscovery()
            }
            .setNegativeButton(R.string.cancel) { _, _ ->
                cleanupDiscovery()
            }
            .setOnDismissListener {
                cleanupDiscovery()
            }
            .show()
    }

    private fun addDiscoveredPeers(peersToAdd: List<com.jbselfcompany.tyr.data.DiscoveredPeer>) {
        if (peersToAdd.isEmpty()) {
            Toast.makeText(this, R.string.error_no_peers_selected, Toast.LENGTH_SHORT).show()
            return
        }

        var addedCount = 0

        // Sort by RTT before adding (fastest first)
        val sortedPeers = peersToAdd.sortedBy { it.rtt }

        sortedPeers.forEach { discoveredPeer ->
            // Convert to PeerInfo
            val peerInfo = discoveredPeer.toPeerInfo()

            // Check for duplicates
            if (!peers.any { it.uri == peerInfo.uri }) {
                peers.add(peerInfo)
                configRepository.savePeer(peerInfo)
                addedCount++
            }
        }

        if (addedCount > 0) {
            adapter.notifyDataSetChanged()
            hasUnsavedChanges = true
            updateApplyButtonVisibility()

            Toast.makeText(
                this,
                getString(R.string.peers_added, addedCount),
                Toast.LENGTH_SHORT
            ).show()
        } else {
            Toast.makeText(this, R.string.all_peers_already_exist, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    /**
     * Animate progress bar smoothly from current value to target (1% increments)
     * Speed: 300ms per 1% for consistent, visible animation
     */
    private fun animateProgressTo(targetProgress: Int) {
        if (discoveryProgressBar == null || discoveryProgressText == null) return

        // Cancel previous animation
        progressAnimator?.cancel()

        // Create smooth animation with consistent speed (300ms per 1%)
        progressAnimator = android.animation.ValueAnimator.ofInt(currentProgress, targetProgress).apply {
            duration = ((targetProgress - currentProgress) * 300).toLong().coerceAtLeast(100)
            interpolator = android.view.animation.LinearInterpolator()

            addUpdateListener { animator ->
                val value = animator.animatedValue as Int
                currentProgress = value
                discoveryProgressBar?.progress = value
                discoveryProgressText?.text = getString(R.string.peer_discovery_progress_percent, value)
            }

            start()
        }
    }

    /**
     * Cleanup discovery state
     */
    private fun cleanupDiscovery() {
        searchInProgress = false
        progressAnimator?.cancel()
        progressAnimator = null
        currentProgress = 0
        discoveryAdapter = null
        discoveryProgressBar = null
        discoveryProgressText = null
        discoveryRecyclerView = null
    }
}
