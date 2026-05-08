package com.jbselfcompany.tyr.ui.onboarding

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jbselfcompany.tyr.R
import com.jbselfcompany.tyr.TyrApplication
import com.jbselfcompany.tyr.data.DiscoveredPeer
import com.jbselfcompany.tyr.data.NetworkUtils
import com.jbselfcompany.tyr.databinding.FragmentOnboardingPeersBinding
import com.jbselfcompany.tyr.ui.DiscoveredPeerAdapter
import mobile.PeerDiscoveryCallback

/**
 * Peers configuration fragment for onboarding with peer discovery
 */
class OnboardingPeersFragment : Fragment() {

    private var _binding: FragmentOnboardingPeersBinding? = null
    private val binding get() = _binding!!

    private val configRepository by lazy { TyrApplication.instance.configRepository }
    private val discoveredPeers = mutableListOf<DiscoveredPeer>()
    private lateinit var adapter: DiscoveredPeerAdapter
    private val mainHandler = Handler(Looper.getMainLooper())

    private var useDefaultPeers = false
    private var searchInProgress = false
    private var progressAnimator: android.animation.ValueAnimator? = null
    private var currentProgress = 0

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentOnboardingPeersBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        setupButtons()
        loadCachedPeers()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        progressAnimator?.cancel()
        progressAnimator = null
        _binding = null
    }

    private fun setupRecyclerView() {
        adapter = DiscoveredPeerAdapter { selectionCount ->
            // Selection changed callback - could update UI if needed
        }

        binding.recyclerPeers.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@OnboardingPeersFragment.adapter

            // Add divider between items for better visual separation
            addItemDecoration(
                androidx.recyclerview.widget.DividerItemDecoration(
                    requireContext(),
                    androidx.recyclerview.widget.DividerItemDecoration.VERTICAL
                )
            )
        }
    }

    private fun setupButtons() {
        binding.buttonFindPeers.setOnClickListener {
            startPeerDiscovery()
        }

        binding.switchUseDefault.setOnCheckedChangeListener { _, isChecked ->
            useDefaultPeers = isChecked
            if (isChecked) {
                // Clear selected peers and hide peer card when using defaults
                discoveredPeers.clear()
                adapter.updatePeers(discoveredPeers)
                binding.peersCard.visibility = View.GONE
                binding.chipCacheIndicator.visibility = View.GONE
            }
        }
    }

    private fun loadCachedPeers() {
        val cachedPeers = configRepository.getCachedDiscoveredPeers()
        if (cachedPeers != null && cachedPeers.isNotEmpty()) {
            discoveredPeers.clear()
            discoveredPeers.addAll(cachedPeers)
            adapter.updatePeers(discoveredPeers)

            // Show cache indicator and hide instructions
            binding.chipCacheIndicator.visibility = View.VISIBLE
            binding.peersCard.visibility = View.VISIBLE
            binding.instructionsCard.visibility = View.GONE
        }
    }

    private fun startPeerDiscovery() {
        // Check network availability
        if (!NetworkUtils.isNetworkAvailable(requireContext())) {
            showNoNetworkDialog()
            return
        }

        if (searchInProgress) {
            Toast.makeText(requireContext(), R.string.peer_discovery_in_progress, Toast.LENGTH_SHORT).show()
            return
        }

        // Clear previous results
        discoveredPeers.clear()
        adapter.updatePeers(discoveredPeers)

        // Hide cache indicator and instructions
        binding.chipCacheIndicator.visibility = View.GONE
        binding.instructionsCard.visibility = View.GONE

        // Show progress and peers card
        binding.progressLayout.visibility = View.VISIBLE
        binding.peersCard.visibility = View.VISIBLE
        binding.buttonFindPeers.isEnabled = false
        binding.switchUseDefault.isEnabled = false

        // Turn off default peers switch when starting discovery
        binding.switchUseDefault.isChecked = false

        // Reset progress
        currentProgress = 0
        progressAnimator?.cancel()
        binding.progressBar.progress = 0
        binding.textProgress.text = getString(R.string.peer_discovery_progress_percent, 0)

        searchInProgress = true

        // Set batching parameters based on network type
        val (batchSize, concurrency, pauseMs) = NetworkUtils.getBatchingParams(requireContext())

        // Create callback
        val callback = object : PeerDiscoveryCallback {
            override fun onProgress(current: Long, total: Long, availableCount: Long) {
                mainHandler.post {
                    updateProgress(current.toInt(), total.toInt(), availableCount.toInt())
                }
            }

            override fun onPeerAvailable(peerJSON: String) {
                mainHandler.post {
                    try {
                        val peer = DiscoveredPeer.fromJson(org.json.JSONObject(peerJSON))
                        addDiscoveredPeer(peer)
                    } catch (e: Exception) {
                        // Ignore malformed JSON
                    }
                }
            }
        }

        // Start async discovery using helper (doesn't require running service)
        com.jbselfcompany.tyr.utils.PeerDiscoveryHelper.findAvailablePeersAsync(
            context = requireContext(),
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

    private fun updateProgress(current: Int, total: Int, availableCount: Int) {
        if (_binding == null) return

        // Update progress bar with smooth animation (1% increments)
        val progress = if (total > 0) (current * 100) / total else 0
        animateProgressTo(progress)

        // Auto-finish when complete
        if (current >= total && total > 0) {
            finishDiscovery()
        }
    }

    private fun addDiscoveredPeer(peer: DiscoveredPeer) {
        if (_binding == null) return

        discoveredPeers.add(peer)

        // Add peer dynamically with sorting by RTT
        adapter.addPeerSorted(peer)

        // Always scroll to top to show the fastest peers
        binding.recyclerPeers.scrollToPosition(0)
    }

    private fun finishDiscovery() {
        if (_binding == null) return

        searchInProgress = false

        // Hide progress
        binding.progressLayout.visibility = View.GONE
        binding.buttonFindPeers.isEnabled = true
        binding.switchUseDefault.isEnabled = true

        if (discoveredPeers.isEmpty()) {
            binding.peersCard.visibility = View.GONE
            binding.instructionsCard.visibility = View.VISIBLE
            Toast.makeText(requireContext(), R.string.peer_discovery_no_peers_found, Toast.LENGTH_LONG).show()
            return
        }

        // Peers are already added dynamically via addPeerSorted(), no need to update adapter

        // Show peers card
        binding.peersCard.visibility = View.VISIBLE

        // Cache the results
        configRepository.cacheDiscoveredPeers(discoveredPeers)

        Toast.makeText(
            requireContext(),
            getString(R.string.peer_discovery_found, discoveredPeers.size),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun showNoNetworkDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.peer_discovery_no_network)
            .setMessage(R.string.peer_discovery_no_network_message)
            .setPositiveButton(R.string.use_default_peers) { _, _ ->
                // Enable the default peers switch
                binding.switchUseDefault.isChecked = true
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * Get selected peers for validation in OnboardingActivity
     */
    fun getSelectedPeers(): List<DiscoveredPeer> {
        return adapter.getSelectedPeers()
    }

    /**
     * Check if user chose to use default peers
     */
    fun isUsingDefaultPeers(): Boolean {
        return useDefaultPeers
    }

    /**
     * Get manually entered peer URL, or null if empty
     */
    fun getManualPeerUrl(): String? {
        val url = binding.editManualPeer.text?.toString()?.trim()
        return if (url.isNullOrBlank()) null else url
    }

    /**
     * Validate that either peers are selected, default is chosen, or a manual peer is entered
     */
    fun hasValidSelection(): Boolean {
        return useDefaultPeers || adapter.getSelectedPeers().isNotEmpty() || !getManualPeerUrl().isNullOrBlank()
    }

    /**
     * Animate progress bar smoothly from current value to target (1% increments)
     * Speed: 300ms per 1% for consistent, visible animation
     */
    private fun animateProgressTo(targetProgress: Int) {
        if (_binding == null) return

        // Cancel previous animation
        progressAnimator?.cancel()

        // Create smooth animation with consistent speed (300ms per 1%)
        progressAnimator = android.animation.ValueAnimator.ofInt(currentProgress, targetProgress).apply {
            duration = ((targetProgress - currentProgress) * 300).toLong().coerceAtLeast(100)
            interpolator = android.view.animation.LinearInterpolator()

            addUpdateListener { animator ->
                if (_binding == null) {
                    cancel()
                    return@addUpdateListener
                }
                val value = animator.animatedValue as Int
                currentProgress = value
                binding.progressBar.progress = value
                binding.textProgress.text = getString(R.string.peer_discovery_progress_percent, value)
            }

            start()
        }
    }
}
