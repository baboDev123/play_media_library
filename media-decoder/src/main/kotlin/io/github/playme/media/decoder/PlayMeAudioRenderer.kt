package io.github.playme.media.decoder

import android.os.Handler
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Util
import androidx.media3.decoder.CryptoConfig
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DecoderAudioRenderer
import io.github.playme.media.core.PlayMediaNative

/**
 * Plays audio decoded by FFmpeg (Dolby Digital, DTS, TrueHD, Vorbis, Opus, ALAC...) as 16-bit
 * PCM through [audioSink], so Media3's speed, volume and other audio processing still apply.
 */
class PlayMeAudioRenderer(
    eventHandler: Handler?,
    eventListener: AudioRendererEventListener?,
    audioSink: AudioSink,
) : DecoderAudioRenderer<FfmpegAudioDecoder>(eventHandler, eventListener, audioSink) {

    override fun getName(): String = "PlayMeAudioRenderer"

    override fun supportsFormatInternal(format: Format): Int {
        val mimeType = format.sampleMimeType
        return when {
            mimeType == null || !MimeTypes.isAudio(mimeType) -> C.FORMAT_UNSUPPORTED_TYPE
            !PlayMediaNative.canDecodeAudio(mimeType) -> C.FORMAT_UNSUPPORTED_SUBTYPE
            !sinkSupportsFormat(pcmFormat(format.channelCount, format.sampleRate)) -> C.FORMAT_UNSUPPORTED_SUBTYPE
            format.cryptoType != C.CRYPTO_TYPE_NONE -> C.FORMAT_UNSUPPORTED_DRM
            else -> C.FORMAT_HANDLED
        }
    }

    override fun createDecoder(format: Format, cryptoConfig: CryptoConfig?) = FfmpegAudioDecoder(format)

    override fun getOutputFormat(decoder: FfmpegAudioDecoder): Format = pcmFormat(decoder.channelCount, decoder.sampleRate)

    private fun pcmFormat(channels: Int, sampleRate: Int): Format = Util.getPcmFormat(
        C.ENCODING_PCM_16BIT,
        // More than 8 channels are mixed down to stereo.
        if (channels in 1..8) channels else 2,
        if (sampleRate > 0) sampleRate else 48_000,
    )
}
