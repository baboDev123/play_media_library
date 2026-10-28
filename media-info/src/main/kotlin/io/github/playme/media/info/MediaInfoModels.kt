package io.github.playme.media.info

import androidx.annotation.Keep

@Keep
enum class HdrType {
    NONE,
    HDR10,
    HDR10_PLUS,
    HLG,
    DOLBY_VISION
}

@Keep
data class MediaAttachment(
    val id: Long,
    val name: String,
    val mimeType: String,
    val size: Long,
    val data: ByteArray? = null
) {
    val isFont: Boolean
        get() = mimeType.contains("font") ||
            name.endsWith(".ttf", ignoreCase = true) ||
            name.endsWith(".otf", ignoreCase = true) ||
            name.endsWith(".ttc", ignoreCase = true)
}

@Keep
data class VideoStream(
    val index: Int,
    val codecName: String,
    val width: Int,
    val height: Int,
    val bitRate: Long,
    val frameRate: Float,
    val rotation: Int = 0,
    val bitDepth: Int = 8,
    val hdrType: HdrType = HdrType.NONE,
    val colorSpace: String? = null,
    val colorTransfer: String? = null,
    val colorPrimaries: String? = null,
    val isDefault: Boolean = false,
    val language: String? = null,
    val title: String? = null,
    /** FFmpeg's AV_DISPOSITION_* flags. */
    val disposition: Int = 0,
) {
    val isHdr: Boolean
        get() = hdrType != HdrType.NONE || bitDepth > 8
}

@Keep
data class AudioStream(
    val index: Int,
    val codecName: String,
    val sampleRate: Int,
    val channels: Int,
    val bitRate: Long,
    val language: String? = null,
    val title: String? = null,
    val isDefault: Boolean = false,
    /** FFmpeg's description, such as "5.1(side)". */
    val channelLayout: String? = null,
    /** FFmpeg's sample format name, such as "fltp". */
    val sampleFormat: String? = null,
    /** FFmpeg's AV_DISPOSITION_* flags. */
    val disposition: Int = 0,
)

@Keep
data class SubtitleStream(
    val index: Int,
    val codecName: String,
    val language: String? = null,
    val title: String? = null,
    val isDefault: Boolean = false,
    val isForced: Boolean = false,
    /** FFmpeg's AV_DISPOSITION_* flags. */
    val disposition: Int = 0,
)

@Keep
data class Chapter(
    val id: Long,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val title: String? = null
)

@Keep
data class MediaInfo(
    val path: String,
    val formatName: String,
    val durationMs: Long,
    val bitRate: Long,
    val videoStreams: List<VideoStream> = emptyList(),
    val audioStreams: List<AudioStream> = emptyList(),
    val subtitleStreams: List<SubtitleStream> = emptyList(),
    val chapters: List<Chapter> = emptyList(),
    val attachments: List<MediaAttachment> = emptyList(),
    val coverArt: ByteArray? = null
) {
    val primaryVideo: VideoStream?
        get() = videoStreams.firstOrNull()

    val primaryAudio: AudioStream?
        get() = audioStreams.firstOrNull { it.isDefault } ?: audioStreams.firstOrNull()

    val resolutionFormatted: String
        get() = primaryVideo?.let { "${it.width}x${it.height}" } ?: "Unknown"

    val rotation: Int
        get() = primaryVideo?.rotation ?: 0

    val hasCoverArt: Boolean
        get() = coverArt != null && coverArt.isNotEmpty()

    val embeddedFonts: List<MediaAttachment>
        get() = attachments.filter { it.isFont }
}
