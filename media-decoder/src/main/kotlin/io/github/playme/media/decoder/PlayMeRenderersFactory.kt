package io.github.playme.media.decoder

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.video.VideoRendererEventListener
import java.util.ArrayList

/**
 * Drop-in custom RenderersFactory for Media3 ExoPlayer.
 *
 * Implements:
 * 1. 4 Decoder Modes: AUTO, HARDWARE, SYSTEM_SOFTWARE, FFMPEG.
 * 2. Custom MediaCodecSelectors for Hardware vs System Software isolation.
 * 3. PlayMeTextRenderer integration for Subtitle Delay & Speed adjustments.
 */
@OptIn(UnstableApi::class)
open class PlayMeRenderersFactory @JvmOverloads constructor(
    context: Context,
    private var decoderMode: DecoderMode = DecoderMode.AUTO
) : DefaultRenderersFactory(context) {

    private var videoDecoderMode: DecoderMode = decoderMode
    private var audioDecoderMode: DecoderMode = decoderMode

    fun setDecoderMode(mode: DecoderMode): PlayMeRenderersFactory = apply {
        this.decoderMode = mode
        this.videoDecoderMode = mode
        this.audioDecoderMode = mode
        updateExtensionRendererMode()
    }

    fun setVideoDecoderMode(mode: DecoderMode): PlayMeRenderersFactory = apply {
        this.videoDecoderMode = mode
        updateExtensionRendererMode()
    }

    fun setAudioDecoderMode(mode: DecoderMode): PlayMeRenderersFactory = apply {
        this.audioDecoderMode = mode
        updateExtensionRendererMode()
    }

    private fun updateExtensionRendererMode() {
        when {
            videoDecoderMode == DecoderMode.FFMPEG || audioDecoderMode == DecoderMode.FFMPEG -> {
                setExtensionRendererMode(EXTENSION_RENDERER_MODE_PREFER)
            }
            videoDecoderMode == DecoderMode.HARDWARE && audioDecoderMode == DecoderMode.HARDWARE -> {
                setExtensionRendererMode(EXTENSION_RENDERER_MODE_OFF)
            }
            else -> {
                setExtensionRendererMode(EXTENSION_RENDERER_MODE_ON)
            }
        }
    }

    private fun resolveMediaCodecSelector(mode: DecoderMode, defaultSelector: MediaCodecSelector): MediaCodecSelector {
        return when (mode) {
            DecoderMode.HARDWARE -> HardwareMediaCodecSelector(defaultSelector)
            DecoderMode.SYSTEM_SOFTWARE -> SystemSoftwareMediaCodecSelector(defaultSelector)
            else -> defaultSelector
        }
    }

    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>
    ) {
        val softwareRenderer = PlayMeVideoRenderer(
            allowedJoiningTimeMs = allowedVideoJoiningTimeMs,
            eventHandler = eventHandler,
            eventListener = eventListener
        )

        val resolvedSelector = resolveMediaCodecSelector(videoDecoderMode, mediaCodecSelector)

        when (videoDecoderMode) {
            DecoderMode.FFMPEG -> {
                // FFmpeg takes absolute priority
                out.add(softwareRenderer)
                super.buildVideoRenderers(
                    context,
                    extensionRendererMode,
                    resolvedSelector,
                    enableDecoderFallback,
                    eventHandler,
                    eventListener,
                    allowedVideoJoiningTimeMs,
                    out
                )
            }
            DecoderMode.HARDWARE -> {
                // Strict Hardware only, no software fallback
                super.buildVideoRenderers(
                    context,
                    EXTENSION_RENDERER_MODE_OFF,
                    resolvedSelector,
                    enableDecoderFallback,
                    eventHandler,
                    eventListener,
                    allowedVideoJoiningTimeMs,
                    out
                )
            }
            DecoderMode.SYSTEM_SOFTWARE -> {
                // System software codecs only (c2.android.* / OMX.google.*)
                super.buildVideoRenderers(
                    context,
                    EXTENSION_RENDERER_MODE_OFF,
                    resolvedSelector,
                    enableDecoderFallback,
                    eventHandler,
                    eventListener,
                    allowedVideoJoiningTimeMs,
                    out
                )
            }
            DecoderMode.AUTO -> {
                // Hardware first, then System Software, then FFmpeg extension
                super.buildVideoRenderers(
                    context,
                    extensionRendererMode,
                    mediaCodecSelector,
                    true, // Enable decoder fallback
                    eventHandler,
                    eventListener,
                    allowedVideoJoiningTimeMs,
                    out
                )
                out.add(softwareRenderer)
            }
        }
    }

    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>
    ) {
        val softwareRenderer = PlayMeAudioRenderer(
            eventHandler = eventHandler,
            eventListener = eventListener,
            audioSink = audioSink
        )

        val resolvedSelector = resolveMediaCodecSelector(audioDecoderMode, mediaCodecSelector)

        when (audioDecoderMode) {
            DecoderMode.FFMPEG -> {
                out.add(softwareRenderer)
                super.buildAudioRenderers(
                    context,
                    extensionRendererMode,
                    resolvedSelector,
                    enableDecoderFallback,
                    audioSink,
                    eventHandler,
                    eventListener,
                    out
                )
            }
            DecoderMode.HARDWARE, DecoderMode.SYSTEM_SOFTWARE -> {
                super.buildAudioRenderers(
                    context,
                    EXTENSION_RENDERER_MODE_OFF,
                    resolvedSelector,
                    enableDecoderFallback,
                    audioSink,
                    eventHandler,
                    eventListener,
                    out
                )
            }
            DecoderMode.AUTO -> {
                super.buildAudioRenderers(
                    context,
                    extensionRendererMode,
                    mediaCodecSelector,
                    true,
                    audioSink,
                    eventHandler,
                    eventListener,
                    out
                )
                out.add(softwareRenderer)
            }
        }
    }

    // Point 4: Inject PlayMeTextRenderer for subtitle delay and speed adjustments
    override fun buildTextRenderers(
        context: Context,
        output: TextOutput,
        outputLooper: Looper,
        extensionRendererMode: Int,
        out: ArrayList<Renderer>
    ) {
        out.add(PlayMeTextRenderer(output, outputLooper))
    }
}

// ==============================================================================
// MediaCodecSelectors for HARDWARE and SYSTEM_SOFTWARE Isolation
// ==============================================================================

@OptIn(UnstableApi::class)
internal class HardwareMediaCodecSelector(
    private val delegate: MediaCodecSelector
) : MediaCodecSelector {
    override fun getDecoderInfos(
        mimeType: String,
        requiresSecureDecoder: Boolean,
        requiresTunnelingDecoder: Boolean
    ): List<MediaCodecInfo> {
        val all = delegate.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
        return all.filter { info ->
            info.hardwareAccelerated &&
                !info.softwareOnly &&
                !info.name.startsWith("c2.android.") &&
                !info.name.startsWith("OMX.google.")
        }
    }
}

@OptIn(UnstableApi::class)
internal class SystemSoftwareMediaCodecSelector(
    private val delegate: MediaCodecSelector
) : MediaCodecSelector {
    override fun getDecoderInfos(
        mimeType: String,
        requiresSecureDecoder: Boolean,
        requiresTunnelingDecoder: Boolean
    ): List<MediaCodecInfo> {
        val all = delegate.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
        return all.filter { info ->
            info.softwareOnly ||
                info.name.startsWith("c2.android.") ||
                info.name.startsWith("OMX.google.")
        }
    }
}
