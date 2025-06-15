package com.oasis.alchemix.model

import java.io.File
import java.util.Date

data class VideoItem(
    val file: File,
    val name: String,
    val size: Long,
    val originalSize: Long = size, // Default to size for backward compatibility
    val dateModified: Date
) {
    fun getFormattedSize(): String {
        return formatFileSize(size)
    }
    
    fun getFormattedOriginalSize(): String {
        return formatFileSize(originalSize)
    }
    
    private fun formatFileSize(size: Long): String {
        if (size <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
        return String.format(
            "%.1f %s",
            size / Math.pow(1024.0, digitGroups.toDouble()),
            units[digitGroups.coerceAtMost(units.size - 1)]
        )
    }
}
