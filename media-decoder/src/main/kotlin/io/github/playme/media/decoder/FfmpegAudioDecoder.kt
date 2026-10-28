package io.github.playme.media.decoder

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.decoder.SimpleDecoder
import androidx.media3.decoder.SimpleDecoderOutputBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Decodes one audio stream with FFmpeg into interleaved 16-bit PCM, for [PlayMeAudioRenderer]. */
@Suppress("UNCHECKED_CAST")
class FfmpegAudioDecoder internal constructor(format: Format) :
    SimpleDecoder<DecoderInputBuffer, SimpleDecoderOutputBuffer, PlayMeDecoderException>(
        arrayOfNulls<DecoderInputBuffer>(BUFFERS) as Array<DecoderInputBuffer>,
        arrayOfNulls<SimpleDecoderOutputBuffer>(BUFFERS) as Array<SimpleDecoderOutputBuffer>,
    ) {

    private val handle: Long = try {
        AudioNative.create(format.sampleMimeType.orEmpty(), extraDataOf(format), format.sampleRate, format.channelCount)
    } catch (error: IllegalStateException) {
        throw PlayMeDecoderException(error.message ?: "Couldn't open the FFmpeg audio decoder", error)
    }
    private val name = "playmedia-${AudioNative.name(handle)}"

    /** Channels in the decoded PCM; known for sure after the first sample. */
    @Volatile
    var channelCount: Int = AudioNative.outputChannels(handle)
        private set

    /** Sample rate of the decoded PCM; known for sure after the first sample. */
    @Volatile
    var sampleRate: Int = AudioNative.outputSampleRate(handle)
        private set

    init {
        setInitialInputBufferSize(if (format.maxInputSize != Format.NO_VALUE) format.maxInputSize else DEFAULT_INPUT_SIZE)
    }

    override fun getName(): String = name

    override fun createInputBuffer() = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT)

    override fun createOutputBuffer() = SimpleDecoderOutputBuffer(::releaseOutputBuffer)

    override fun createUnexpectedDecodeException(error: Throwable) =
        PlayMeDecoderException("Unexpected audio decoding failure", error)

    override fun decode(
        inputBuffer: DecoderInputBuffer,
        outputBuffer: SimpleDecoderOutputBuffer,
        reset: Boolean,
    ): PlayMeDecoderException? {
        if (reset) AudioNative.flush(handle)
        val data = inputBuffer.data ?: return null
        val size = AudioNative.decode(handle, data, data.position(), data.remaining())
        if (size < 0) return PlayMeDecoderException("Audio decoding failed ($size)")
        if (size == 0) {
            // The codec wants more input before it has audio, or skipped a corrupt packet.
            outputBuffer.shouldBeSkipped = true
            return null
        }
        channelCount = AudioNative.outputChannels(handle)
        sampleRate = AudioNative.outputSampleRate(handle)
        val pcm = outputBuffer.init(inputBuffer.timeUs, size)
        AudioNative.copyOutput(handle, pcm, 0)
        return null
    }

    override fun release() {
        super.release()
        AudioNative.destroy(handle)
    }

    internal companion object {
        private const val BUFFERS = 16
        private const val DEFAULT_INPUT_SIZE = 64 * 1024

        /** The codec configuration FFmpeg expects, built from Media3's initialization data. */
        fun extraDataOf(format: Format): ByteArray? {
            val parts = format.initializationData
            val first = parts.firstOrNull() ?: return null
            return when (format.sampleMimeType) {
                MimeTypes.AUDIO_AAC, MimeTypes.AUDIO_OPUS, MimeTypes.AUDIO_FLAC -> first
                MimeTypes.AUDIO_ALAC -> alacAtom(first)
                MimeTypes.AUDIO_VORBIS -> if (parts.size >= 2) vorbisHeaders(parts[0], parts[1]) else null
                else -> null
            }
        }

        /** FFmpeg reads ALAC's "magic cookie" as a complete 'alac' atom. */
        private fun alacAtom(cookie: ByteArray): ByteArray {
            val isAtom = cookie.size >= 8 && String(cookie, 4, 4, Charsets.US_ASCII) == "alac"
            if (isAtom) return cookie
            return ByteBuffer.allocate(12 + cookie.size).order(ByteOrder.BIG_ENDIAN)
                .putInt(12 + cookie.size)
                .put("alac".toByteArray(Charsets.US_ASCII))
                .putInt(0)
                .put(cookie)
                .array()
        }

        /**
         * Vorbis's three headers in Xiph lacing. Media3 keeps the identification and setup
         * headers; the comment header, which decoding ignores, is an empty one.
         */
        private fun vorbisHeaders(identification: ByteArray, setup: ByteArray): ByteArray {
            val comment = byteArrayOf(3, 'v'.code.toByte(), 'o'.code.toByte(), 'r'.code.toByte(), 'b'.code.toByte(),
                'i'.code.toByte(), 's'.code.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 1)
            val out = java.io.ByteArrayOutputStream()
            out.write(2)
            for (header in listOf(identification, comment)) {
                var length = header.size
                while (length >= 255) {
                    out.write(255)
                    length -= 255
                }
                out.write(length)
            }
            out.write(identification)
            out.write(comment)
            out.write(setup)
            return out.toByteArray()
        }
    }
}
