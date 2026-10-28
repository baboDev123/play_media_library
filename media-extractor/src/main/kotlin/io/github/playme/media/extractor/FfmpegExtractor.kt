package io.github.playme.media.extractor

import android.os.ParcelFileDescriptor
import androidx.media3.common.C
import androidx.media3.common.ParserException
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.TrackOutput
import java.io.IOException

/**
 * Reads a container with FFmpeg, for files Media3's own extractors can't handle: MP4s with an
 * index too large for the Java heap, broken indexes, and formats Media3 doesn't know.
 *
 * FFmpeg reads the file itself, through a descriptor from [openFile], with all the seeking it
 * needs. Media3's input only follows along: it is kept at the position of the packets read, so
 * that Media3 can pace loading (it stops reading ahead once enough is buffered) as it does for
 * its own extractors.
 */
class FfmpegExtractor internal constructor(private val openFile: () -> ParcelFileDescriptor?) : Extractor {

    private var file: ParcelFileDescriptor? = null
    private var handle = 0L
    private var output: ExtractorOutput? = null
    private var tracks: Map<Int, Track>? = null
    private val packetInfo = LongArray(4)
    private var sample = ByteArray(INITIAL_SAMPLE_SIZE)

    /** Size of a packet read but not yet passed on (Media3 was first sent to its position). */
    private var pendingSize = NO_PACKET
    private var pendingSeekUs = C.TIME_UNSET
    private var durationUs = C.TIME_UNSET

    override fun sniff(input: ExtractorInput): Boolean = openNative() && hasPlayableStream()

    override fun init(output: ExtractorOutput) {
        this.output = output
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        if (!openNative()) throw ParserException.createForUnsupportedContainerFeature("FFmpeg can't read this file")
        val tracks = tracks ?: buildTracks().also { tracks = it }
        if (pendingSeekUs != C.TIME_UNSET) {
            DemuxerNative.seek(handle, pendingSeekUs)
            pendingSeekUs = C.TIME_UNSET
            pendingSize = NO_PACKET
        }

        val size = if (pendingSize != NO_PACKET) pendingSize else DemuxerNative.nextPacket(handle, packetInfo)
        if (size == END_OF_INPUT) return Extractor.RESULT_END_OF_INPUT
        if (size < 0) throw IOException("FFmpeg couldn't read the file ($size)")
        val track = tracks[packetInfo[0].toInt()]
        if (track == null) {
            pendingSize = NO_PACKET
            return Extractor.RESULT_CONTINUE
        }

        // Keep Media3's input where FFmpeg reads: jump there when far ahead, else read up to it.
        val packetPosition = packetInfo[3]
        if (pendingSize == NO_PACKET && packetPosition > input.position + MAX_FOLLOW_BYTES) {
            pendingSize = size
            seekPosition.position = packetPosition
            return Extractor.RESULT_SEEK
        }
        pendingSize = NO_PACKET
        if (packetPosition > input.position) input.skipFully((packetPosition - input.position).toInt())

        if (sample.size < size) sample = ByteArray(size + size / 2)
        DemuxerNative.copyPacket(handle, sample)
        track.write(sample, size, packetInfo[1], keyFrame = packetInfo[2] == 1L)
        return Extractor.RESULT_CONTINUE
    }

    override fun seek(position: Long, timeUs: Long) {
        pendingSize = NO_PACKET
        // The first load starts at the beginning anyway.
        pendingSeekUs = if (tracks == null && timeUs == 0L) C.TIME_UNSET else timeUs
        tracks?.values?.forEach { it.lastTimeUs = C.TIME_UNSET }
    }

    override fun release() {
        if (handle != 0L) DemuxerNative.close(handle)
        handle = 0L
        file?.close()
        file = null
    }

    private fun openNative(): Boolean {
        if (handle != 0L) return true
        val descriptor = file ?: runCatching(openFile).getOrNull()?.also { file = it } ?: return false
        handle = DemuxerNative.open(descriptor.fd)
        return handle != 0L
    }

    private fun hasPlayableStream(): Boolean =
        (0 until DemuxerNative.streamCount(handle)).any { StreamFormats.of(DemuxerNative.stream(handle, it)) != null }

