package io.github.playme.media.decoder

/**
 * Defines the decoder strategy used for media playback.
 * Fully compatible with both Kotlin DSL and Groovy DSL / Java callers.
 */
enum class DecoderMode(val id: Int, val displayName: String) {
    /**
     * Tries hardware decoder (MediaCodec) first.
     * Automatically falls back to high-performance software decoding if hardware codec is absent or fails.
     */
    AUTO(0, "Auto"),

    /**
     * Forces device hardware acceleration (MediaCodec).
     */
    HARDWARE(1, "Hardware (HW)"),

    /**
     * Forces optimized native software decoding via FFmpeg / NEON direct rendering.
     */
    SOFTWARE(2, "Software (SW)");

    val isHardwarePreferred: Boolean
        get() = this == HARDWARE || this == AUTO

    val isSoftwarePreferred: Boolean
        get() = this == SOFTWARE

    companion object {
        @JvmStatic
        fun fromId(id: Int): DecoderMode {
            return entries.firstOrNull { it.id == id } ?: AUTO
        }
    }
}

