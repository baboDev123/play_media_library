package io.github.playme.media.decoder

import android.view.Surface
import androidx.annotation.Keep
import java.nio.ByteBuffer

/**
 * Low-level JNI interface bridging Kotlin/Java with the high-performance C++ FFmpeg engine.
 */
@Keep
internal object PlayMeNativeBridge {

    init {
        try {
            System.loadLibrary("playme_decoder")
        } catch (e: UnsatisfiedLinkError) {
            System.err.println("PlayMeNativeBridge: Failed to load native library: ${e.message}")
        }
    }

    /**
     * Checks if the native library and FFmpeg are successfully initialized.
     */
    @JvmStatic
    external fun isNativeAvailable(): Boolean

    /**
     * Returns the underlying FFmpeg version string.
     */
    @JvmStatic
    external fun getFfmpegVersion(): String

    /**
     * Checks if a specific codec name (e.g. "ac3", "hevc", "dts") is supported.
     */
    @JvmStatic
    external fun isCodecSupported(codecMime: String): Boolean

    // --- Video Decoding & Direct Rendering ---
    @JvmStatic
    external fun nativeInitVideoDecoder(mimeType: String, surface: Surface?, width: Int, height: Int): Long

    @JvmStatic
    external fun nativeSetVideoSurface(handle: Long, surface: Surface?): Boolean

    @JvmStatic
    external fun nativeDecodeVideoFrame(handle: Long, inputData: ByteBuffer, inputSize: Int, timeUs: Long): Int

    @JvmStatic
    external fun nativeReleaseVideoDecoder(handle: Long)

    // --- Audio Decoding ---
    @JvmStatic
    external fun nativeInitAudioDecoder(mimeType: String, sampleRate: Int, channelCount: Int): Long

    @JvmStatic
    external fun nativeDecodeAudioFrame(
        handle: Long,
        inputData: ByteBuffer,
        inputSize: Int,
        outputData: ByteBuffer,
        outputCapacity: Int
    ): Int

    @JvmStatic
    external fun nativeFlushAudioDecoder(handle: Long)

    @JvmStatic
    external fun nativeReleaseAudioDecoder(handle: Long)
}

