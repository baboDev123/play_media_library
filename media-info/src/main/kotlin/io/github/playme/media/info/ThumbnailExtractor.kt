package io.github.playme.media.info

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaDataSource
import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream

/**
 * Takes frames out of videos, upright and scaled to fit, for thumbnails and seek previews.
 *
 * By default the frame is the key frame at or before [timeUs][getFrameAtSync], which is fast;
 * `exact = true` decodes on to the first frame at or after it. For media without video the
 * embedded cover picture is used. Reading gives up after `timeoutMs`; failures return null.
 */
object ThumbnailExtractor {

    const val DEFAULT_TIMEOUT_MS = 10_000L

    interface Callback {
        fun onLoaded(bitmap: Bitmap)
        fun onError(error: PlayMediaException)
    }

    // --- Suspending API ---

    suspend fun getFrameAt(
        context: Context,
        uri: Uri,
        timeUs: Long = 0L,
        targetWidth: Int = 0,
        targetHeight: Int = 0,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        exact: Boolean = false,
    ): Bitmap? = withContext(Dispatchers.IO) { getFrameAtSync(context, uri, timeUs, targetWidth, targetHeight, timeoutMs, exact) }

    suspend fun getFrameAt(
        source: MediaDataSource,
        timeUs: Long = 0L,
        targetWidth: Int = 0,
        targetHeight: Int = 0,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        exact: Boolean = false,
    ): Bitmap? = withContext(Dispatchers.IO) { getFrameAtSync(source, timeUs, targetWidth, targetHeight, timeoutMs, exact) }

    suspend fun getFrameAt(
        inputStream: InputStream,
        streamLength: Long = -1L,
        timeUs: Long = 0L,
        targetWidth: Int = 0,
        targetHeight: Int = 0,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): Bitmap? = withContext(Dispatchers.IO) { getFrameAtSync(inputStream, streamLength, timeUs, targetWidth, targetHeight, timeoutMs) }

    suspend fun getFrameAt(
        descriptor: ParcelFileDescriptor,
        timeUs: Long = 0L,
        targetWidth: Int = 0,
        targetHeight: Int = 0,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        exact: Boolean = false,
    ): Bitmap? = withContext(Dispatchers.IO) { getFrameAtSync(descriptor, timeUs, targetWidth, targetHeight, timeoutMs, exact) }

    suspend fun getFrameAt(
        filePath: String,
        timeUs: Long = 0L,
        targetWidth: Int = 0,
        targetHeight: Int = 0,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        exact: Boolean = false,
    ): Bitmap? = withContext(Dispatchers.IO) { getFrameAtSync(filePath, timeUs, targetWidth, targetHeight, timeoutMs, exact) }

    // --- Blocking API ---

    @JvmStatic
    @JvmOverloads
    fun getFrameAtSync(
        context: Context,
        uri: Uri,
        timeUs: Long = 0L,
        targetWidth: Int = 0,
        targetHeight: Int = 0,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        exact: Boolean = false,
    ): Bitmap? = quietly {
        MediaInputs.open(context, uri) { fd, source -> frame(fd, source, timeUs, targetWidth, targetHeight, timeoutMs, exact) }
    }

    @JvmStatic
    @JvmOverloads
    fun getFrameAtSync(
        source: MediaDataSource,
        timeUs: Long = 0L,
        targetWidth: Int = 0,
        targetHeight: Int = 0,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        exact: Boolean = false,
    ): Bitmap? = quietly {
        MediaInputs.open(source) { fd, data -> frame(fd, data, timeUs, targetWidth, targetHeight, timeoutMs, exact) }
    }

    /** From a forward-only stream: only the first key frame can be reached reliably. */
    @JvmStatic
    @JvmOverloads
    fun getFrameAtSync(
        inputStream: InputStream,
        streamLength: Long = -1L,
        timeUs: Long = 0L,
        targetWidth: Int = 0,
        targetHeight: Int = 0,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): Bitmap? = quietly {
        MediaInputs.open(inputStream, streamLength) { fd, source -> frame(fd, source, timeUs, targetWidth, targetHeight, timeoutMs, false) }
    }

    @JvmStatic
    @JvmOverloads
    fun getFrameAtSync(
        descriptor: ParcelFileDescriptor,
        timeUs: Long = 0L,
        targetWidth: Int = 0,
        targetHeight: Int = 0,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        exact: Boolean = false,
    ): Bitmap? = quietly {
        MediaInputs.open(descriptor) { fd, source -> frame(fd, source, timeUs, targetWidth, targetHeight, timeoutMs, exact) }
    }

