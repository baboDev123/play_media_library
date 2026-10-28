package io.github.playme.media.tests

import android.media.MediaDataSource
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/** The test media in assets/media, copied out to files so every kind of input can be tried. */
object TestMedia {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    fun file(name: String): File {
        val target = File(instrumentation.targetContext.cacheDir, "media/$name")
        if (!target.exists()) {
            target.parentFile?.mkdirs()
            instrumentation.context.assets.open("media/$name").use { input -> target.outputStream().use(input::copyTo) }
        }
        return target
    }

    fun bytes(name: String): ByteArray = file(name).readBytes()
}

/** Random-access media in memory, as an app's network source would provide it. */
class BytesDataSource(private val bytes: ByteArray, private val delayPerReadMs: Long = 0) : MediaDataSource() {
    override fun getSize(): Long = bytes.size.toLong()

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (delayPerReadMs > 0) Thread.sleep(delayPerReadMs)
        if (position >= bytes.size) return -1
        val count = minOf(size.toLong(), bytes.size - position).toInt()
        bytes.copyInto(buffer, offset, position.toInt(), position.toInt() + count)
        return count
    }

    override fun close() = Unit
}
