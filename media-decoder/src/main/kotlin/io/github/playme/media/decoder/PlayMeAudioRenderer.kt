package io.github.playme.media.decoder

import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.CryptoConfig
import androidx.media3.decoder.Decoder
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.decoder.SimpleDecoderOutputBuffer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DecoderAudioRenderer
import androidx.media3.exoplayer.audio.DefaultAudioSink

@OptIn(UnstableApi::class)
class PlayMeAudioRenderer @JvmOverloads constructor(
    eventHandler: Handler? = null,
    eventListener: AudioRendererEventListener? = null,
    audioSink: AudioSink = DefaultAudioSink.Builder().build()
) : DecoderAudioRenderer<Decoder<DecoderInputBuffer, SimpleDecoderOutputBuffer, PlayMeDecoderException>>(
    eventHandler,
    eventListener,
    audioSink
) {

    override fun getName(): String = "PlayMeAudioRenderer"

    override fun supportsFormat(format: Format): Int {
        val mimeType = format.sampleMimeType ?: return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE)
        if (!MimeTypes.isAudio(mimeType)) {
            return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE)
        }

        return if (PlayMeNativeBridge.isCodecSupported(mimeType)) {
            RendererCapabilities.create(C.FORMAT_HANDLED)
        } else {
            RendererCapabilities.create(C.FORMAT_UNSUPPORTED_SUBTYPE)
        }
    }

    override fun createDecoder(
        format: Format,
        cryptoConfig: CryptoConfig?
    ): Decoder<DecoderInputBuffer, SimpleDecoderOutputBuffer, PlayMeDecoderException> {
        return PlayMeAudioDecoder(format)
    }

    override fun getOutputFormat(decoder: Decoder<DecoderInputBuffer, SimpleDecoderOutputBuffer, PlayMeDecoderException>): Format {
        val audioDecoder = decoder as? PlayMeAudioDecoder
        val sampleRate = audioDecoder?.sampleRate ?: 44100
        val channelCount = audioDecoder?.channelCount ?: 2

        return Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setChannelCount(channelCount)
            .setSampleRate(sampleRate)
            .setPcmEncoding(C.ENCODING_PCM_16BIT)
            .build()
    }
}

