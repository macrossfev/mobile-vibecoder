package com.vibecoder.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.vibecoder.data.ServerConfig
import com.vibecoder.databinding.ItemServerBinding

class ServerAdapter(
    private val onServerClick: (ServerConfig) -> Unit,
    private val onServerLongClick: (ServerConfig) -> Unit,
    private val onMonitorClick: (ServerConfig) -> Unit
) : ListAdapter<ServerConfig, ServerAdapter.ViewHolder>(ServerDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemServerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemServerBinding) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                val pos = adapterPosition
                if (pos != RecyclerView.NO_POSITION) onServerClick(getItem(pos))
            }

            binding.root.setOnLongClickListener {
                val pos = adapterPosition
                if (pos != RecyclerView.NO_POSITION) onServerLongClick(getItem(pos))
                true
            }

            binding.btnMonitor.setOnClickListener {
                val pos = adapterPosition
                if (pos != RecyclerView.NO_POSITION) onMonitorClick(getItem(pos))
            }
        }

        fun bind(server: ServerConfig) {
            binding.apply {
                tvServerName.text = server.name
                tvServerAddress.text = server.getDisplayAddress()
                tvServerUser.text = server.username

                try {
                    viewColorIndicator.setBackgroundColor(Color.parseColor(server.color))
                } catch (e: Exception) {
                    viewColorIndicator.setBackgroundColor(Color.parseColor("#4CAF50"))
                }

                if (server.lastConnected > 0) {
                    val lastTime = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                        .format(java.util.Date(server.lastConnected))
                    tvLastConnected.text = "上次连接: $lastTime"
                    tvLastConnected.visibility = android.view.View.VISIBLE
                } else {
                    tvLastConnected.visibility = android.view.View.GONE
                }
            }
        }
    }

    class ServerDiffCallback : DiffUtil.ItemCallback<ServerConfig>() {
        override fun areItemsTheSame(oldItem: ServerConfig, newItem: ServerConfig): Boolean = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: ServerConfig, newItem: ServerConfig): Boolean = oldItem == newItem
    }
}