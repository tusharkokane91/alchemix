package com.oasis.alchemix

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import android.view.View
import android.widget.Toast
import com.oasis.alchemix.utils.getFileName
import com.oasis.alchemix.utils.getFileNameWithoutExtension
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.oasis.alchemix.adapter.VideoListAdapter
import com.oasis.alchemix.databinding.ActivityMainBinding
import com.oasis.alchemix.model.VideoItem
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var selectedVideoUri: Uri? = null
    private lateinit var videoConverter: VideoConverter
    private lateinit var videoAdapter: VideoListAdapter
    private var outputPath: String = ""
    
    // Date formatter for filenames
    private val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
    
    private val pickMedia = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { handleVideoSelected(it) }
    }
    
    private fun handleVideoSelected(uri: Uri) {
        selectedVideoUri = uri
        
        // Get the original file size before conversion
        val inputPath = getRealPathFromUri(uri) ?: run {
            showError("Could not access the video file")
            return
        }
        
        val originalFile = File(inputPath)
        originalSizes[originalFile.name] = originalFile.length()
        Log.d("VideoSize", "Stored original size for ${originalFile.name}: ${originalFile.length()} bytes")
        
        showConversionDialog()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize the video adapter first
        setupUI()

        // Check and request permissions
        if (!hasStoragePermissions()) {
            requestStoragePermissions()
        } else {
            loadVideos()
        }
        
        // Load saved files
        val savedFiles = getConvertedFiles()
        originalSizes.putAll(savedFiles)
        Log.d("MainActivity", "Loaded ${savedFiles.size} saved files from SharedPreferences")
        
        // Initialize VideoConverter with event listener
        videoConverter = VideoConverter(this).apply {
            setEventListener(object : VideoConverter.EventListener {
                override fun onProgress(progress: Int, currentFrame: Int, totalFrames: Int) {
                    this@MainActivity.totalFrames = totalFrames
                    updateProgress(progress, currentFrame, totalFrames)
                }

                override fun onStart(originalSize: Long, totalFrames: Int) {
                    this@MainActivity.totalFrames = totalFrames
                    Log.d("VideoConverter", "Conversion started. Original size: $originalSize bytes, Total frames: $totalFrames")
                    runOnUiThread {
                        updateUIForConversionStart()
                    }
                }

                override fun onSuccess(outputPath: String, originalSize: Long, newSize: Long) {
                    runOnUiThread {
                        try {
                            val outputFile = File(outputPath)
                            if (!outputFile.exists()) {
                                Log.e("ConvertVideo", "Output file not found: $outputPath")
                                showError("Error: Output file was not created")
                                updateUIForIdle("Conversion failed")
                                return@runOnUiThread
                            }
                            
                            Log.d("ConvertVideo", "Conversion successful. File saved to: $outputPath")
                            Log.d("ConvertVideo", "File size: ${outputFile.length()} bytes")
                            
                            // Update the media store so the file appears in gallery
                            val mediaScanIntent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
                            mediaScanIntent.data = Uri.fromFile(outputFile)
                            sendBroadcast(mediaScanIntent)
                            
                            updateUIForSuccess(outputPath)
                            showConversionCompleteDialog(
                                outputPath,
                                "Original: ${String.format("%.2f", originalSize / (1024.0 * 1024.0))}MB\n" +
                                "Compressed: ${String.format("%.2f", newSize / (1024.0 * 1024.0))}MB\n" +
                                "Reduction: ${String.format("%.1f", 100.0 - (newSize * 100.0 / originalSize))}%"
                            )
                            
                            // Refresh the video list
                            loadVideos()
                            
                        } catch (e: Exception) {
                            Log.e("ConvertVideo", "Error handling successful conversion", e)
                            showError("Error: ${e.message}")
                            updateUIForIdle("Conversion completed with issues")
                        }
                    }
                }

                override fun onFailure(error: String) {
                    runOnUiThread {
                        updateUIForIdle("Conversion failed")
                        showError(error)
                    }
                }

                override fun onCancelled() {
                    runOnUiThread {
                        updateUIForIdle("Conversion cancelled")
                    }
                }
            })
        }
        
        // Moved setupUI() to before loading videos to ensure adapter is initialized
    }

    private fun setupUI() {
        videoAdapter = VideoListAdapter(
            onItemClick = { video ->
                // Handle item click (e.g., show details)
                showVideoDetails(video)
            },
            onPlayClick = { video ->
                // Handle play button click
                playVideo(video)
            },
            onDeleteClick = { video ->
                // Handle delete button click
                showDeleteConfirmation(video)
            }
        )
        
        binding.apply {
            // Set up RecyclerView
            videosRecyclerView.apply {
                layoutManager = LinearLayoutManager(this@MainActivity)
                adapter = videoAdapter
                setHasFixedSize(true)
            }
            
            // Set up FAB click listener
            selectVideoButton.setOnClickListener {
                pickMedia.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                )
            }
            
            // Set up cancel button click listener
            cancelButton.setOnClickListener {
                videoConverter.cancel()
                updateUIForIdle("Conversion cancelled")
            }
        }
    }

    private fun showConversionDialog() {
        AlertDialog.Builder(this)
            .setTitle("Convert to WebM")
            .setMessage("Do you want to convert the selected video to WebM format?")
            .setPositiveButton("Convert") { _, _ ->
                selectedVideoUri?.let { convertVideo(it) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun getAppMoviesDirectory(): File {
        val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        return File(moviesDir, "Alchemix").apply {
            if (!exists()) {
                mkdirs()
            }
        }
    }
    
    private fun convertVideo(inputUri: Uri) {
        try {
            // First try to get the original filename from the Uri
            val originalFileName = inputUri.getFileName(this) ?: run {
                showError("Error: Could not get original filename")
                return
            }
            
            Log.d("ConvertVideo", "Original filename from URI: $originalFileName")
            
            // Get the filename without extension
            val baseName = originalFileName.getFileNameWithoutExtension()
            
            // Get or create app's directory in Movies
            val appDir = getAppMoviesDirectory()
            
            // Create output filename with _alchemix.webm suffix
            val outputFileName = "${baseName}_alchemix.webm"
            val outputFile = File(appDir, outputFileName)
            
            Log.d("ConvertVideo", "Output will be saved as: ${outputFile.absolutePath}")

            // For logging and size tracking, we'll still need the input file
            val inputPath = getRealPathFromUri(inputUri) ?: run {
                showError("Error: Could not access the video file")
                return
            }
            val inputFile = File(inputPath)
            Log.d("ConvertVideo", "Input file: ${inputFile.absolutePath}, size: ${inputFile.length()} bytes")

            // Ensure the output directory exists
            if (!appDir.exists()) {
                if (!appDir.mkdirs()) {
                    showError("Failed to create output directory")
                    return
                }
            }

            // Delete any existing file with the same name
            if (outputFile.exists()) {
                if (!outputFile.delete()) {
                    Log.e("ConvertVideo", "Failed to delete existing output file: ${outputFile.absolutePath}")
                    showError("Failed to prepare output file")
                    return
                }
            }
            
            outputPath = outputFile.absolutePath
            Log.d("ConvertVideo", "Output will be saved to: $outputPath")
            Log.d("ConvertVideo", "Output filename: $outputFileName")
            
            // Store the original size and track the converted file
            val originalSize = inputFile.length()
            addConvertedFile(outputPath, originalSize)
            
            Log.d("ConvertVideo", "Original size: $originalSize bytes")
            Log.d("ConvertVideo", "Output will be saved as: $outputPath")
            
            // Reset UI
            updateUIForConversionStart()
            
            // Start conversion
            videoConverter.convertToWebM(inputPath, outputPath)
            
        } catch (e: Exception) {
            Log.e("ConvertVideo", "Error during conversion setup", e)
            showError("Error setting up conversion: ${e.message}")
            updateUIForIdle("Conversion failed")
        }
    }
    
    // Region: UI Update Methods
    private fun updateProgress(progress: Int, currentFrame: Int = -1, totalFrames: Int = -1) {
        binding.apply {
            progressBar.progress = progress
            
            val progressText = if (currentFrame >= 0 && totalFrames > 0) {
                "Converting: $progress% (Frame $currentFrame/$totalFrames)"
            } else {
                "Converting: $progress%"
            }
            statusText.text = progressText
        }
    }
    
    private fun updateUIForConversionStart() {
        binding.apply {
            progressBar.visibility = View.VISIBLE
            progressBar.progress = 0
            statusText.text = "Starting conversion..."
            selectVideoButton.isEnabled = false
            cancelButton.visibility = View.VISIBLE
        }
    }
    
    private fun updateUIForSuccess(outputPath: String) {
        binding.apply {
            progressBar.visibility = View.GONE
            statusText.text = "Conversion complete!"
            selectVideoButton.isEnabled = true
            cancelButton.visibility = View.GONE
        }
    }
    
    private fun updateUIForIdle(message: String) {
        binding.apply {
            progressBar.visibility = View.GONE
            statusText.text = message
            selectVideoButton.isEnabled = true
            cancelButton.visibility = View.GONE
        }
    }
    
    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
    
    private fun showConversionCompleteDialog(outputPath: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle("Conversion Complete")
            .setMessage("${File(outputPath).name}\n\n$message")
            .setPositiveButton("OK") { dialog, _ ->
                dialog.dismiss()
            }
            .setNegativeButton("Share") { _, _ ->
                shareVideo(outputPath)
            }
            .show()
    }
    // End Region
    
    private fun shareVideo(filePath: String) {
        // Implement share functionality
        Toast.makeText(this, "Share functionality would go here", Toast.LENGTH_SHORT).show()
    }
                
    // Store original sizes when files are first selected
    private val originalSizes = mutableMapOf<String, Long>()
    private val prefs by lazy { getSharedPreferences("video_prefs", Context.MODE_PRIVATE) }
    private val CONVERTED_FILES_KEY = "converted_files"
    private var totalFrames = -1
    
    // Storage Permissions
    private val PERMISSIONS_STORAGE = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        arrayOf(
            android.Manifest.permission.READ_MEDIA_VIDEO,
            android.Manifest.permission.READ_MEDIA_IMAGES
        )
    } else {
        arrayOf(
            android.Manifest.permission.READ_EXTERNAL_STORAGE,
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
    }

    private fun addConvertedFile(filePath: String, originalSize: Long) {
        // Add to memory
        originalSizes[filePath] = originalSize
        
        // Save to SharedPreferences
        val convertedFiles = getConvertedFiles().toMutableMap()
        convertedFiles[filePath] = originalSize
        
        prefs.edit()
            .putString(CONVERTED_FILES_KEY, convertedFiles.entries.joinToString("|") {
                "${it.key},${it.value}"
            })
            .apply()
    }
    
    private fun getConvertedFiles(): Map<String, Long> {
        val files = mutableMapOf<String, Long>()
        val saved = prefs.getString(CONVERTED_FILES_KEY, "") ?: ""
        
        saved.split("|")
            .filter { it.isNotBlank() }
            .forEach { entry ->
                try {
                    val (path, size) = entry.split(",")
                    files[path] = size.toLong()
                } catch (e: Exception) {
                    Log.e("MainActivity", "Error parsing converted file entry: $entry", e)
                }
            }
        
        return files
    }
    
    private fun loadVideos() {
        Log.d("LoadVideos", "===== Starting to load videos =====")
        
        // 1. Get all files from the app's Movies/Alchemix directory
        val appDir = getAppMoviesDirectory()
        Log.d("LoadVideos", "Loading videos from: ${appDir.absolutePath}")
        
        val videoFiles = try {
            if (appDir.exists() && appDir.isDirectory) {
                appDir.listFiles { _, name ->
                    name?.let { 
                        it.endsWith(".webm", ignoreCase = true) || 
                        it.endsWith(".mp4", ignoreCase = true) || 
                        it.endsWith(".mkv", ignoreCase = true)
                    } ?: false
                }?.toList() ?: emptyList()
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            Log.e("LoadVideos", "Error reading app directory", e)
            emptyList()
        }
        
        Log.d("LoadVideos", "Found ${videoFiles.size} video files in app directory")
        
        // 2. Create VideoItem objects for each file
        val videoItems = videoFiles
            .sortedByDescending { it.lastModified() }
            .mapNotNull { file ->
            try {
                val fileLength = file.length()
                if (fileLength == 0L) {
                    Log.w("LoadVideos", "Skipping zero-length file: ${file.absolutePath}")
                    return@mapNotNull null
                }
                
                // Use tracked original size if available, otherwise use current size
                val originalSize = originalSizes[file.absolutePath] ?: fileLength
                
                VideoItem(
                    file = file,
                    name = file.name,
                    size = fileLength,
                    originalSize = originalSize,
                    dateModified = Date(file.lastModified())
                ).also { videoItem ->
                    Log.d("VideoItem", "\n=== Video Item ===")
                    Log.d("VideoItem", "File: ${file.absolutePath}")
                    Log.d("VideoItem", "Name: ${file.name}")
                    Log.d("VideoItem", "Size: ${fileLength} bytes (${videoItem.getFormattedSize()})")
                    Log.d("VideoItem", "Original size: $originalSize bytes (${videoItem.getFormattedOriginalSize()})")
                }
            } catch (e: Exception) {
                Log.e("LoadVideos", "Error creating video item for ${file.absolutePath}", e)
                null
            }
        }
        
        Log.d("LoadVideos", "Created ${videoItems.size} valid video items")
        
        runOnUiThread {
            Log.d("LoadVideos", "Updating UI with ${videoItems.size} videos")
            Log.d("LoadVideos", "Video list empty: ${videoItems.isEmpty()}")
            Log.d("LoadVideos", "Empty view visibility: ${if (videoItems.isEmpty()) "VISIBLE" else "GONE"}")
            
            // Update the adapter
            videoAdapter.updateVideos(videoItems)
            
            // Update empty view visibility
            binding.emptyView.visibility = if (videoItems.isEmpty()) View.VISIBLE else View.GONE
            
            // Log RecyclerView state
            Log.d("LoadVideos", "RecyclerView child count: ${binding.videosRecyclerView.childCount}")
            Log.d("LoadVideos", "RecyclerView adapter count: ${binding.videosRecyclerView.adapter?.itemCount}")
            
            // Force a layout pass
            binding.videosRecyclerView.requestLayout()
        }
    }
    
    private fun updateEmptyView(videoItems: List<Any>) {
        runOnUiThread {
            binding.emptyView.visibility = if (videoItems.isEmpty()) View.VISIBLE else View.GONE
        }
    }
    
    private fun hasStoragePermissions(): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            checkSelfPermission(android.Manifest.permission.READ_MEDIA_VIDEO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }
    
    private fun requestStoragePermissions() {
        if (shouldShowRequestPermissionRationale(android.Manifest.permission.READ_EXTERNAL_STORAGE) ||
            shouldShowRequestPermissionRationale(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ||
            shouldShowRequestPermissionRationale(android.Manifest.permission.READ_MEDIA_VIDEO)) {
            
            AlertDialog.Builder(this)
                .setTitle("Storage Permission Required")
                .setMessage("This app needs storage permission to access and manage your video files.")
                .setPositiveButton("OK") { _, _ ->
                    requestPermissions(
                        PERMISSIONS_STORAGE,
                        Companion.PERMISSION_REQUEST_CODE
                    )
                }
                .setNegativeButton("Cancel") { dialog, _ ->
                    dialog.dismiss()
                    showError("Storage permission is required to use this app")
                }
                .show()
        } else {
            requestPermissions(
                PERMISSIONS_STORAGE,
                Companion.PERMISSION_REQUEST_CODE
            )
        }
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        if (requestCode == Companion.PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == android.content.pm.PackageManager.PERMISSION_GRANTED }) {
                // Permissions granted, load videos
                loadVideos()
            } else {
                showError("Storage permission is required to use this app")
            }
        }
    }
    
    private fun showVideoDetails(video: VideoItem) {
        val sizeMB = String.format("%.2f", video.size / (1024.0 * 1024.0))
        val originalSizeMB = String.format("%.2f", video.originalSize / (1024.0 * 1024.0))
        val date = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
            .format(video.dateModified)
            
        AlertDialog.Builder(this)
            .setTitle("Video Details")
            .setMessage(
                "Name: ${video.name}\n" +
                "Original Size: ${originalSizeMB}MB\n" +
                "Compressed Size: ${sizeMB}MB\n" +
                "Date: $date"
            )
            .setPositiveButton("Play") { _, _ -> playVideo(video) }
            .setNeutralButton("Share") { _, _ -> shareVideo(video.file.absolutePath) }
            .setNegativeButton("Delete") { _, _ -> showDeleteConfirmation(video) }
            .show()
    }
    
    private fun showDeleteConfirmation(video: VideoItem) {
        AlertDialog.Builder(this)
            .setTitle("Delete Video")
            .setMessage("Are you sure you want to delete ${video.name}?")
            .setPositiveButton("Delete") { _, _ -> 
                deleteVideo(video)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun deleteVideo(video: VideoItem) {
        try {
            if (video.file.delete()) {
                // Remove from original sizes map if it exists
                originalSizes.remove(video.file.name)
                // Reload the video list
                loadVideos()
                Toast.makeText(this, "Video deleted", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Failed to delete video", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error deleting video", e)
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun playVideo(video: VideoItem) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(
                FileProvider.getUriForFile(
                    this@MainActivity,
                    "${applicationContext.packageName}.fileprovider",
                    video.file
                ),
                "video/*"
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(intent)
    }

    fun getRealVideoFileName(context: Context, uri: Uri): String? {
        var fileName: String? = null
        val projection = arrayOf(
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH // optional, to get folder info
        )

        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                val pathIndex = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)

                val name = if (nameIndex != -1) cursor.getString(nameIndex) else null
                val path = if (pathIndex != -1) cursor.getString(pathIndex) else null

                fileName = if (path != null) "$path$name" else name
            }
        }

        return fileName
    }

    private fun getRealPathFromUri(uri: Uri): String? {
        if (uri.scheme == "file") {
            return uri.path
        }
        
        var result: String? = null
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            cursor.moveToFirst()
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx != -1) {
                val fileName = cursor.getString(idx)
                val tempFile = File(cacheDir, fileName)
                contentResolver.openInputStream(uri)?.use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                result = tempFile.absolutePath
            }
        }
        return result
    }

    companion object {
        private const val PERMISSION_REQUEST_CODE = 1001
    }
}