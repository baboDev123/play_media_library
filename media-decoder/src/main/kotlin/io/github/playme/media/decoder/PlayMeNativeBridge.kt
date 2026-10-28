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

    @JvmStatic
    external fun isNativeAvailable(): Boolean

    @JvmStatic
    external fun getFfmpegVersion(): String

    @JvmStatic
    external fun isCodecSupported(codecMime: String): Boolean

    // --- Video Decoding & Media3 Surface Rendering ---

    /** Point b: extradata (SPS/PPS) initialization */
    @JvmStatic
    external fun nativeInitVideoDecoder(
        mimeType: String,
        extraData: ByteArray?,
        width: Int,
        height: Int
    ): Long

    /** Point f: Thread-safe surface update */
    @JvmStatic
    external fun nativeSetVideoSurface(handle: Long, surface: Surface?): Boolean

    /** Point e: Multi-frame drain packet sender */
    @JvmStatic
    external fun nativeSendVideoPacket(
        handle: Long,
        inputData: ByteBuffer?,
        inputSize: Int,
        timeUs: Long
    ): Int

    /** Point c: Receive YUV frame metadata for Media3 */
    @JvmStatic
    external fun nativeReceiveVideoFrame(
        handle: Long,
        outputBuffer: Any,
        outputMode: Int,
        metadata: LongArray // [0]=pts, [1]=width, [2]=height, [3]=frameHandle
    ): Int

    /** Point c & f: Render frame when Media3 clock triggers presentation */
    @JvmStatic
    external fun nativeRenderVideoFrameToSurface(
        handle: Long,
        surface: Surface?,
        frameHandle: Long
    ): Boolean

    /** Point c: Release frame when displayed or dropped by Media3 */
    @JvmStatic
    external fun nativeReleaseVideoFrame(handle: Long, frameHandle: Long)

    /** Point d: Seek flush */
    @JvmStatic
    external fun nativeFlushVideoDecoder(handle: Long)

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
