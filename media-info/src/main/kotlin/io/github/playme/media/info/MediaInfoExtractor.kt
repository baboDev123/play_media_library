package io.github.playme.media.info

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * High-performance media metadata extractor.
 * Fully supports Android 10+ Scoped Storage via Content Uris and ParcelFileDescriptors,
 * alongside standard file paths and network streams.
 */
object MediaInfoExtractor {

    interface Callback {
        fun onSuccess(info: MediaInfo)
        fun onError(error: Throwable)
    }

    // --- Kotlin Coroutines (Suspend) API ---

    /**
     * Extracts media metadata from a Content or File Uri (Recommended for Android 10+).
     */
    suspend fun extract(context: Context, uri: Uri): MediaInfo? = withContext(Dispatchers.IO) {
        extractSync(context, uri)
    }

    /**
     * Extracts media metadata directly from a ParcelFileDescriptor.
     */
    suspend fun extract(descriptor: ParcelFileDescriptor): MediaInfo? = withContext(Dispatchers.IO) {
        extractSync(descriptor)
    }

    /**
     * Extracts media metadata from a local file path or network URL.
     */
    suspend fun extract(filePath: String): MediaInfo? = withContext(Dispatchers.IO) {
        extractSync(filePath)
    }

    // --- Synchronous (Java / Groovy Friendly) API ---

    /**
     * Synchronously extracts media metadata from a Content or File Uri.
     */
    @JvmStatic
    fun extractSync(context: Context, uri: Uri): MediaInfo? {
        val scheme = uri.scheme?.lowercase()
        return when {
            scheme == "http" || scheme == "https" -> {
                extractSync(uri.toString())
            }
            scheme == "file" -> {
                uri.path?.let { extractSync(it) }
            }
            else -> {
                // Scoped Storage: content:// or android.resource://
                try {
                    context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                        extractSync(pfd)
                    }
                } catch (e: Throwable) {
                    null
                }
            }
        }
    }

    /**
     * Synchronously extracts media metadata directly from a ParcelFileDescriptor.
     */
    @JvmStatic
    fun extractSync(descriptor: ParcelFileDescriptor): MediaInfo? {
        return try {
            MediaInfoNativeBridge.nativeParseMediaInfoFd(descriptor.fd)
        } catch (e: Throwable) {
            null
        }
    }

    /**
     * Synchronously extracts media metadata from a file path or URL.
     */
    @JvmStatic
    fun extractSync(filePath: String): MediaInfo? {
        return try {
            MediaInfoNativeBridge.nativeParseMediaInfo(filePath)
        } catch (e: Throwable) {
            null
        }
    }

    // --- Asynchronous Callback (Java / Groovy Friendly) API ---

    @JvmStatic
    fun extractAsync(context: Context, uri: Uri, callback: Callback) {
        Thread {
            try {
                val info = extractSync(context, uri)
                if (info != null) {
                    callback.onSuccess(info)
                } else {
                    callback.onError(IllegalArgumentException("Unable to parse media for URI: $uri"))
                }
            } catch (t: Throwable) {
                callback.onError(t)
            }
        }.start()
    }

    @JvmStatic
    fun extractAsync(descriptor: ParcelFileDescriptor, callback: Callback) {
        Thread {
            try {
                val info = extractSync(descriptor)
                if (info != null) {
                    callback.onSuccess(info)
                } else {
                    callback.onError(IllegalArgumentException("Unable to parse media from file descriptor"))
                }
            } catch (t: Throwable) {
                callback.onError(t)
            }
        }.start()
    }

    @JvmStatic
    fun extractAsync(filePath: String, callback: Callback) {
        Thread {
            try {
                val info = extractSync(filePath)
                if (info != null) {
                    callback.onSuccess(info)
                } else {
                    callback.onError(IllegalArgumentException("Unable to parse media at $filePath"))
                }
            } catch (t: Throwable) {
                callback.onError(t)
            }
        }.start()
    }
}
