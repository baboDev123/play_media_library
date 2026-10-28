package io.github.playme.media.decoder

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.decoder.SimpleDecoder
import androidx.media3.decoder.SimpleDecoderOutputBuffer
import java.nio.ByteBuffer

@OptIn(UnstableApi::class)
class PlayMeAudioDecoder(
    private val format: Format,
    numInputBuffers: Int = 16,
    numOutputBuffers: Int = 16,
    initialInputBufferSize: Int = 32 * 1024
) : SimpleDecoder<DecoderInputBuffer, SimpleDecoderOutputBuffer, PlayMeDecoderException>(
    arrayOfNulls<DecoderInputBuffer>(numInputBuffers),
    arrayOfNulls<SimpleDecoderOutputBuffer>(numOutputBuffers)
) {

    private var nativeHandle: Long = 0
    val sampleRate: Int = if (format.sampleRate != Format.NO_VALUE) format.sampleRate else 44100
    val channelCount: Int = if (format.channelCount != Format.NO_VALUE) format.channelCount else 2

    init {
        val mime = format.sampleMimeType ?: ""
        nativeHandle = PlayMeNativeBridge.nativeInitAudioDecoder(
            mimeType = mime,
            sampleRate = sampleRate,
            channelCount = channelCount
        )
        if (nativeHandle == 0L) {
            throw PlayMeDecoderException("Failed to initialize native FFmpeg audio decoder for $mime")
        }
        for (i in 0 until numInputBuffers) {
            setInitialInputBufferSize(initialInputBufferSize)
        }
    }

    override fun getName(): String = "PlayMe-FFmpegAudioDecoder"

    override fun createInputBuffer(): DecoderInputBuffer {
        return DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT)
    }

    override fun createOutputBuffer(): SimpleDecoderOutputBuffer {
        return SimpleDecoderOutputBuffer { outputBuffer ->
            releaseOutputBuffer(outputBuffer)
        }
    }

    override fun createUnexpectedDecodeException(error: Throwable): PlayMeDecoderException {
        return PlayMeDecoderException("Unexpected audio decode failure", error)
    }

    override fun decode(
        inputBuffer: DecoderInputBuffer,
        outputBuffer: SimpleDecoderOutputBuffer,
        reset: Boolean
    ): PlayMeDecoderException? {
        if (inputBuffer.isEndOfStream) {
            outputBuffer.setFlags(C.BUFFER_FLAG_END_OF_STREAM)
            return null
        }

        val inputData = inputBuffer.data ?: return null
        val inputSize = inputData.remaining()

        // Allocate output buffer: 16-bit PCM = 2 bytes per sample * channels * max samples (approx 8192 samples)
        val maxOutputBytes = 8192 * channelCount * 2
        val outputByteBuffer = outputBuffer.init(inputBuffer.timeUs, maxOutputBytes)

        val bytesDecoded = PlayMeNativeBridge.nativeDecodeAudioFrame(
            handle = nativeHandle,
            inputData = inputData,
            inputSize = inputSize,
            outputData = outputByteBuffer,
            outputCapacity = maxOutputBytes
        )

        return if (bytesDecoded >= 0) {
            outputByteBuffer.position(0)
            outputByteBuffer.limit(bytesDecoded)
            null
        } else {
            PlayMeDecoderException("FFmpeg audio decode error code: $bytesDecoded")
        }
    }

    override fun release() {
        super.release()
        if (nativeHandle != 0L) {
            PlayMeNativeBridge.nativeReleaseAudioDecoder(nativeHandle)
            nativeHandle = 0L
        }
    }
}

