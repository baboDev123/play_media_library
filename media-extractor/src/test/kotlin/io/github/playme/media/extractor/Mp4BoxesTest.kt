package io.github.playme.media.extractor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile

class Mp4BoxesTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun indexAfterAHugeMediaBox() {
        // As a 27-hour recording: a 5 GB 'mdat' with a 64-bit size, then a 114 MB 'moov'.
        val file = folder.newFile("long.mp4")
        RandomAccessFile(file, "rw").use { out ->
            out.writeInt(16)
            out.writeBytes("ftyp")
            out.write(ByteArray(8))
            out.writeInt(1)
            out.writeBytes("mdat")
            out.writeLong(5_370_370_679L)
            out.seek(16 + 5_370_370_679L)
            out.writeInt(114_413_006)
            out.writeBytes("moov")
        }

        assertEquals(114_413_006L, sizeOf(file))
    }

    @Test
    fun notAnMp4() {
        val file = folder.newFile("clip.mkv").apply { writeBytes(byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()) + ByteArray(60)) }

        assertNull(sizeOf(file))
    }

    @Test
    fun noIndex() {
        val file = folder.newFile("cut.mp4").apply { writeBytes(byteArrayOf(0, 0, 0, 16) + "ftyp".toByteArray() + ByteArray(8)) }

        assertNull(sizeOf(file))
    }

    private fun sizeOf(file: File) = RandomAccessFile(file, "r").use { Mp4Boxes.movieBoxSize(it.channel) }
}
