package io.github.playme.media.info

import androidx.annotation.Keep

@Keep
data class VideoStream(
    val index: Int,
    val codecName: String,
    val width: Int,
    val height: Int,
    val bitRate: Long,
    val frameRate: Float,
    val colorSpace: String? = null,
    val isDefault: Boolean = false
)

@Keep
data class AudioStream(
    val index: Int,
    val codecName: String,
    val sampleRate: Int,
    val channels: Int,
    val bitRate: Long,
    val language: String? = null,
    val title: String? = null,
    val isDefault: Boolean = false
)

@Keep
data class SubtitleStream(
    val index: Int,
    val codecName: String,
    val language: String? = null,
    val title: String? = null,
    val isDefault: Boolean = false,
    val isForced: Boolean = false
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
    val chapters: List<Chapter> = emptyList()
) {
    val primaryVideo: VideoStream?
        get() = videoStreams.firstOrNull()

    val primaryAudio: AudioStream?
        get() = audioStreams.firstOrNull { it.isDefault } ?: audioStreams.firstOrNull()

    val resolutionFormatted: String
        get() = primaryVideo?.let { "${it.width}x${it.height}" } ?: "Unknown"
}

