package io.github.playme.media.extractor

import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A stream as Media3 takes it: its [format], and how its packets need adjusting on the way.
 *
 * @property nalLengthSize H.264/HEVC packets with NAL units behind length prefixes of this many
 *   bytes (MP4, Matroska), turned into Annex-B start codes; 0 when they already are.
 * @property stripAdts AAC packets that start with an ADTS header, which MediaCodec doesn't take.
 * @property vorbisSuffix Vorbis packets, which Android's decoder takes with 4 more bytes (the
 *   count of samples in the packet, -1 for unknown), as Media3's own extractors write them.
 * @property needsParameterSets H.264/HEVC without codec configuration in the container (TS, AVI):
 *   it is found in the first key frame, and the format is only sent then.
 */
internal data class StreamFormat(
    val trackType: Int,
    val format: Format,
    val nalLengthSize: Int = 0,
    val stripAdts: Boolean = false,
    val vorbisSuffix: Boolean = false,
    val needsParameterSets: Boolean = false,
)

internal object StreamFormats {

    private val videoTypes = mapOf(
        "h264" to MimeTypes.VIDEO_H264,
        "hevc" to MimeTypes.VIDEO_H265,
        "vp8" to MimeTypes.VIDEO_VP8,
        "vp9" to MimeTypes.VIDEO_VP9,
        "av1" to MimeTypes.VIDEO_AV1,
        "mpeg4" to MimeTypes.VIDEO_MP4V,
        "mpeg2video" to MimeTypes.VIDEO_MPEG2,
        "mpeg1video" to MimeTypes.VIDEO_MPEG,
        "h263" to MimeTypes.VIDEO_H263,
        "vc1" to MimeTypes.VIDEO_VC1,
        "mjpeg" to MimeTypes.VIDEO_MJPEG,
        // Only PlayMedia's FFmpeg decoder plays it.
        "wmv3" to "video/x-ms-wmv",
    )

    private val audioTypes = mapOf(
        "aac" to MimeTypes.AUDIO_AAC,
        "mp3" to MimeTypes.AUDIO_MPEG,
        "mp2" to MimeTypes.AUDIO_MPEG_L2,
        "mp1" to MimeTypes.AUDIO_MPEG_L1,
        "ac3" to MimeTypes.AUDIO_AC3,
        "eac3" to MimeTypes.AUDIO_E_AC3,
        "dts" to MimeTypes.AUDIO_DTS,
        "truehd" to MimeTypes.AUDIO_TRUEHD,
        "flac" to MimeTypes.AUDIO_FLAC,
        "vorbis" to MimeTypes.AUDIO_VORBIS,
        "opus" to MimeTypes.AUDIO_OPUS,
        "alac" to MimeTypes.AUDIO_ALAC,
        "amr_nb" to MimeTypes.AUDIO_AMR_NB,
        "amr_wb" to MimeTypes.AUDIO_AMR_WB,
        "pcm_alaw" to MimeTypes.AUDIO_ALAW,
        "pcm_mulaw" to MimeTypes.AUDIO_MLAW,
    )

    private val pcmEncodings = mapOf(
        "pcm_u8" to C.ENCODING_PCM_8BIT,
        "pcm_s16le" to C.ENCODING_PCM_16BIT,
        "pcm_s16be" to C.ENCODING_PCM_16BIT_BIG_ENDIAN,
        "pcm_s24le" to C.ENCODING_PCM_24BIT,
        "pcm_s32le" to C.ENCODING_PCM_32BIT,
        "pcm_f32le" to C.ENCODING_PCM_FLOAT,
    )

    /** How Media3 should take [stream], or null when it can't play it (or it isn't sound or picture). */
    fun of(stream: DemuxedStream): StreamFormat? = when (stream.type) {
        "video" -> video(stream)
        "audio" -> audio(stream)
        else -> null
    }

