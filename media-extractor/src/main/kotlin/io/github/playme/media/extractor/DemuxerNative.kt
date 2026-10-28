package io.github.playme.media.extractor

import io.github.playme.media.core.PlayMediaNative

/** FFmpeg demuxing (media-core's demuxer.cpp). Handles are native pointers; one thread at a time. */
internal object DemuxerNative {
    init {
        PlayMediaNative.requireAvailable()
    }

    /** Opens a container from [fd], which stays the caller's. 0 when FFmpeg can't read it. */
    @JvmStatic external fun open(fd: Int): Long

    @JvmStatic external fun formatName(handle: Long): String

    /** The container's length in µs, or -1 when unknown. */
    @JvmStatic external fun durationUs(handle: Long): Long

    @JvmStatic external fun streamCount(handle: Long): Int

    @JvmStatic external fun stream(handle: Long, index: Int): DemuxedStream

    /** Copy packets of stream [index] out as Annex-B, their NAL units having [lengthSize]-byte length prefixes. */
    @JvmStatic external fun setNalLengthSize(handle: Long, index: Int, lengthSize: Int)

    /**
     * Reads the next packet: returns its size as [copyPacket] gives it, -1 at the end, or another
     * negative FFmpeg error. [info] gets the stream index, time in µs, 1 for a key frame, and the
     * packet's byte position in the file (-1 when unknown).
     */
    @JvmStatic external fun nextPacket(handle: Long, info: LongArray): Int

    /** Copies the packet [nextPacket] read into [target], which holds at least its size. */
    @JvmStatic external fun copyPacket(handle: Long, target: ByteArray)

    /** Moves to the key frame at or before [timeUs]. */
    @JvmStatic external fun seek(handle: Long, timeUs: Long): Boolean

    /** {time µs, byte position} of the key frame at or before [timeUs] in stream [index]'s index, or null. */
    @JvmStatic external fun seekPoint(handle: Long, index: Int, timeUs: Long): LongArray?

    @JvmStatic external fun close(handle: Long)
}

/** One stream of a container as FFmpeg describes it. Built by native code: keep the constructor as is. */
data class DemuxedStream(
    val index: Int,
    /** "video", "audio", "subtitle", "picture" (cover art) or "other". */
    val type: String,
    /** FFmpeg's codec name, such as "h264" or "ac3". */
    val codec: String,
    val width: Int,
    val height: Int,
    val frameRate: Float,
    val rotation: Int,
    val pixelWidthHeightRatio: Float,
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val bitRate: Long,
    /** FFmpeg's AVColorPrimaries, AVColorTransferCharacteristic, AVColorSpace and AVColorRange. */
    val colorPrimaries: Int,
    val colorTransfer: Int,
    val colorSpace: Int,
    val colorRange: Int,
    val bitDepth: Int,
    val language: String?,
    val title: String?,
    /** FFmpeg's AV_DISPOSITION_* flags. */
    val disposition: Int,
    val extraData: ByteArray?,
)
