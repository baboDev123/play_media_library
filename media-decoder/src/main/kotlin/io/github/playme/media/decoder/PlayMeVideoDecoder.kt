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

@OptIn(UnstableApi::class)
class PlayMeDecoderException(message: String, cause: Throwable? = null) : DecoderException(message, cause)

/**
 * High-performance Video Decoder that delegates to the C++ FFmpeg engine with direct ANativeWindow rendering.
 */
@OptIn(UnstableApi::class)
class PlayMeVideoDecoder(
    private val format: Format,
    numInputBuffers: Int = 4,
    numOutputBuffers: Int = 4,
    initialInputBufferSize: Int = 1024 * 1024
) : SimpleDecoder<DecoderInputBuffer, VideoDecoderOutputBuffer, PlayMeDecoderException>(
    arrayOfNulls<DecoderInputBuffer>(numInputBuffers),
    arrayOfNulls<VideoDecoderOutputBuffer>(numOutputBuffers)
) {

    private var nativeHandle: Long = 0
    private var outputSurface: Surface? = null

    init {
        val mime = format.sampleMimeType ?: ""
        nativeHandle = PlayMeNativeBridge.nativeInitVideoDecoder(
            mimeType = mime,
            surface = null,
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

    fun setOutputSurface(surface: Surface?) {
        this.outputSurface = surface
        if (nativeHandle != 0L) {
            PlayMeNativeBridge.nativeSetVideoSurface(nativeHandle, surface)
        }
    }

    override fun getName(): String = "PlayMe-FFmpegVideoDecoder"

    override fun createInputBuffer(): DecoderInputBuffer {
        return DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT)
    }

    override fun createOutputBuffer(): VideoDecoderOutputBuffer {
        return VideoDecoderOutputBuffer { outputBuffer ->
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
        if (inputBuffer.isEndOfStream) {
            outputBuffer.setFlags(android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            return null
        }

        val data: ByteBuffer = inputBuffer.data ?: return null
        val size = data.remaining()
        val timeUs = inputBuffer.timeUs

        outputBuffer.init(timeUs, VideoDecoderOutputBuffer.COLOR_INFO_SDR, null)

        val result = PlayMeNativeBridge.nativeDecodeVideoFrame(
            handle = nativeHandle,
            inputData = data,
            inputSize = size,
            timeUs = timeUs
        )

        return if (result >= 0) {
            null
        } else {
            PlayMeDecoderException("FFmpeg native decode frame error code: $result")
        }
    }

    override fun release() {
        super.release()
        if (nativeHandle != 0L) {
            PlayMeNativeBridge.nativeReleaseVideoDecoder(nativeHandle)
            nativeHandle = 0L
        }
    }
}

