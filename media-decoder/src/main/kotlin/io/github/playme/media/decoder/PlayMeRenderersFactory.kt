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
 * Media3 renderers with FFmpeg decoding, chosen by [DecoderMode]:
 *
 * - Video: [DecoderMode.AUTO] tries the device's decoders and falls back to FFmpeg for formats
 *   none of them handles; [DecoderMode.HARDWARE] and [DecoderMode.SYSTEM_SOFTWARE] use only that
 *   kind of device decoder; [DecoderMode.FFMPEG] prefers FFmpeg.
 * - Audio: device decoders first and FFmpeg for the rest (Android's audio decoders are software
 *   anyway), except in [DecoderMode.FFMPEG], which prefers FFmpeg.
 * - Subtitles follow [subtitleTiming].
 */
open class PlayMeRenderersFactory @JvmOverloads constructor(
    context: Context,
    decoderMode: DecoderMode = DecoderMode.AUTO,
    val subtitleTiming: SubtitleTiming = SubtitleTiming(),
) : DefaultRenderersFactory(context) {

    private var videoMode = decoderMode
    private var audioMode = decoderMode

    init {
        // FFmpeg renderers are added here, not looked up by Media3's extension mechanism.
        setExtensionRendererMode(EXTENSION_RENDERER_MODE_OFF)
    }

    fun setDecoderMode(mode: DecoderMode): PlayMeRenderersFactory = apply {
        videoMode = mode
        audioMode = mode
    }

    fun setVideoDecoderMode(mode: DecoderMode): PlayMeRenderersFactory = apply { videoMode = mode }

    fun setAudioDecoderMode(mode: DecoderMode): PlayMeRenderersFactory = apply { audioMode = mode }

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
        val ffmpeg = PlayMeVideoRenderer(allowedVideoJoiningTimeMs, eventHandler, eventListener, MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY)
        val selector = when (videoMode) {
            DecoderMode.HARDWARE -> HardwareMediaCodecSelector(mediaCodecSelector)
            DecoderMode.SYSTEM_SOFTWARE -> SystemSoftwareMediaCodecSelector(mediaCodecSelector)
            else -> mediaCodecSelector
        }
        if (videoMode == DecoderMode.FFMPEG) out += ffmpeg
        super.buildVideoRenderers(
            context, EXTENSION_RENDERER_MODE_OFF, selector, enableDecoderFallback || videoMode == DecoderMode.AUTO,
            eventHandler, eventListener, allowedVideoJoiningTimeMs, out,
        )
        if (videoMode == DecoderMode.AUTO) out += ffmpeg
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
        val ffmpeg = PlayMeAudioRenderer(eventHandler, eventListener, audioSink)
        if (audioMode == DecoderMode.FFMPEG) out += ffmpeg
        super.buildAudioRenderers(
            context, EXTENSION_RENDERER_MODE_OFF, mediaCodecSelector, enableDecoderFallback,
            audioSink, eventHandler, eventListener, out,
        )
        if (audioMode != DecoderMode.FFMPEG) out += ffmpeg
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
