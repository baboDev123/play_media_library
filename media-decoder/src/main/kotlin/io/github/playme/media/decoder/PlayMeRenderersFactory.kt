package io.github.playme.media.decoder

import android.content.Context
import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.VideoRendererEventListener
import java.util.ArrayList

/**
 * Drop-in custom RenderersFactory for Media3 ExoPlayer.
 * Seamlessly integrates FFmpeg-backed Software Decoders alongside Android Hardware Decoders.
 *
 * Fully compatible with Kotlin DSL and Groovy DSL:
 * ```groovy
 * def factory = new PlayMeRenderersFactory(context)
 *     .setDecoderMode(DecoderMode.AUTO)
 * ```
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
        if (videoDecoderMode == DecoderMode.SOFTWARE || audioDecoderMode == DecoderMode.SOFTWARE) {
            setExtensionRendererMode(EXTENSION_RENDERER_MODE_PREFER)
        } else if (videoDecoderMode == DecoderMode.HARDWARE && audioDecoderMode == DecoderMode.HARDWARE) {
            setExtensionRendererMode(EXTENSION_RENDERER_MODE_OFF)
        } else {
            setExtensionRendererMode(EXTENSION_RENDERER_MODE_ON)
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

        when (videoDecoderMode) {
            DecoderMode.SOFTWARE -> {
                // Software decoder takes top priority
                out.add(softwareRenderer)
                super.buildVideoRenderers(
                    context,
                    extensionRendererMode,
                    mediaCodecSelector,
                    enableDecoderFallback,
                    eventHandler,
                    eventListener,
                    allowedVideoJoiningTimeMs,
                    out
                )
            }
            DecoderMode.HARDWARE -> {
                // Hardware first, no software extension
                super.buildVideoRenderers(
                    context,
                    EXTENSION_RENDERER_MODE_OFF,
                    mediaCodecSelector,
                    enableDecoderFallback,
                    eventHandler,
                    eventListener,
                    allowedVideoJoiningTimeMs,
                    out
                )
            }
            DecoderMode.AUTO -> {
                // Hardware first, then software fallback
                super.buildVideoRenderers(
                    context,
                    extensionRendererMode,
                    mediaCodecSelector,
                    enableDecoderFallback,
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

        when (audioDecoderMode) {
            DecoderMode.SOFTWARE -> {
                // Software audio decoder first (handles AC3, DTS, TrueHD directly)
                out.add(softwareRenderer)
                super.buildAudioRenderers(
                    context,
                    extensionRendererMode,
                    mediaCodecSelector,
                    enableDecoderFallback,
                    audioSink,
                    eventHandler,
                    eventListener,
                    out
                )
            }
            DecoderMode.HARDWARE -> {
                super.buildAudioRenderers(
                    context,
                    EXTENSION_RENDERER_MODE_OFF,
                    mediaCodecSelector,
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
                    enableDecoderFallback,
                    audioSink,
                    eventHandler,
                    eventListener,
                    out
                )
                out.add(softwareRenderer)
            }
        }
    }
}

