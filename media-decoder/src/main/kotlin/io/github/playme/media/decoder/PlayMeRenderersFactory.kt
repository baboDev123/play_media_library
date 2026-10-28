package io.github.playme.media.decoder

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.video.VideoRendererEventListener

/**
 * Media3 renderers with FFmpeg decoding, chosen by [DecoderMode] through [decoderManager]:
 *
 * - Video: [DecoderMode.AUTO] tries the device's decoders and falls back to FFmpeg for what they
 *   can't play; [DecoderMode.HARDWARE] and [DecoderMode.SYSTEM_SOFTWARE] use only that kind of
 *   device decoder; [DecoderMode.FFMPEG] uses FFmpeg, and the device only for formats FFmpeg
 *   doesn't know.
 * - Audio: the same, except that Android's audio decoders are software anyway, so
 *   [DecoderMode.HARDWARE] means the device's decoders.
 * - Subtitles follow [subtitleTiming].
 *
 * The mode can change while playing: see [DecoderManager].
 */
open class PlayMeRenderersFactory @JvmOverloads constructor(
    context: Context,
    decoderMode: DecoderMode = DecoderMode.AUTO,
    val subtitleTiming: SubtitleTiming = SubtitleTiming(),
    val decoderManager: DecoderManager = DecoderManager(decoderMode, decoderMode),
) : DefaultRenderersFactory(context) {

    init {
        // FFmpeg renderers are added here, not looked up by Media3's extension mechanism.
        setExtensionRendererMode(EXTENSION_RENDERER_MODE_OFF)
    }

    fun setDecoderMode(mode: DecoderMode): PlayMeRenderersFactory = apply {
        decoderManager.selectVideoDecoder(mode)
        decoderManager.selectAudioDecoder(mode)
    }

    fun setVideoDecoderMode(mode: DecoderMode): PlayMeRenderersFactory = apply { decoderManager.selectVideoDecoder(mode) }

    fun setAudioDecoderMode(mode: DecoderMode): PlayMeRenderersFactory = apply { decoderManager.selectAudioDecoder(mode) }

    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>,
    ) {
        fun device(kind: DecoderKind, selector: MediaCodecSelector) {
            val built = ArrayList<Renderer>()
            super.buildVideoRenderers(
                context, EXTENSION_RENDERER_MODE_OFF, selector, enableDecoderFallback,
                eventHandler, eventListener, allowedVideoJoiningTimeMs, built,
            )
            built.mapTo(out) { ModeGatedRenderer(it, kind, decoderManager) }
        }
        // On a tie the first renderer wins: the device's decoders come before FFmpeg.
        device(DecoderKind.DEVICE, mediaCodecSelector)
        device(DecoderKind.HARDWARE, HardwareMediaCodecSelector(mediaCodecSelector))
        device(DecoderKind.SYSTEM_SOFTWARE, SystemSoftwareMediaCodecSelector(mediaCodecSelector))
        val ffmpeg = PlayMeVideoRenderer(allowedVideoJoiningTimeMs, eventHandler, eventListener, MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY)
        out += ModeGatedRenderer(ffmpeg, DecoderKind.FFMPEG, decoderManager)
    }

    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>,
    ) {
        fun device(kind: DecoderKind, selector: MediaCodecSelector) {
            val built = ArrayList<Renderer>()
            super.buildAudioRenderers(
                context, EXTENSION_RENDERER_MODE_OFF, selector, enableDecoderFallback,
                audioSink, eventHandler, eventListener, built,
            )
            built.mapTo(out) { ModeGatedRenderer(it, kind, decoderManager) }
        }
        device(DecoderKind.DEVICE, mediaCodecSelector)
        device(DecoderKind.SYSTEM_SOFTWARE, SystemSoftwareMediaCodecSelector(mediaCodecSelector))
        out += ModeGatedRenderer(PlayMeAudioRenderer(eventHandler, eventListener, audioSink), DecoderKind.FFMPEG, decoderManager)
    }

    override fun buildTextRenderers(
        context: Context,
        output: TextOutput,
        outputLooper: Looper,
        extensionRendererMode: Int,
        out: ArrayList<Renderer>,
    ) {
        val first = out.size
        super.buildTextRenderers(context, output, outputLooper, extensionRendererMode, out)
        for (index in first until out.size) out[index] = TimedTextRenderer(out[index], subtitleTiming)
    }
}

/** Device decoders that run on dedicated hardware. */
internal class HardwareMediaCodecSelector(private val delegate: MediaCodecSelector) : MediaCodecSelector {
    override fun getDecoderInfos(
        mimeType: String,
        requiresSecureDecoder: Boolean,
        requiresTunnelingDecoder: Boolean,
    ): List<MediaCodecInfo> = delegate.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
        .filter { it.hardwareAccelerated && !it.softwareOnly }
}

/** The platform's own software decoders (c2.android.*, OMX.google.*). */
internal class SystemSoftwareMediaCodecSelector(private val delegate: MediaCodecSelector) : MediaCodecSelector {
    override fun getDecoderInfos(
        mimeType: String,
        requiresSecureDecoder: Boolean,
        requiresTunnelingDecoder: Boolean,
    ): List<MediaCodecInfo> = delegate.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
        .filter { it.softwareOnly || it.name.startsWith("c2.android.") || it.name.startsWith("OMX.google.") }
}
