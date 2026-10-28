package io.github.playme.media.decoder

import android.view.Surface
import io.github.playme.media.core.PlayMediaNative
import java.nio.ByteBuffer

/** FFmpeg video decoding (media-core's video_decoder.cpp). Handles are native pointers. */
internal object VideoNative {
    init {
        PlayMediaNative.requireAvailable()
    }

    /** Throws IllegalStateException when FFmpeg can't open a decoder for the format. */
    @JvmStatic external fun create(mimeType: String, extraData: ByteArray?, width: Int, height: Int, threads: Int): Long

    @JvmStatic external fun name(handle: Long): String

    /** Frames now ready, or a negative FFmpeg error. */
    @JvmStatic external fun send(handle: Long, data: ByteBuffer, offset: Int, size: Int, timeUs: Long, keyFrame: Boolean): Int

    /** Frames ready once the codec gave up what it held, or a negative FFmpeg error. */
    @JvmStatic external fun drain(handle: Long): Int

    /** The next frame (free it with [freeFrame]) or 0; [info] gets its time in µs, width and height. */
    @JvmStatic external fun nextFrame(handle: Long, info: LongArray): Long

    @JvmStatic external fun flush(handle: Long)

    @JvmStatic external fun destroy(handle: Long)

    @JvmStatic external fun freeFrame(frame: Long)
}

/** Draws frames onto a Surface (media-core's frame_window.cpp), on the playback thread. */
internal object FrameWindowNative {
    init {
        PlayMediaNative.requireAvailable()
    }

    @JvmStatic external fun create(): Long

    @JvmStatic external fun render(handle: Long, surface: Surface, frame: Long): Boolean

    @JvmStatic external fun release(handle: Long)
}

/** FFmpeg audio decoding to 16-bit PCM (media-core's audio_decoder.cpp). */
internal object AudioNative {
    init {
        PlayMediaNative.requireAvailable()
    }

    /**
     * Throws IllegalStateException when FFmpeg can't open a decoder for the format.
     * [skipSamples] are dropped from the start of the output.
     */
    @JvmStatic external fun create(mimeType: String, extraData: ByteArray?, sampleRate: Int, channels: Int, skipSamples: Int): Long

    @JvmStatic external fun name(handle: Long): String

    /** Bytes of PCM decoded from the sample, or a negative FFmpeg error. */
    @JvmStatic external fun decode(handle: Long, data: ByteBuffer, offset: Int, size: Int): Int

    /** Copies the last [decode]'s PCM into [target], starting at [position]. */
    @JvmStatic external fun copyOutput(handle: Long, target: ByteBuffer, position: Int)

    @JvmStatic external fun outputChannels(handle: Long): Int

    @JvmStatic external fun outputSampleRate(handle: Long): Int

    @JvmStatic external fun flush(handle: Long)

    @JvmStatic external fun destroy(handle: Long)
}