    private fun buildTracks(): Map<Int, Track> {
        val output = checkNotNull(output)
        val tracks = HashMap<Int, Track>()
        var seekStream = -1
        for (index in 0 until DemuxerNative.streamCount(handle)) {
            val stream = DemuxerNative.stream(handle, index)
            val format = StreamFormats.of(stream) ?: continue
            if (format.nalLengthSize > 0) DemuxerNative.setNalLengthSize(handle, index, format.nalLengthSize)
            val trackOutput = output.track(index, format.trackType)
            if (!format.needsParameterSets) trackOutput.format(format.format)
            tracks[index] = Track(trackOutput, stream, format)
            if (seekStream < 0 || format.trackType == C.TRACK_TYPE_VIDEO && tracks[seekStream]?.format?.trackType != C.TRACK_TYPE_VIDEO) {
                seekStream = index
            }
        }
        output.endTracks()
        val duration = DemuxerNative.durationUs(handle)
        durationUs = if (duration > 0) duration else C.TIME_UNSET
        output.seekMap(IndexSeekMap(seekStream))
        return tracks
    }

    /** Seek points from the container's index; without one, a position in proportion to the time. */
    private inner class IndexSeekMap(private val stream: Int) : SeekMap {
        private val length = file?.statSize ?: -1L

        private val totalUs = this@FfmpegExtractor.durationUs

        override fun isSeekable(): Boolean = totalUs != C.TIME_UNSET && stream >= 0

        override fun getDurationUs(): Long = totalUs

        override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
            val indexed = if (handle != 0L) DemuxerNative.seekPoint(handle, stream, timeUs) else null
            if (indexed != null) return SeekMap.SeekPoints(SeekPoint(indexed[0].coerceAtLeast(0), indexed[1]))
            val position = if (length > 0 && totalUs > 0) (length * (timeUs.toDouble() / totalUs)).toLong() else 0L
            return SeekMap.SeekPoints(SeekPoint(timeUs, position))
        }
    }

    private class Track(val output: TrackOutput, val stream: DemuxedStream, val format: StreamFormat) {
        private val view = ParsableByteArray()
        private var formatSent = !format.needsParameterSets
        var lastTimeUs = C.TIME_UNSET

        fun write(sample: ByteArray, size: Int, packetTimeUs: Long, keyFrame: Boolean) {
            if (!formatSent) {
                // Codec configuration only comes with the first key frame.
                if (!keyFrame) return
                val sets = StreamFormats.parameterSetsIn(sample, size, stream.codec)
                output.format(format.format.buildUpon().setInitializationData(sets).build())
                formatSent = true
            }
            val timeUs = if (packetTimeUs == Long.MIN_VALUE) lastTimeUs else packetTimeUs
            if (timeUs == C.TIME_UNSET) return
            lastTimeUs = timeUs

            var offset = 0
            if (format.stripAdts && size > 7 && sample[0] == 0xFF.toByte() && (sample[1].toInt() and 0xF0) == 0xF0) {
                // ADTS header: 7 bytes, or 9 with a CRC (protection_absent bit clear).
                offset = if (sample[1].toInt() and 1 == 1) 7 else 9
            }
            val length = size - offset
            if (length <= 0) return
            view.reset(sample, size)
            view.position = offset
            output.sampleData(view, length)
            var written = length
            if (format.vorbisSuffix) {
                output.sampleData(ParsableByteArray(UNKNOWN_SAMPLE_COUNT), UNKNOWN_SAMPLE_COUNT.size)
                written += UNKNOWN_SAMPLE_COUNT.size
            }
            val flags = if (keyFrame || format.trackType == C.TRACK_TYPE_AUDIO) C.BUFFER_FLAG_KEY_FRAME else 0
            output.sampleMetadata(timeUs, flags, written, 0, null)
        }
    }

    private companion object {
        val UNKNOWN_SAMPLE_COUNT = byteArrayOf(-1, -1, -1, -1)
        const val NO_PACKET = Int.MIN_VALUE
        const val END_OF_INPUT = -1
        const val INITIAL_SAMPLE_SIZE = 512 * 1024

        /** Further ahead than this, Media3's input is sent to the packet instead of reading up to it. */
        const val MAX_FOLLOW_BYTES = 2L * 1024 * 1024
    }
}
