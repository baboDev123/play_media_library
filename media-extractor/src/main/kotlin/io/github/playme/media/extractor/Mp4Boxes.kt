package io.github.playme.media.extractor

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/** Reading just enough of an MP4's top-level boxes to judge it, a few bytes per box. */
internal object Mp4Boxes {

    private val topLevelTypes = setOf("ftyp", "moov", "mdat", "free", "skip", "wide", "uuid", "pdin", "styp", "sidx", "moof", "meta")

    /** Size of the 'moov' box (the index), or null when this isn't an MP4 or has none. */
    fun movieBoxSize(channel: FileChannel): Long? {
        val size = channel.size()
        var position = 0L
        while (position + 8 <= size) {
            val header = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
            while (header.hasRemaining() && channel.read(header, position + header.position()) > 0) Unit
            if (header.position() < 8) return null
            header.flip()
            val type = String(ByteArray(4) { header.get(4 + it) }, Charsets.ISO_8859_1)
            if (type !in topLevelTypes) return null
            val boxSize = when (val size32 = header.getInt(0).toUInt().toLong()) {
                1L -> if (header.limit() >= 16) header.getLong(8) else return null
                0L -> size - position
                else -> size32
            }
            if (boxSize < 8) return null
            if (type == "moov") return boxSize
            position += boxSize
        }
        return null
    }
}
