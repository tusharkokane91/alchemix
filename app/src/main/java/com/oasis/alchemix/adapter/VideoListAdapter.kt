package com.oasis.alchemix.adapter

import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.oasis.alchemix.R
import com.oasis.alchemix.model.VideoItem
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt

class VideoListAdapter(
    private val onItemClick: (VideoItem) -> Unit,
    private val onPlayClick: (VideoItem) -> Unit,
    private val onDeleteClick: (VideoItem) -> Unit
) : RecyclerView.Adapter<VideoListAdapter.VideoViewHolder>() {

    private val videos = mutableListOf<VideoItem>()
    private val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())

    fun updateVideos(newVideos: List<VideoItem>) {
        Log.d("VideoListAdapter", "Updating videos list. New count: ${newVideos.size}")
        Log.d("VideoListAdapter", "New videos: ${newVideos.joinToString("\n") { "- ${it.name} (${it.file.absolutePath})" }}")
        
        videos.clear()
        videos.addAll(newVideos)
        Log.d("VideoListAdapter", "Videos list updated. Current count: ${videos.size}")
        
        try {
            notifyDataSetChanged()
            Log.d("VideoListAdapter", "notifyDataSetChanged() called")
        } catch (e: Exception) {
            Log.e("VideoListAdapter", "Error in notifyDataSetChanged", e)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_video, parent, false)
        return VideoViewHolder(view)
    }

    override fun onBindViewHolder(holder: VideoViewHolder, position: Int) {
        val video = videos[position]
        
        // Set video name
        holder.videoName.text = video.name
        
        // Set sizes with labels
        val compressedSize = video.getFormattedSize()
        val originalSize = video.getFormattedOriginalSize()
        
        // Always show both sizes with labels
        holder.videoOriginalSize.text = "Original: $originalSize"
        holder.videoSize.text = "Compressed: $compressedSize"
        
        // Make sure both are visible
        holder.itemView.findViewById<View>(R.id.iconOriginal).visibility = View.VISIBLE
        holder.videoOriginalSize.visibility = View.VISIBLE
        
        // Debug log the values
        Log.d("VideoAdapter", "Original: ${video.originalSize} bytes, Compressed: ${video.size} bytes")
        
        // Format date
        holder.videoDate.text = dateFormat.format(video.dateModified)
        
        // Load thumbnail using Glide
        Glide.with(holder.itemView.context)
            .load(video.file)
            .placeholder(android.R.drawable.ic_media_play)
            .centerCrop()
            .into(holder.thumbnail)
        
        // Set click listeners
        holder.itemView.setOnClickListener { onItemClick(video) }
        holder.btnPlay.setOnClickListener { onPlayClick(video) }
        holder.btnDelete.setOnClickListener { onDeleteClick(video) }
    }

    override fun getItemCount() = videos.size

    inner class VideoViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val videoName: TextView = itemView.findViewById(R.id.videoName)
        val videoSize: TextView = itemView.findViewById(R.id.videoSize)
        val videoOriginalSize: TextView = itemView.findViewById(R.id.videoOriginalSize)
        val videoDate: TextView = itemView.findViewById(R.id.videoDate)
        val thumbnail: ImageView = itemView.findViewById(R.id.thumbnail)
        val btnPlay: ImageButton = itemView.findViewById(R.id.btnPlay)
        val btnDelete: ImageButton = itemView.findViewById(R.id.btnDelete)
    }
}
