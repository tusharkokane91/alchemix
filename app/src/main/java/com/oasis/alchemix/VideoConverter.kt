package com.oasis.alchemix

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.Statistics
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class VideoConverter(private val context: Context) {

    data class VideoMetadata(
        val frameCount: Int,
        val durationMs: Long,
        val frameRate: Float
    )

    interface EventListener {
        fun onProgress(progress: Int, currentFrame: Int = -1, totalFrames: Int = -1)
        fun onStart(originalSize: Long, totalFrames: Int = -1)
        fun onSuccess(outputPath: String, originalSize: Long, newSize: Long)
        fun onFailure(error: String)
        fun onCancelled()
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentSession: FFmpegSession? = null
    private var listener: EventListener? = null
    private val isConverting = AtomicBoolean(false)

    private var lastProgressUpdateTime = 0L
    private var conversionStartTime = 0L
    private var totalFrames = -1
    private var lastFrameNumber = 0
    private val progressUpdateThreshold = 200 // Update at most every 200ms

    private fun getVideoMetadata(filePath: String): VideoMetadata? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(filePath)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val frameRate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull() ?: 30f
            val frameCount = (duration * frameRate / 1000).toInt()
            VideoMetadata(frameCount, duration, frameRate)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting video metadata", e)
            null
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing MediaMetadataRetriever", e)
            }
        }
    }

    fun convertToWebM(inputPath: String, outputPath: String) {
        // Input validation
        val inputFile = File(inputPath)
        if (!inputFile.exists()) {
            notifyFailure("Input file does not exist: $inputPath")
            return
        }

        if (isConverting.getAndSet(true)) {
            Log.w(TAG, "Conversion already in progress")
            return
        }
        conversionStartTime = System.currentTimeMillis()

        // Get video metadata including frame count
        val metadata = getVideoMetadata(inputPath)
        totalFrames = metadata?.frameCount ?: -1
        Log.d(TAG, "Video metadata - Total Frames: $totalFrames, Duration: ${metadata?.durationMs}ms, FPS: ${metadata?.frameRate}")
        
        // Notify listener about the start with total frames
        notifyOnMainThread { listener?.onStart(inputFile.length(), totalFrames) }

        try {
            // Prepare output directory
            val outputFile = File(outputPath)
            outputFile.parentFile?.mkdirs()

            // Get the number of available CPU cores (using at least 2 threads, but not more than 8)
            val threadCount = minOf(maxOf(Runtime.getRuntime().availableProcessors() - 1, 2), 8)
            Log.d(TAG, "Using $threadCount threads for conversion")

            // FFmpeg command for lossless WebM with VP9
            val cmd = arrayOf(
                "-y",                     // Overwrite output file if exists
                "-i", inputPath,           // Input file

                // Video encoding settings for WebM
                "-c:v", "libvpx-vp9",      // VP9 codec for WebM
                "-deadline", "realtime",    // Faster encoding
                "-cpu-used", "4",           // Faster encoding (0=best quality, 8=fastest)
                "-b:v", "3M",              // Target bitrate
                "-maxrate", "6M",           // Maximum bitrate
                "-minrate", "2M",           // Minimum bitrate
                "-pix_fmt", "yuv420p",      // Standard pixel format
                "-threads", threadCount.toString(),  // Use multiple threads
                "-row-mt", "1",             // Enable row-based multithreading
                "-tile-columns", "2",       // Enable tile-based multithreading
                "-auto-alt-ref", "1",       // Enable alternative reference frames
                "-lag-in-frames", "0",      // Disable lookahead for faster encoding

                // Audio settings for WebM
                "-c:a", "libopus",          // Opus is the standard for WebM
                "-b:a", "96k",              // Audio bitrate
                "-vbr", "on",               // Variable bitrate
                "-compression_level", "5",  // Balanced compression
                "-frame_duration", "20",    // 20ms frame size
                "-application", "audio",    // Optimize for audio
                "-f", "webm",               // WebM container
                "-vsync", "cfr",            // Constant frame rate
                "-progress", "pipe:1",
                outputPath                   // Output file
            )

            val originalSize = File(inputPath).length()

            // Notify listener that conversion is starting with the original file size
            notifyOnMainThread { listener?.onStart(originalSize) }

            // Execute FFmpeg command asynchronously
            currentSession = FFmpegKit.executeAsync(
                cmd.joinToString(" "),
                { session ->
                    isConverting.set(false)
                    val returnCode = session.returnCode

                    if (ReturnCode.isCancel(returnCode)) {
                        Log.d(TAG, "Conversion cancelled")
                        notifyOnMainThread { listener?.onCancelled() }
                        File(outputPath).delete() // Clean up partial output
                    } else if (ReturnCode.isSuccess(returnCode)) {
                        Log.d(TAG, "Conversion successful")
                        val newSize = File(outputPath).length()
                        notifyOnMainThread { listener?.onSuccess(outputPath, originalSize, newSize) }
                    } else {
                        val error = session.failStackTrace ?: "Unknown error"
                        Log.e(TAG, "Conversion failed: $error")
                        notifyFailure("Conversion failed: ${error.take(200)}")
                        File(outputPath).delete() // Clean up failed output
                    }
                },
                { log ->
                    // Log FFmpeg output for debugging
                    Log.d(TAG, log.message)
                },
                { statistics ->
                    updateProgress(statistics, inputFile.length())
                }
            )

        } catch (e: Exception) {
            isConverting.set(false)
            Log.e(TAG, "Conversion error", e)
            notifyFailure("Conversion error: ${e.message}")
        }
    }
    
    private fun updateProgress(statistics: Statistics?, inputFileSize: Long) {
        statistics?.let { stats ->
            try {
                val currentTime = System.currentTimeMillis()
                
                // Log raw statistics for debugging
                Log.d(TAG, "Raw stats - time: ${stats.time}ms, size: ${stats.size} bytes, " +
                        "bitrate: ${stats.bitrate} kbps, speed: ${stats.speed}x")
                
                // Always update progress, but only notify UI at certain intervals
                if (currentTime - lastProgressUpdateTime < progressUpdateThreshold) {
                    return@let
                }
                lastProgressUpdateTime = currentTime
                
                val timeInMs = stats.time.toLong()
                val speed = stats.speed
                val bitrate = stats.bitrate.toLong()
                val processedBytes = stats.size.toLong()
                val sizeKb = processedBytes / 1024
                
                // Calculate current frame number based on time and FPS (if available)
                var currentFrame = stats.videoFrameNumber
                
                // Calculate progress using the most reliable method available
                val progress = when {
                    // Method 1: Use frame count if available (most accurate)
                    totalFrames > 0 -> {
                        ((currentFrame * 100) / totalFrames).coerceIn(0, 99)
                    }
                    // Method 2: Use processed bytes vs total size if we have both
                    processedBytes > 0 && inputFileSize > 0 -> {
                        ((processedBytes * 100) / inputFileSize).toInt().coerceIn(0, 95)
                    }
                    // Method 3: Use bitrate and time if available
                    bitrate > 0 && timeInMs > 0 -> {
                        val estimatedTotalBytes = (bitrate * 1024 * timeInMs) / 8 // Convert kbps to bytes/ms
                        ((processedBytes * 100) / estimatedTotalBytes).toInt().coerceIn(0, 95)
                    }
                    // Fallback: Use time-based progress (very rough estimate)
                    else -> {
                        val elapsedSeconds = (currentTime - conversionStartTime) / 1000
                        elapsedSeconds.toInt().coerceAtMost(95)
                    }
                }

                Log.d(TAG, "Progress: $progress% - Frame: $currentFrame/$totalFrames - " +
                        "Time: ${timeInMs}ms, Processed: ${sizeKb}KB, Speed: ${speed}x")
                        
                notifyOnMainThread { listener?.onProgress(progress, currentFrame, totalFrames) }
            } catch (e: Exception) {
                Log.e(TAG, "Error in updateProgress", e)
                // Fallback to time-based progress in case of errors
                val fallbackProgress = ((System.currentTimeMillis() - conversionStartTime) / 1000).toInt().coerceAtMost(95)
                notifyOnMainThread { listener?.onProgress(fallbackProgress) }
            }
        } ?: run {
            // If we don't have statistics, use time-based progress
            val fallbackProgress = ((System.currentTimeMillis() - conversionStartTime) / 1000).toInt().coerceAtMost(95)
            notifyOnMainThread { listener?.onProgress(fallbackProgress) }
        }
    }

    private fun notifyOnMainThread(action: () -> Unit) {
        mainHandler.post {
            try {
                action()
            } catch (e: Exception) {
                Log.e(TAG, "Error in listener callback", e)
            }
        }
    }

    private fun notifyFailure(error: String) {
        Log.e(TAG, error)
        notifyOnMainThread { listener?.onFailure(error) }
    }

    fun setEventListener(listener: EventListener) {
        this.listener = listener
    }

    fun cancel() {
        if (!isConverting.getAndSet(false)) return

        try {
            currentSession?.let { session ->
                FFmpegKit.cancel(session.sessionId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error cancelling conversion", e)
        }
    }

    companion object {
        private const val TAG = "VideoConverter"
    }
}
