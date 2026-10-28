package io.github.playme.media.decoder

/**
 * 4 Decoder Modes for PlayMedia Engine:
 * - AUTO: Hardware first -> Fallback to System Software -> Fallback to FFmpeg
 * - HARDWARE: Strict device hardware acceleration (MediaCodec only, no software codecs)
 * - SYSTEM_SOFTWARE: Android built-in software decoders (c2.android.* / OMX.google.*)
 * - FFMPEG: Native C++ FFmpeg decoders (AC3, DTS, TrueHD, AV1, HEVC, H264, etc.)
 */
enum class DecoderMode(val id: Int, val displayName: String) {
    /**
     * Automatic selection with full fallback chain:
     * HARDWARE -> SYSTEM_SOFTWARE -> FFMPEG
     */
    AUTO(0, "Auto"),

    /**
     * Strict device hardware acceleration.
     * Excludes c2.android.* and OMX.google.* software decoders.
     */
    HARDWARE(1, "Hardware (HW)"),

    /**
     * Android platform software decoders (c2.android.* and OMX.google.*).
     */
    SYSTEM_SOFTWARE(2, "System Software (SW)"),

    /**
     * Native C++ FFmpeg software engine.
     * High-performance software decoding for all container formats and codecs.
     */
    FFMPEG(3, "FFmpeg (SW)");

    val isHardwareOnly: Boolean
        get() = this == HARDWARE

    val isSystemSoftware: Boolean
        get() = this == SYSTEM_SOFTWARE

    val isFfmpegOnly: Boolean
        get() = this == FFMPEG

    companion object {
        @JvmStatic
        fun fromId(id: Int): DecoderMode {
            return entries.firstOrNull { it.id == id } ?: AUTO
        }
    }
}