    @JvmStatic
    @JvmOverloads
    fun getFrameAtSync(
        filePath: String,
        timeUs: Long = 0L,
        targetWidth: Int = 0,
        targetHeight: Int = 0,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        exact: Boolean = false,
    ): Bitmap? = quietly {
        MediaInputs.open(filePath) { fd, source -> frame(fd, source, timeUs, targetWidth, targetHeight, timeoutMs, exact) }
    }

    /** The encoded cover picture (JPEG or PNG) of [uri], or null. */
    @JvmStatic
    @JvmOverloads
    fun getEmbeddedPicture(context: Context, uri: Uri, timeoutMs: Long = DEFAULT_TIMEOUT_MS): ByteArray? = quietly {
        MediaInputs.open(context, uri) { fd, source -> MediaInfoNativeBridge.embeddedPicture(fd, source, timeoutMs) }
    }

    @JvmStatic
    @JvmOverloads
    fun getEmbeddedPicture(source: MediaDataSource, timeoutMs: Long = DEFAULT_TIMEOUT_MS): ByteArray? = quietly {
        MediaInputs.open(source) { fd, data -> MediaInfoNativeBridge.embeddedPicture(fd, data, timeoutMs) }
    }

    @JvmStatic
    @JvmOverloads
    fun getEmbeddedPicture(descriptor: ParcelFileDescriptor, timeoutMs: Long = DEFAULT_TIMEOUT_MS): ByteArray? = quietly {
        MediaInputs.open(descriptor) { fd, source -> MediaInfoNativeBridge.embeddedPicture(fd, source, timeoutMs) }
    }

    // --- Callback API (runs on a background thread, calls back there) ---

    @JvmStatic
    @JvmOverloads
    fun getFrameAtAsync(
        context: Context,
        uri: Uri,
        timeUs: Long = 0L,
        targetWidth: Int = 0,
        targetHeight: Int = 0,
        callback: Callback,
    ) = inBackground(callback, "$uri") { getFrameAtSync(context, uri, timeUs, targetWidth, targetHeight) }

    @JvmStatic
    @JvmOverloads
    fun getFrameAtAsync(
        inputStream: InputStream,
        streamLength: Long = -1L,
        timeUs: Long = 0L,
        targetWidth: Int = 0,
        targetHeight: Int = 0,
        callback: Callback,
    ) = inBackground(callback, "the stream") { getFrameAtSync(inputStream, streamLength, timeUs, targetWidth, targetHeight) }

    private fun frame(
        fd: Int,
        source: MediaDataSource?,
        timeUs: Long,
        maxWidth: Int,
        maxHeight: Int,
        timeoutMs: Long,
        exact: Boolean,
    ): Bitmap? {
        val rotation = IntArray(1)
        val frame = MediaInfoNativeBridge.frame(fd, source, timeUs, maxWidth, maxHeight, exact, timeoutMs, rotation)
        if (frame != null) return upright(frame, rotation[0])
        val picture = MediaInfoNativeBridge.embeddedPicture(fd, source, timeoutMs) ?: return null
        return decodePicture(picture, maxWidth, maxHeight)
    }

    private fun upright(frame: Bitmap, rotation: Int): Bitmap {
        if (rotation % 360 == 0) return frame
        val turn = Matrix().apply { postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(frame, 0, 0, frame.width, frame.height, turn, true).also { if (it !== frame) frame.recycle() }
    }

    /** Decodes a cover picture, subsampled to roughly the requested size. */
    private fun decodePicture(bytes: ByteArray, maxWidth: Int, maxHeight: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while ((maxWidth > 0 && bounds.outWidth / (sample * 2) >= maxWidth) || (maxHeight > 0 && bounds.outHeight / (sample * 2) >= maxHeight)) {
            sample *= 2
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun <T> quietly(read: () -> T?): T? = try {
        read()
    } catch (_: PlayMediaException) {
        null
    }

    private fun inBackground(callback: Callback, label: String, read: () -> Bitmap?) {
        Thread({
            val bitmap = read()
            if (bitmap != null) {
                callback.onLoaded(bitmap)
            } else {
                callback.onError(PlayMediaException.CorruptStreamException(-1, "No frame could be taken from $label"))
            }
        }, "PlayMedia:Thumbnail").start()
    }
}
