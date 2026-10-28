package io.github.playme.media.info

import android.content.Context
import android.media.MediaDataSource
import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream

/**
 * Reads a container's facts: its streams (codecs, resolution, HDR, languages...), chapters,
 * attachments and cover art. Every input is read with seeking, so formats with their index at
 * the end (MP4) work too; a read that takes longer than [DEFAULT_TIMEOUT_MS] gives up.
 *
 * The suspending functions run on [Dispatchers.IO]; the `Sync` ones block the calling thread.
 * All of them throw [PlayMediaException] when the media can't be read.
 */
object MediaInfoExtractor {

    const val DEFAULT_TIMEOUT_MS = 15_000L

    interface Callback {
        fun onSuccess(info: MediaInfo)
        fun onError(error: PlayMediaException)
    }

    // --- Suspending API ---

    suspend fun extract(context: Context, uri: Uri): MediaInfo = withContext(Dispatchers.IO) { extractSync(context, uri) }

    suspend fun extract(descriptor: ParcelFileDescriptor): MediaInfo = withContext(Dispatchers.IO) { extractSync(descriptor) }

    suspend fun extract(filePath: String): MediaInfo = withContext(Dispatchers.IO) { extractSync(filePath) }

    /** Reads network or other random-access media. */
    suspend fun extract(source: MediaDataSource): MediaInfo = withContext(Dispatchers.IO) { extractSync(source) }

    /** Reads a forward-only stream; see [extractSync] for its limits. */
    suspend fun extract(inputStream: InputStream, streamLength: Long = -1L): MediaInfo =
        withContext(Dispatchers.IO) { extractSync(inputStream, streamLength) }

    // --- Blocking API ---

    @JvmStatic
    @JvmOverloads
    fun extractSync(context: Context, uri: Uri, timeoutMs: Long = DEFAULT_TIMEOUT_MS): MediaInfo =
        MediaInputs.open(context, uri) { fd, source -> probe(fd, source, uri.toString(), timeoutMs) }

    @JvmStatic
    @JvmOverloads
    fun extractSync(descriptor: ParcelFileDescriptor, timeoutMs: Long = DEFAULT_TIMEOUT_MS): MediaInfo =
        MediaInputs.open(descriptor) { fd, source -> probe(fd, source, "fd:${descriptor.fd}", timeoutMs) }

    @JvmStatic
    @JvmOverloads
    fun extractSync(filePath: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): MediaInfo =
        MediaInputs.open(filePath) { fd, source -> probe(fd, source, filePath, timeoutMs) }

    @JvmStatic
    @JvmOverloads
    fun extractSync(source: MediaDataSource, timeoutMs: Long = DEFAULT_TIMEOUT_MS): MediaInfo =
        MediaInputs.open(source) { fd, data -> probe(fd, data, "source", timeoutMs) }

    /**
     * Reads a stream that can only go forward. Containers that need to seek back, like MP4 with
     * its index at the end, fail this way; give network files a [MediaDataSource] instead.
     */
    @JvmStatic
    @JvmOverloads
    fun extractSync(inputStream: InputStream, streamLength: Long = -1L, timeoutMs: Long = DEFAULT_TIMEOUT_MS): MediaInfo =
        MediaInputs.open(inputStream, streamLength) { fd, source -> probe(fd, source, "stream", timeoutMs) }

    // --- Callback API (runs on a background thread, calls back there) ---

    @JvmStatic
    fun extractAsync(context: Context, uri: Uri, callback: Callback) = inBackground(callback) { extractSync(context, uri) }

    @JvmStatic
    fun extractAsync(filePath: String, callback: Callback) = inBackground(callback) { extractSync(filePath) }

    @JvmStatic
    fun extractAsync(source: MediaDataSource, callback: Callback) = inBackground(callback) { extractSync(source) }

    @JvmStatic
    @JvmOverloads
    fun extractAsync(inputStream: InputStream, streamLength: Long = -1L, callback: Callback) =
        inBackground(callback) { extractSync(inputStream, streamLength) }

    private fun probe(fd: Int, source: MediaDataSource?, label: String, timeoutMs: Long): MediaInfo =
        MediaInfoNativeBridge.probe(fd, source, label, timeoutMs)
            ?: throw PlayMediaException.CorruptStreamException(-1, "Can't read the media in $label")

    private fun inBackground(callback: Callback, read: () -> MediaInfo) {
        Thread({
            try {
                callback.onSuccess(read())
            } catch (error: PlayMediaException) {
                callback.onError(error)
            } catch (error: Exception) {
                callback.onError(PlayMediaException.CorruptStreamException(-1, error.message ?: "Can't read the media"))
            }
        }, "PlayMedia:Info").start()
    }
}