    private fun video(stream: DemuxedStream): StreamFormat? {
        val mimeType = videoTypes[stream.codec] ?: return null
        val extra = stream.extraData
        var nalLengthSize = 0
        val initialization: List<ByteArray> = when (stream.codec) {
            "h264" -> if (extra != null && extra.size > 6 && extra[0].toInt() == 1) {
                nalLengthSize = (extra[4].toInt() and 3) + 1
                avcParameterSets(extra)
            } else {
                annexBNalUnits(extra).map { START_CODE + it }
            }
            "hevc" -> if (extra != null && extra.size > 22 && extra[0].toInt() == 1) {
                nalLengthSize = (extra[21].toInt() and 3) + 1
                listOf(hevcParameterSets(extra))
            } else {
                annexBNalUnits(extra).takeIf { it.isNotEmpty() }?.let { units -> listOf(units.fold(ByteArray(0)) { all, unit -> all + START_CODE + unit }) }.orEmpty()
            }
            else -> listOfNotNull(extra)
        }
        val needsParameterSets = (stream.codec == "h264" || stream.codec == "hevc") && initialization.isEmpty()
        val format = base(stream, mimeType)
            .setWidth(stream.width)
            .setHeight(stream.height)
            .setFrameRate(if (stream.frameRate > 0) stream.frameRate else Format.NO_VALUE.toFloat())
            .setRotationDegrees(stream.rotation)
            .setPixelWidthHeightRatio(stream.pixelWidthHeightRatio)
            .setColorInfo(colorInfoOf(stream))
            .setInitializationData(initialization)
            .build()
        return StreamFormat(C.TRACK_TYPE_VIDEO, format, nalLengthSize = nalLengthSize, needsParameterSets = needsParameterSets)
    }

    private fun audio(stream: DemuxedStream): StreamFormat? {
        val pcm = pcmEncodings[stream.codec]
        val mimeType = if (pcm != null) MimeTypes.AUDIO_RAW else audioTypes[stream.codec] ?: return null
        val extra = stream.extraData
        val initialization: List<ByteArray> = when (stream.codec) {
            "aac" -> listOf(extra ?: aacConfig(stream.sampleRate, stream.channels) ?: return null)
            "opus" -> extra?.let(::opusInitialization).orEmpty()
            "vorbis" -> extra?.let(::vorbisHeaders).orEmpty()
            "flac" -> extra?.let(::flacStreamInfo).orEmpty()
            // FFmpeg keeps ALAC's whole 'alac' atom; Media3 the magic cookie inside it.
            "alac" -> extra?.let { listOf(if (it.size > 12 && String(it, 4, 4, Charsets.US_ASCII) == "alac") it.copyOfRange(12, it.size) else it) }.orEmpty()
            else -> emptyList()
        }
        val builder = base(stream, mimeType)
            .setSampleRate(stream.sampleRate)
            .setChannelCount(stream.channels)
            .setInitializationData(initialization)
        if (pcm != null) builder.setPcmEncoding(pcm)
        return StreamFormat(C.TRACK_TYPE_AUDIO, builder.build(), stripAdts = stream.codec == "aac", vorbisSuffix = stream.codec == "vorbis")
    }

    private fun base(stream: DemuxedStream, mimeType: String): Format.Builder = Format.Builder()
        // Marks formats that came from FFmpeg, in logs and analytics.
        .setId("playmedia/${stream.index}")
        .setSampleMimeType(mimeType)
        .setLanguage(stream.language?.takeUnless { it == "und" })
        .setLabel(stream.title)
        .setAverageBitrate(if (stream.bitRate > 0) stream.bitRate.coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else Format.NO_VALUE)
        .setSelectionFlags(
            (if (stream.disposition and DISPOSITION_DEFAULT != 0) C.SELECTION_FLAG_DEFAULT else 0) or
                (if (stream.disposition and DISPOSITION_FORCED != 0) C.SELECTION_FLAG_FORCED else 0),
        )

