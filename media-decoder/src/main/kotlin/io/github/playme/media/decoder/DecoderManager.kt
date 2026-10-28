package io.github.playme.media.decoder

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import io.github.playme.media.core.PlayMediaNative
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Chooses the decoders of a player, and changes them while it plays.
 *
 * [PlayMeRenderersFactory] builds a renderer for each kind of decoder (all device decoders, the
 * device's hardware ones, its software ones, FFmpeg); this decides which of them may take a
 * format. Selecting another mode tells the player those renderers changed, and Media3 moves the
 * track to the right one at the current position, so the player and its session stay as they are.
 *
 * Call [attach] once the player is built so that changes apply right away; without it they apply
 * from the next track selection (the next item, or the next prepare).
 */
class DecoderManager @JvmOverloads constructor(
    videoMode: DecoderMode = DecoderMode.AUTO,
    audioMode: DecoderMode = DecoderMode.AUTO,
) {
    /** The video decoding the app asked for. */
    @Volatile
    var videoMode: DecoderMode = videoMode
        private set

    /** The audio decoding the app asked for. */
    @Volatile
    var audioMode: DecoderMode = audioMode
        private set

    /**
     * The video decoding in use: [DecoderMode.FFMPEG] when FFmpeg decodes, [DecoderMode.AUTO] for
     * the device's decoders picked automatically, null with no video playing.
     */
    @Volatile
    var activeVideoMode: DecoderMode? = null
        private set

    /** The audio decoding in use, as [activeVideoMode]. */
    @Volatile
    var activeAudioMode: DecoderMode? = null
        private set

    private val renderers = CopyOnWriteArrayList<ModeGatedRenderer>()

    fun selectVideoDecoder(mode: DecoderMode) {
        if (mode == videoMode) return
        videoMode = mode
        capabilitiesChanged(C.TRACK_TYPE_VIDEO)
    }

    fun selectAudioDecoder(mode: DecoderMode) {
        if (mode == audioMode) return
        audioMode = mode
        capabilitiesChanged(C.TRACK_TYPE_AUDIO)
    }

    /**
     * Lets decoder changes apply while [player] plays. Its track selector must be a
     * [DefaultTrackSelector] (ExoPlayer's default).
     */
    fun attach(player: ExoPlayer) {
        val selector = requireNotNull(player.trackSelector as? DefaultTrackSelector) {
            "DecoderManager needs the player's track selector to be a DefaultTrackSelector"
        }
        selector.setParameters(selector.buildUponParameters().setAllowInvalidateSelectionsOnRendererCapabilitiesChange(true))
    }

    /** Stops tracking the renderers of the player being released. */
    fun detach() {
        renderers.clear()
        activeVideoMode = null
        activeAudioMode = null
    }

    internal fun register(renderer: ModeGatedRenderer) {
        renderers += renderer
    }

    /** Whether a renderer of [kind] may decode [format] in the current mode. */
    internal fun allows(kind: DecoderKind, trackType: Int, format: Format): Boolean {
        val video = trackType == C.TRACK_TYPE_VIDEO
        val mode = if (video) videoMode else audioMode
        return when (kind) {
            DecoderKind.FFMPEG -> mode == DecoderMode.FFMPEG || mode == DecoderMode.AUTO
            DecoderKind.DEVICE -> when (mode) {
                DecoderMode.AUTO -> true
                // Formats FFmpeg doesn't know still play on the device.
                DecoderMode.FFMPEG -> !ffmpegDecodes(format, video)
                // Android's audio decoders are software: "hardware" audio means the device's.
                DecoderMode.HARDWARE -> !video
                DecoderMode.SYSTEM_SOFTWARE -> false
            }
            DecoderKind.HARDWARE -> mode == DecoderMode.HARDWARE
            DecoderKind.SYSTEM_SOFTWARE -> mode == DecoderMode.SYSTEM_SOFTWARE
        }
    }

    internal fun onRendererEnabled(kind: DecoderKind, trackType: Int, enabled: Boolean) {
        val mode = if (enabled) kind.mode else null
        when (trackType) {
            C.TRACK_TYPE_VIDEO -> if (enabled || activeVideoMode == kind.mode) activeVideoMode = mode
            C.TRACK_TYPE_AUDIO -> if (enabled || activeAudioMode == kind.mode) activeAudioMode = mode
        }
    }

    private fun capabilitiesChanged(trackType: Int) {
        for (renderer in renderers) {
            if (renderer.getTrackType() == trackType) renderer.notifyCapabilitiesChanged()
        }
    }

    private fun ffmpegDecodes(format: Format, video: Boolean): Boolean {
        val mimeType = format.sampleMimeType ?: return false
        return if (video) PlayMediaNative.canDecodeVideo(mimeType) else PlayMediaNative.canDecodeAudio(mimeType)
    }
}

/** The kinds of decoder renderer [PlayMeRenderersFactory] builds, and the mode each reports. */
internal enum class DecoderKind(val mode: DecoderMode) {
    DEVICE(DecoderMode.AUTO),
    HARDWARE(DecoderMode.HARDWARE),
    SYSTEM_SOFTWARE(DecoderMode.SYSTEM_SOFTWARE),
    FFMPEG(DecoderMode.FFMPEG),
}
