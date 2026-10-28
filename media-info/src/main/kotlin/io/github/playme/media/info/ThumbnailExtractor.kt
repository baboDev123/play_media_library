package io.github.playme.media.info

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.InputStream

/**
 * High-precision Video Thumbnail and Frame Extractor.
 *
 * Features:
 * - Scoped Storage (Content Uris)
 * - Raw FileDescriptors
 * - Network InputStreams (SMB, FTP, SFTP) via custom AVIOContext
 * - Keyframe-accurate seeking
 * - Upright rotation correction (90°, 180°, 270°)
 * - Embedded cover art fallback for audio tracks & albums
 * - Timeout and cancellation support
 */
object ThumbnailExtractor {

    interface Callback {
        fun onLoaded(bitmap: Bitmap)
        fun onError(error: PlayMediaException)
    }

    // --- Kotlin Coroutines (Suspend) API ---

    suspend fun getFrameAt(
        context: Context,
        uri: Uri,
        timeUs: Long = 0L,
        targetWidth: Int = 0,
        targetHeight: Int = 0,
        timeoutMs: Long = 5000L
    ): Bitmap? = withContext(Dispatchers.IO) {
        withTimeoutOrNull(timeoutMs) {
            getFrameAtSync(context, uri, timeUs, targetWidth, targetHeight)
        }
    }

    suspend fun getFrameAt(
        inputStream: InputStream,
        streamLength: Long = -1L,
        timeUs: Long = 0L,
        targetWidth: Int = 0,
        targetHeight: Int = 0,
        timeoutMs: Long = 5000L
    ): Bitmap? = withContext(Dispatchers.IO) {
        withTimeoutOrNull(timeoutMs) {
            getFrameAtSync(inputStream, streamLength, timeUs, targetWidth, targetHeight)
        }
    }

    suspend fun getFrameAt(
        descriptor: ParcelFileDescriptor,
        timeUs: Long = 0L,
        targetWidth: Int = 0,
        targetHeight: Int = 0
    ): Bitmap? = withContext(Dispatchers.IO) {
        getFrameAtSync(descriptor, timeUs, targetWidth, targetHeight)
    }

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
                val path = uri.path ?: return null
                getFrameAtSync(path, timeUs, targetWidth, targetHeight)
            }
            else -> {
                try {
                    context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                        getFrameAtSync(pfd, timeUs, targetWidth, targetHeight)
                    }
                } catch (e: Exception) {
                    null
                }
            }
        }
    }

    /**
     * Extracts frame from a network InputStream (SMB, FTP, SFTP) via native AVIOContext.
     */
    @JvmStatic
    @JvmOverloads
    fun getFrameAtSync(
        inputStream: InputStream,
        streamLength: Long = -1L,
        timeUs: Long = 0L,
        targetWidth: Int = 0,
        targetHeight: Int = 0
    ): Bitmap? {
        return MediaInfoNativeBridge.nativeExtractFrameAtTimeStream(
            inputStream, streamLength, timeUs, targetWidth, targetHeight
        )
    }

    @JvmStatic
    @JvmOverloads
    fun getFrameAtSync(
        descriptor: ParcelFileDescriptor,
        timeUs: Long = 0L,
        targetWidth: Int = 0,
        targetHeight: Int = 0
    ): Bitmap? {
        return MediaInfoNativeBridge.nativeExtractFrameAtTimeFd(
            descriptor.fd, timeUs, targetWidth, targetHeight
        )
    }

    @JvmStatic
    @JvmOverloads
    fun getFrameAtSync(
        filePath: String,
        timeUs: Long = 0L,
        targetWidth: Int = 0,
        targetHeight: Int = 0
    ): Bitmap? {
        return MediaInfoNativeBridge.nativeExtractFrameAtTime(
            filePath, timeUs, targetWidth, targetHeight
        )
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
            val bmp = getFrameAtSync(context, uri, timeUs, targetWidth, targetHeight)
            if (bmp != null) {
                callback.onLoaded(bmp)
            } else {
                callback.onError(PlayMediaException.CorruptStreamException(-1, "Unable to extract frame from $uri"))
            }
        }.start()
    }

    @JvmStatic
    @JvmOverloads
    fun getFrameAtAsync(
        inputStream: InputStream,
        streamLength: Long = -1L,
        timeUs: Long = 0L,
        targetWidth: Int = 0,
        targetHeight: Int = 0,
        callback: Callback
    ) {
        Thread {
            val bmp = getFrameAtSync(inputStream, streamLength, timeUs, targetWidth, targetHeight)
            if (bmp != null) {
                callback.onLoaded(bmp)
            } else {
                callback.onError(PlayMediaException.NetworkTimeoutException("Failed to decode frame from network InputStream"))
            }
        }.start()
    }
}
