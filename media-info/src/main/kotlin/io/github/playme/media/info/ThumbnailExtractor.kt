package io.github.playme.media.info

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Ultra-fast native video thumbnail and frame extractor.
 * Fully compatible with Android 10+ Scoped Storage (Content Uris & ParcelFileDescriptors).
 */
object ThumbnailExtractor {

    interface Callback {
        fun onLoaded(bitmap: Bitmap)
        fun onError(error: Throwable)
    }

    // --- Kotlin Coroutines (Suspend) API ---

    /**
     * Extracts a frame from a Content or File Uri (Recommended for Android 10+ Scoped Storage).
     */
    suspend fun getFrameAt(
        context: Context,
        uri: Uri,
        timeUs: Long = 0L,
        targetWidth: Int = 0,
        targetHeight: Int = 0
    ): Bitmap? = withContext(Dispatchers.IO) {
        getFrameAtSync(context, uri, timeUs, targetWidth, targetHeight)
    }

    /**
     * Extracts a frame directly from a ParcelFileDescriptor.
     */
    suspend fun getFrameAt(
        descriptor: ParcelFileDescriptor,
        timeUs: Long = 0L,
        targetWidth: Int = 0,
        targetHeight: Int = 0
    ): Bitmap? = withContext(Dispatchers.IO) {
        getFrameAtSync(descriptor, timeUs, targetWidth, targetHeight)
    }

    /**
     * Extracts a frame from a local file path or network URL.
     */
    suspend fun getFrameAt(
        filePath: String,
        timeUs: Long = 0L,
        targetWidth: Int = 0,
        targetHeight: Int = 0
    ): Bitmap? = withContext(Dispatchers.IO) {
        getFrameAtSync(filePath, timeUs, targetWidth, targetHeight)
    }

    // --- Synchronous (Java / Groovy Friendly) API ---

    @JvmStatic
    @JvmOverloads
    fun getFrameAtSync(
        context: Context,
        uri: Uri,
        timeUs: Long = 0L,
        targetWidth: Int = 0,
        targetHeight: Int = 0
    ): Bitmap? {
        val scheme = uri.scheme?.lowercase()
        return when {
            scheme == "http" || scheme == "https" -> {
                getFrameAtSync(uri.toString(), timeUs, targetWidth, targetHeight)
            }
            scheme == "file" -> {
                uri.path?.let { getFrameAtSync(it, timeUs, targetWidth, targetHeight) }
            }
            else -> {
                try {
                    context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                        getFrameAtSync(pfd, timeUs, targetWidth, targetHeight)
                    }
                } catch (e: Throwable) {
                    null
                }
            }
        }
    }

    @JvmStatic
    @JvmOverloads
    fun getFrameAtSync(
        descriptor: ParcelFileDescriptor,
        timeUs: Long = 0L,
        targetWidth: Int = 0,
        targetHeight: Int = 0
    ): Bitmap? {
        return try {
            MediaInfoNativeBridge.nativeExtractFrameAtTimeFd(descriptor.fd, timeUs, targetWidth, targetHeight)
        } catch (e: Throwable) {
            null
        }
    }

    @JvmStatic
    @JvmOverloads
    fun getFrameAtSync(
        filePath: String,
        timeUs: Long = 0L,
        targetWidth: Int = 0,
        targetHeight: Int = 0
    ): Bitmap? {
        return try {
            MediaInfoNativeBridge.nativeExtractFrameAtTime(filePath, timeUs, targetWidth, targetHeight)
        } catch (e: Throwable) {
            null
        }
    }

    // --- Asynchronous Callback (Java / Groovy Friendly) API ---

    @JvmStatic
    @JvmOverloads
    fun getFrameAtAsync(
        context: Context,
        uri: Uri,
        timeUs: Long = 0L,
        targetWidth: Int = 0,
        targetHeight: Int = 0,
        callback: Callback
    ) {
        Thread {
            try {
                val bitmap = getFrameAtSync(context, uri, timeUs, targetWidth, targetHeight)
                if (bitmap != null) {
                    callback.onLoaded(bitmap)
                } else {
                    callback.onError(IllegalArgumentException("Failed to decode frame from Uri: $uri"))
                }
            } catch (t: Throwable) {
                callback.onError(t)
            }
        }.start()
    }

    @JvmStatic
    @JvmOverloads
    fun getFrameAtAsync(
        descriptor: ParcelFileDescriptor,
        timeUs: Long = 0L,
        targetWidth: Int = 0,
        targetHeight: Int = 0,
        callback: Callback
    ) {
        Thread {
            try {
                val bitmap = getFrameAtSync(descriptor, timeUs, targetWidth, targetHeight)
                if (bitmap != null) {
                    callback.onLoaded(bitmap)
                } else {
                    callback.onError(IllegalArgumentException("Failed to decode frame from file descriptor"))
                }
            } catch (t: Throwable) {
                callback.onError(t)
            }
        }.start()
    }

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
                    callback.onError(IllegalArgumentException("Failed to decode frame at $timeUs us from $filePath"))
                }
            } catch (t: Throwable) {
                callback.onError(t)
            }
        }.start()
    }
}
