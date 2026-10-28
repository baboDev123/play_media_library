package io.github.playme.media.info

import android.content.Context
import android.media.MediaDataSource
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Opens each kind of input for the native reader, which takes a file descriptor or a
 * [MediaDataSource]. Descriptors opened here are closed when [read] returns.
 */
internal object MediaInputs {

    fun <T> open(context: Context, uri: Uri, read: (fd: Int, source: MediaDataSource?) -> T): T = when (uri.scheme?.lowercase()) {
        "file" -> open(uri.path ?: throw PlayMediaException.InvalidDataSourceException("File URI without a path: $uri"), read)
        "http", "https" -> throw PlayMediaException.InvalidDataSourceException(
            "Read network media through a MediaDataSource: $uri",
        )
        else -> {
            val descriptor = try {
                context.contentResolver.openFileDescriptor(uri, "r")
            } catch (error: Exception) {
                throw PlayMediaException.InvalidDataSourceException("Can't open $uri: ${error.message}")
            } ?: throw PlayMediaException.InvalidDataSourceException("No content at $uri")
            descriptor.use { read(it.fd, null) }
        }
    }

    fun <T> open(path: String, read: (fd: Int, source: MediaDataSource?) -> T): T {
        val descriptor = try {
            ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (error: IOException) {
            throw PlayMediaException.InvalidDataSourceException("Can't open $path: ${error.message}")
        }
        return descriptor.use { read(it.fd, null) }
    }

    fun <T> open(descriptor: ParcelFileDescriptor, read: (fd: Int, source: MediaDataSource?) -> T): T = read(descriptor.fd, null)

    fun <T> open(source: MediaDataSource, read: (fd: Int, source: MediaDataSource?) -> T): T = read(-1, source)

    fun <T> open(stream: InputStream, length: Long, read: (fd: Int, source: MediaDataSource?) -> T): T =
        read(-1, InputStreamDataSource(stream, length))
}

/**
 * A [MediaDataSource] over a stream, which can only go forward: containers that need to jump
 * back (MP4 with its index at the end, for one) can't be read this way. Prefer a real
 * MediaDataSource with random access for network files.
 */
internal class InputStreamDataSource(private val stream: InputStream, private val length: Long) : MediaDataSource() {
    private var position = 0L

    override fun getSize(): Long = length

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position < this.position) throw IOException("Can't seek back in a stream ($position < ${this.position})")
        while (this.position < position) {
            val skipped = stream.skip(position - this.position)
            if (skipped <= 0) {
                if (stream.read() < 0) return -1
                this.position++
            } else {
                this.position += skipped
            }
        }
        val count = stream.read(buffer, offset, size)
        if (count > 0) this.position += count
        return count
    }

    override fun close() = Unit
}
