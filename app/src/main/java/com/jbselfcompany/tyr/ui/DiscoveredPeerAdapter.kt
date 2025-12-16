package com.jbselfcompany.tyr.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.chip.Chip
import com.jbselfcompany.tyr.R
import com.jbselfcompany.tyr.data.DiscoveredPeer

/**
 * Adapter for displaying discovered peers with checkbox selection
 */
class DiscoveredPeerAdapter(
    private val onSelectionChanged: (Int) -> Unit
) : RecyclerView.Adapter<DiscoveredPeerAdapter.DiscoveredPeerViewHolder>() {

    private val peers = mutableListOf<DiscoveredPeer>()
    private val selectedPeers = mutableSetOf<String>() // Track by address

    inner class DiscoveredPeerViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val checkbox: MaterialCheckBox = itemView.findViewById(R.id.checkbox_peer)
        val textAddress: TextView = itemView.findViewById(R.id.text_peer_address)
        val chipRtt: Chip = itemView.findViewById(R.id.chip_rtt)
        val chipRegion: Chip = itemView.findViewById(R.id.chip_region)

        init {
            // Handle checkbox click
            checkbox.setOnCheckedChangeListener { _, isChecked ->
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    val peer = peers[position]
                    if (isChecked) {
                        selectedPeers.add(peer.address)
                    } else {
                        selectedPeers.remove(peer.address)
                    }
                    onSelectionChanged(selectedPeers.size)
                }
            }

            // Handle item click (toggle checkbox)
            itemView.setOnClickListener {
                checkbox.isChecked = !checkbox.isChecked
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DiscoveredPeerViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_discovered_peer, parent, false)
        return DiscoveredPeerViewHolder(view)
    }

    override fun onBindViewHolder(holder: DiscoveredPeerViewHolder, position: Int) {
        val peer = peers[position]

        // Set checkbox state without triggering listener
        holder.checkbox.setOnCheckedChangeListener(null)
        holder.checkbox.isChecked = selectedPeers.contains(peer.address)
        holder.checkbox.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                selectedPeers.add(peer.address)
            } else {
                selectedPeers.remove(peer.address)
            }
            onSelectionChanged(selectedPeers.size)
        }

        // Set peer info
        holder.textAddress.text = peer.address
        holder.chipRtt.text = peer.getRttFormatted()

        // Show region chip only if region is not empty
        if (peer.region.isNotEmpty()) {
            holder.chipRegion.visibility = View.VISIBLE
            holder.chipRegion.text = peer.region.replaceFirstChar { it.uppercase() }
        } else {
            holder.chipRegion.visibility = View.GONE
        }
    }

    override fun getItemCount(): Int = peers.size

    /**
     * Update peer list with diff util for smooth animations
     */
    fun updatePeers(newPeers: List<DiscoveredPeer>) {
        val diffCallback = DiscoveredPeerDiffCallback(peers, newPeers)
        val diffResult = DiffUtil.calculateDiff(diffCallback)

        peers.clear()
        peers.addAll(newPeers)
        diffResult.dispatchUpdatesTo(this)
    }

    /**
     * Add a single peer in real-time and keep the list sorted by RTT
     * Note: This method uses notifyItemInserted which does NOT cause auto-scrolling.
     * The RecyclerView will maintain the user's current scroll position.
     */
    fun addPeerSorted(peer: DiscoveredPeer) {
        // Check if peer already exists
        if (peers.any { it.address == peer.address }) {
            return
        }

        // Find insertion position (binary search for better performance)
        val insertPosition = peers.binarySearch { it.rtt.compareTo(peer.rtt) }.let {
            if (it < 0) -(it + 1) else it
        }

        // Insert at the correct position
        peers.add(insertPosition, peer)

        // Notify about insertion without triggering auto-scroll
        notifyItemInserted(insertPosition)
    }

    /**
     * Select all peers
     */
    fun selectAll() {
        selectedPeers.clear()
        selectedPeers.addAll(peers.map { it.address })
        notifyDataSetChanged()
        onSelectionChanged(selectedPeers.size)
    }

    /**
     * Deselect all peers
     */
    fun deselectAll() {
        selectedPeers.clear()
        notifyDataSetChanged()
        onSelectionChanged(0)
    }

    /**
     * Get selected peers
     */
    fun getSelectedPeers(): List<DiscoveredPeer> {
        return peers.filter { selectedPeers.contains(it.address) }
    }

    /**
     * Get selection count
     */
    fun getSelectionCount(): Int = selectedPeers.size

    /**
     * DiffUtil callback for efficient list updates
     */
    private class DiscoveredPeerDiffCallback(
        private val oldList: List<DiscoveredPeer>,
        private val newList: List<DiscoveredPeer>
    ) : DiffUtil.Callback() {

        override fun getOldListSize(): Int = oldList.size

        override fun getNewListSize(): Int = newList.size

        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            return oldList[oldItemPosition].address == newList[newItemPosition].address
        }

        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            val old = oldList[oldItemPosition]
            val new = newList[newItemPosition]
            return old == new
        }
    }
}
