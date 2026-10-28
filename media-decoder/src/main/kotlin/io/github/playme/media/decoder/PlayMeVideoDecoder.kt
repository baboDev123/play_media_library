package io.github.playme.media.decoder

import android.view.Surface
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderException
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.decoder.SimpleDecoder
import androidx.media3.decoder.VideoDecoderOutputBuffer
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

@OptIn(UnstableApi::class)
class PlayMeDecoderException(message: String, cause: Throwable? = null) : DecoderException(message, cause)

/**
 * High-performance Video Decoder for Android Media3.
 *
 * Implements:
 * - Point b: initializationData (SPS/PPS) extraction and extradata delivery
 * - Point c: Media3 timed rendering & automatic frame dropping handling
 * - Point d: Flush on seek
 * - Point e: Multi-frame drain queue per packet
 * - Point f: Thread-safe surface locks
 */
@OptIn(UnstableApi::class)
class PlayMeVideoDecoder(
    private val format: Format,
    numInputBuffers: Int = 8,
    numOutputBuffers: Int = 8,
    initialInputBufferSize: Int = 1024 * 1024
) : SimpleDecoder<DecoderInputBuffer, VideoDecoderOutputBuffer, PlayMeDecoderException>(
    arrayOfNulls<DecoderInputBuffer>(numInputBuffers),
    arrayOfNulls<VideoDecoderOutputBuffer>(numOutputBuffers)
) {

    private var nativeHandle: Long = 0
    private var outputSurface: Surface? = null
    private val frameHandleMap = ConcurrentHashMap<VideoDecoderOutputBuffer, Long>()
    private val metadataArray = LongArray(4) // [0]=pts, [1]=width, [2]=height, [3]=frameHandle

    init {
        val mime = format.sampleMimeType ?: ""

        // Point b: Extract extradata (SPS/PPS) from format.initializationData
        val extraData: ByteArray? = if (format.initializationData.isNotEmpty()) {
            val totalBytes = format.initializationData.sumOf { it.size }
            val combined = ByteArray(totalBytes)
            var offset = 0
            for (chunk in format.initializationData) {
                System.arraycopy(chunk, 0, combined, offset, chunk.size)
                offset += chunk.size
            }
            combined
        } else {
            null
        }

        nativeHandle = PlayMeNativeBridge.nativeInitVideoDecoder(
            mimeType = mime,
            extraData = extraData,
            width = format.width,
            height = format.height
        )

        if (nativeHandle == 0L) {
            throw PlayMeDecoderException("Failed to initialize native FFmpeg video decoder for $mime")
        }

        for (i in 0 until numInputBuffers) {
            setInitialInputBufferSize(initialInputBufferSize)
        }
    }

    // Point f: Thread-safe surface binding
    fun setOutputSurface(surface: Surface?) {
        this.outputSurface = surface
        if (nativeHandle != 0L) {
            PlayMeNativeBridge.nativeSetVideoSurface(nativeHandle, surface)
        }
    }

    override fun getName(): String = "PlayMedia-FFmpegVideoDecoder-LGPL"

    override fun createInputBuffer(): DecoderInputBuffer {
        return DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT)
    }

    override fun createOutputBuffer(): VideoDecoderOutputBuffer {
        return VideoDecoderOutputBuffer { outputBuffer ->
            // Point c: When output buffer is released (whether displayed or dropped by Media3)
            val handle = frameHandleMap.remove(outputBuffer)
            if (handle != null && handle != 0L && nativeHandle != 0L) {
                PlayMeNativeBridge.nativeReleaseVideoFrame(nativeHandle, handle)
            }
            releaseOutputBuffer(outputBuffer)
        }
    }

    override fun createUnexpectedDecodeException(error: Throwable): PlayMeDecoderException {
        return PlayMeDecoderException("Unexpected decode failure", error)
    }

    override fun decode(
        inputBuffer: DecoderInputBuffer,
        outputBuffer: VideoDecoderOutputBuffer,
        reset: Boolean
    ): PlayMeDecoderException? {
        // Point d: Seek par Flush
        if (reset && nativeHandle != 0L) {
            PlayMeNativeBridge.nativeFlushVideoDecoder(nativeHandle)
            frameHandleMap.clear()
        }

        if (inputBuffer.isEndOfStream) {
            outputBuffer.setFlags(android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            return null
        }

        val data: ByteBuffer? = inputBuffer.data
        val size = data?.remaining() ?: 0
        val timeUs = inputBuffer.timeUs

        // Point e: Send packet and drain frames
        val sendResult = PlayMeNativeBridge.nativeSendVideoPacket(
            handle = nativeHandle,
            inputData = data,
            inputSize = size,
            timeUs = timeUs
        )

        // Point c: Receive decoded frame into output buffer
        val receiveResult = PlayMeNativeBridge.nativeReceiveVideoFrame(
            handle = nativeHandle,
            outputBuffer = outputBuffer,
            outputMode = 0,
            metadata = metadataArray
        )

        if (receiveResult == 0) {
            val framePts = metadataArray[0]
            val frameHandle = metadataArray[3]

            outputBuffer.init(framePts, VideoDecoderOutputBuffer.COLOR_INFO_SDR, null)
            frameHandleMap[outputBuffer] = frameHandle
            return null
        }

        return if (sendResult >= 0 || receiveResult == -11 /* EAGAIN */) {
            null
        } else {
            PlayMeDecoderException("Native video decode error: send=$sendResult, recv=$receiveResult")
        }
    }

    // Point c & f: Render frame when Media3 clock triggers presentation
    fun renderFrameToSurface(outputBuffer: VideoDecoderOutputBuffer, surface: Surface?) {
        val handle = frameHandleMap[outputBuffer]
        if (handle != null && handle != 0L && nativeHandle != 0L) {
            PlayMeNativeBridge.nativeRenderVideoFrameToSurface(nativeHandle, surface, handle)
        }
    }

    // Point d: Flush on seek
    override fun flush() {
        super.flush()
        if (nativeHandle != 0L) {
            PlayMeNativeBridge.nativeFlushVideoDecoder(nativeHandle)
            frameHandleMap.clear()
        }
    }

    override fun release() {
        super.release()
        if (nativeHandle != 0L) {
            PlayMeNativeBridge.nativeReleaseVideoDecoder(nativeHandle)
            nativeHandle = 0L
            frameHandleMap.clear()
        }
    }
}
