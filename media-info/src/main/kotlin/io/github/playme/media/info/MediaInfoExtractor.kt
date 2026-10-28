package io.github.playme.media.info

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * High-performance media metadata extractor.
 * Supports Kotlin Coroutines as well as Java / Groovy DSL callbacks.
 */
object MediaInfoExtractor {

    interface Callback {
        fun onSuccess(info: MediaInfo)
        fun onError(error: Throwable)
    }

    /**
     * Kotlin Coroutine function for non-blocking stream parsing.
     */
    suspend fun extract(filePath: String): MediaInfo? = withContext(Dispatchers.IO) {
        MediaInfoNativeBridge.nativeParseMediaInfo(filePath)
    }

    /**
     * Synchronous extraction method for Java / Groovy callers.
     */
    @JvmStatic
    fun extractSync(filePath: String): MediaInfo? {
        return MediaInfoNativeBridge.nativeParseMediaInfo(filePath)
    }

    /**
     * Asynchronous extraction with callback for Java / Groovy callers.
     */
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