    /** FFmpeg's colour description in Media3's terms, so HDR reaches the device's decoder. */
    fun colorInfoOf(stream: DemuxedStream): ColorInfo? {
        val space = when (stream.colorPrimaries) {
            PRIMARIES_BT709 -> C.COLOR_SPACE_BT709
            PRIMARIES_BT470BG, PRIMARIES_SMPTE170M -> C.COLOR_SPACE_BT601
            PRIMARIES_BT2020 -> C.COLOR_SPACE_BT2020
            else -> Format.NO_VALUE
        }
        val transfer = when (stream.colorTransfer) {
            TRANSFER_SMPTE2084 -> C.COLOR_TRANSFER_ST2084
            TRANSFER_ARIB_STD_B67 -> C.COLOR_TRANSFER_HLG
            TRANSFER_BT709, TRANSFER_SMPTE170M, TRANSFER_BT2020_10, TRANSFER_BT2020_12 -> C.COLOR_TRANSFER_SDR
            else -> Format.NO_VALUE
        }
        val range = when (stream.colorRange) {
            RANGE_MPEG -> C.COLOR_RANGE_LIMITED
            RANGE_JPEG -> C.COLOR_RANGE_FULL
            else -> Format.NO_VALUE
        }
        if (space == Format.NO_VALUE && transfer == Format.NO_VALUE && range == Format.NO_VALUE && stream.bitDepth <= 0) return null
        return ColorInfo.Builder()
            .setColorSpace(space)
            .setColorTransfer(transfer)
            .setColorRange(range)
            .setLumaBitdepth(if (stream.bitDepth > 0) stream.bitDepth else Format.NO_VALUE)
            .setChromaBitdepth(if (stream.bitDepth > 0) stream.bitDepth else Format.NO_VALUE)
            .build()
    }

    /** SPS and PPS of an 'avcC' box, each with a start code, as Media3 keeps them. */
    fun avcParameterSets(avcC: ByteArray): List<ByteArray> {
        val sets = mutableListOf<ByteArray>()
        var at = 5
        repeat(2) { kind ->
            if (at >= avcC.size) return sets
            val count = if (kind == 0) avcC[at].toInt() and 0x1F else avcC[at].toInt() and 0xFF
            at++
            repeat(count) {
                if (at + 2 > avcC.size) return sets
                val length = ((avcC[at].toInt() and 0xFF) shl 8) or (avcC[at + 1].toInt() and 0xFF)
                at += 2
                if (at + length > avcC.size) return sets
                sets += START_CODE + avcC.copyOfRange(at, at + length)
                at += length
            }
        }
        return sets
    }

    /** VPS, SPS and PPS of an 'hvcC' box as one Annex-B block, as Media3 keeps them. */
    fun hevcParameterSets(hvcC: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var at = 22
        val arrays = hvcC[at++].toInt() and 0xFF
        repeat(arrays) {
            if (at + 3 > hvcC.size) return out.toByteArray()
            at++ // NAL unit type
            val count = ((hvcC[at].toInt() and 0xFF) shl 8) or (hvcC[at + 1].toInt() and 0xFF)
            at += 2
            repeat(count) {
                if (at + 2 > hvcC.size) return out.toByteArray()
                val length = ((hvcC[at].toInt() and 0xFF) shl 8) or (hvcC[at + 1].toInt() and 0xFF)
                at += 2
                if (at + length > hvcC.size) return out.toByteArray()
                out.write(START_CODE)
                out.write(hvcC, at, length)
                at += length
            }
        }
        return out.toByteArray()
    }

