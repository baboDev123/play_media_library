package io.github.playme.media.info

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream

/**
 * Enterprise media metadata extractor.
 * Supports:
 * - Local file paths & URLs
 * - Android 10+ Scoped Storage (Content Uris)
 * - Raw FileDescriptors
 * - Network InputStreams (SMB, FTP, SFTP, HTTP) via custom AVIOContext
 */
object MediaInfoExtractor {

    interface Callback {
        fun onSuccess(info: MediaInfo)
        fun onError(error: PlayMediaException)
    }

    // --- Kotlin Coroutines (Suspend) API ---

    suspend fun extract(context: Context, uri: Uri): MediaInfo = withContext(Dispatchers.IO) {
        extractSync(context, uri)
    }

    suspend fun extract(descriptor: ParcelFileDescriptor): MediaInfo = withContext(Dispatchers.IO) {
        extractSync(descriptor)
    }

    suspend fun extract(filePath: String): MediaInfo = withContext(Dispatchers.IO) {
        extractSync(filePath)
    }

    suspend fun extract(inputStream: InputStream, streamLength: Long = -1L): MediaInfo = withContext(Dispatchers.IO) {
        extractSync(inputStream, streamLength)
    }

    // --- Synchronous (Java / Groovy Friendly) API ---

    @JvmStatic
    fun extractSync(context: Context, uri: Uri): MediaInfo {
        val scheme = uri.scheme?.lowercase()
        return when {
            scheme == "http" || scheme == "https" -> {
                extractSync(uri.toString())
            }
            scheme == "file" -> {
                val path = uri.path ?: throw PlayMediaException.InvalidDataSourceException("File URI has null path")
                extractSync(path)
            }
            else -> {
                try {
                    context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                        extractSync(pfd)
                    } ?: throw PlayMediaException.InvalidDataSourceException("ContentResolver returned null descriptor for $uri")
                } catch (e: Exception) {
                    if (e is PlayMediaException) throw e
                    throw PlayMediaException.InvalidDataSourceException("Failed to open Content URI: ${e.message}")
                }
            }
        }
    }

    @JvmStatic
    fun extractSync(descriptor: ParcelFileDescriptor): MediaInfo {
        val info = MediaInfoNativeBridge.nativeParseMediaInfoFd(descriptor.fd)
        return info ?: throw PlayMediaException.CorruptStreamException(-1, "Unable to parse media from FileDescriptor")
    }

    @JvmStatic
    fun extractSync(filePath: String): MediaInfo {
        val info = MediaInfoNativeBridge.nativeParseMediaInfo(filePath)
        return info ?: throw PlayMediaException.CorruptStreamException(-1, "Unable to parse media at $filePath")
    }

    /**
     * Reads metadata directly from a network InputStream (SMB, FTP, SFTP, HTTP) via native AVIOContext.
     */
    @JvmStatic
    @JvmOverloads
    fun extractSync(inputStream: InputStream, streamLength: Long = -1L): MediaInfo {
        val info = MediaInfoNativeBridge.nativeParseMediaInfoStream(inputStream, streamLength)
        return info ?: throw PlayMediaException.NetworkTimeoutException("Failed to probe stream from network InputStream")
    }

    // --- Asynchronous Callback (Java / Groovy Friendly) API ---

    @JvmStatic
    fun extractAsync(context: Context, uri: Uri, callback: Callback) {
        Thread {
            try {
                callback.onSuccess(extractSync(context, uri))
            } catch (e: PlayMediaException) {
                callback.onError(e)
            } catch (t: Throwable) {
                callback.onError(PlayMediaException.CorruptStreamException(-1, t.message ?: "Unknown error"))
            }
        }.start()
    }

    @JvmStatic
    @JvmOverloads
    fun extractAsync(inputStream: InputStream, streamLength: Long = -1L, callback: Callback) {
        Thread {
            try {
                callback.onSuccess(extractSync(inputStream, streamLength))
            } catch (e: PlayMediaException) {
                callback.onError(e)
            } catch (t: Throwable) {
                callback.onError(PlayMediaException.NetworkTimeoutException(t.message ?: "Network stream failure"))
            }
        }.start()
    }

    @JvmStatic
    fun extractAsync(filePath: String, callback: Callback) {
        Thread {
            try {
                callback.onSuccess(extractSync(filePath))
            } catch (e: PlayMediaException) {
                callback.onError(e)
            } catch (t: Throwable) {
                callback.onError(PlayMediaException.CorruptStreamException(-1, t.message ?: "Failed to open media"))
            }
        }.start()
    }
}
