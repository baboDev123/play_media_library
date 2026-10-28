package io.github.playme.media.info

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Ultra-fast native video thumbnail and frame extractor.
 */
object ThumbnailExtractor {

    interface Callback {
        fun onLoaded(bitmap: Bitmap)
        fun onError(error: Throwable)
    }

    /**
     * Non-blocking frame extraction using Kotlin Coroutines.
     * @param filePath Absolute path of media file or network URI.
     * @param timeUs Desired timestamp in microseconds.
     * @param targetWidth Optional target width for automatic downscaling (0 for original).
     * @param targetHeight Optional target height for automatic downscaling (0 for original).
     */
    suspend fun getFrameAt(
        filePath: String,
        timeUs: Long = 0L,
        targetWidth: Int = 0,
        targetHeight: Int = 0
    ): Bitmap? = withContext(Dispatchers.IO) {
        MediaInfoNativeBridge.nativeExtractFrameAtTime(filePath, timeUs, targetWidth, targetHeight)
    }

    /**
     * Synchronous frame extraction for Java / Groovy callers.
     */
    @JvmStatic
    @JvmOverloads
    fun getFrameAtSync(
        filePath: String,
        timeUs: Long = 0L,
        targetWidth: Int = 0,
        targetHeight: Int = 0
    ): Bitmap? {
        return MediaInfoNativeBridge.nativeExtractFrameAtTime(filePath, timeUs, targetWidth, targetHeight)
    }

    /**
     * Callback-based frame extraction for Java / Groovy callers.
     */
    @JvmStatic
    @JvmOverloads
    fun getFrameAtAsync(
        filePath: String,
        timeUs: Long = 0L,
        targetWidth: Int = 0,
        targetHeight: Int = 0,
        callback: Callback
    ) {
        Thread {
            try {
                val bitmap = getFrameAtSync(filePath, timeUs, targetWidth, targetHeight)
                if (bitmap != null) {
                    callback.onLoaded(bitmap)
                } else {
                    callback.onError(IllegalArgumentException("Failed to decode frame at $timeUs us"))
                }
            } catch (t: Throwable) {
                callback.onError(t)
            }
        }.start()
    }
}

