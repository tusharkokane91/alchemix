package com.oasis.alchemix.utils

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log

/**
 * Utility functions for file operations
 */

/**
 * Extension function to get filename from Uri
 */
fun Uri.getFileName(context: Context): String? {
    var fileName: String? = null
    
    try {
        if (scheme == "content") {
            context.contentResolver.query(this, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1 && cursor.moveToFirst()) {
                    fileName = cursor.getString(nameIndex)
                }
            }
        }
        
        // Fallback to last path segment if display name is not found
        if (fileName.isNullOrEmpty()) {
            fileName = lastPathSegment
        }
    } catch (e: Exception) {
        Log.e("FileUtils", "Error getting filename from Uri", e)
    }
    
    return fileName
}

/**
 * Gets the filename without extension from a full path or filename
 */
fun String.getFileNameWithoutExtension(): String {
    val fileName = substringAfterLast('/').substringAfterLast('\\')
    val dotIndex = fileName.lastIndexOf('.')
    return if (dotIndex > 0) fileName.substring(0, dotIndex) else fileName
}