    /** The NAL units (without start codes) of an Annex-B block. */
    fun annexBNalUnits(data: ByteArray?, size: Int = data?.size ?: 0): List<ByteArray> {
        if (data == null) return emptyList()
        val starts = mutableListOf<Pair<Int, Int>>() // start code position, payload position
        var i = 0
        while (i + 3 <= size) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0) {
                if (data[i + 2].toInt() == 1) {
                    starts += i to i + 3
                    i += 3
                    continue
                }
                if (i + 4 <= size && data[i + 2].toInt() == 0 && data[i + 3].toInt() == 1) {
                    starts += i to i + 4
                    i += 4
                    continue
                }
            }
            i++
        }
        return starts.mapIndexed { n, (_, payload) ->
            val end = if (n + 1 < starts.size) starts[n + 1].first else size
            data.copyOfRange(payload, end)
        }.filter { it.isNotEmpty() }
    }

    /**
     * The parameter sets in a key frame's NAL units, for streams without codec configuration in
     * the container: SPS and PPS separately for H.264, VPS+SPS+PPS together for HEVC.
     */
    fun parameterSetsIn(sample: ByteArray, size: Int, codec: String): List<ByteArray> {
        val units = annexBNalUnits(sample, size)
        return if (codec == "h264") {
            val sps = units.filter { (it[0].toInt() and 0x1F) == 7 }
            val pps = units.filter { (it[0].toInt() and 0x1F) == 8 }
            (sps + pps).map { START_CODE + it }
        } else {
            val sets = units.filter { ((it[0].toInt() shr 1) and 0x3F) in 32..34 }
            if (sets.isEmpty()) emptyList() else listOf(sets.fold(ByteArray(0)) { all, unit -> all + START_CODE + unit })
        }
    }

    /** AudioSpecificConfig for AAC-LC, for streams that carry none (ADTS in TS or AVI). */
    fun aacConfig(sampleRate: Int, channels: Int): ByteArray? {
        val rateIndex = AAC_RATES.indexOf(sampleRate).takeIf { it >= 0 } ?: return null
        if (channels !in 1..7) return null
        val config = (2 shl 11) or (rateIndex shl 7) or (channels shl 3)
        return byteArrayOf((config shr 8).toByte(), config.toByte())
    }

    /** OpusHead, then the pre-skip and the seek pre-roll in nanoseconds, as Media3 hands them to MediaCodec. */
    fun opusInitialization(head: ByteArray): List<ByteArray> {
        val preSkip = if (head.size >= 12) (head[10].toInt() and 0xFF) or ((head[11].toInt() and 0xFF) shl 8) else 0
        fun nanos(value: Long) = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(value).array()
        return listOf(head, nanos(preSkip * 1_000_000_000L / 48_000), nanos(80_000_000L))
    }

    /** The identification and setup headers out of FFmpeg's Xiph-laced Vorbis configuration. */
    fun vorbisHeaders(laced: ByteArray): List<ByteArray> {
        if (laced.isEmpty() || laced[0].toInt() != 2) return emptyList()
        var at = 1
        val sizes = IntArray(2)
        for (n in 0..1) {
            var size = 0
            while (at < laced.size) {
                val value = laced[at++].toInt() and 0xFF
                size += value
                if (value != 255) break
            }
            sizes[n] = size
        }
        val identification = at
        val setup = identification + sizes[0] + sizes[1]
        if (setup > laced.size) return emptyList()
        return listOf(laced.copyOfRange(identification, identification + sizes[0]), laced.copyOfRange(setup, laced.size))
    }

    /** "fLaC" and the STREAMINFO block, as Media3 hands FLAC to MediaCodec. */
    fun flacStreamInfo(extra: ByteArray): List<ByteArray> {
        if (extra.size >= 4 && String(extra, 0, 4, Charsets.US_ASCII) == "fLaC") return listOf(extra)
        if (extra.size < STREAMINFO_SIZE) return emptyList()
        val header = byteArrayOf(0x80.toByte(), 0, 0, STREAMINFO_SIZE.toByte())
        return listOf("fLaC".toByteArray(Charsets.US_ASCII) + header + extra.copyOfRange(0, STREAMINFO_SIZE))
    }

    private val START_CODE = byteArrayOf(0, 0, 0, 1)
    private const val STREAMINFO_SIZE = 34
    private val AAC_RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)

    // FFmpeg's enum values (libavutil/pixfmt.h, libavformat/avformat.h).
    private const val DISPOSITION_DEFAULT = 0x1
    private const val DISPOSITION_FORCED = 0x40
    private const val PRIMARIES_BT709 = 1
    private const val PRIMARIES_BT470BG = 5
    private const val PRIMARIES_SMPTE170M = 6
    private const val PRIMARIES_BT2020 = 9
    private const val TRANSFER_BT709 = 1
    private const val TRANSFER_SMPTE170M = 6
    private const val TRANSFER_BT2020_10 = 14
    private const val TRANSFER_BT2020_12 = 15
    private const val TRANSFER_SMPTE2084 = 16
    private const val TRANSFER_ARIB_STD_B67 = 18
    private const val RANGE_MPEG = 1
    private const val RANGE_JPEG = 2
}
