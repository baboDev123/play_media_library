package io.github.playme.media.decoder

import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer

/**
 * Listener for decoder failure and fallback transitions.
 */
interface DecoderFallbackListener {
    /**
     * Called when a decoder encounters an error and fallback is initiated.
     * @param failedMode The DecoderMode that failed.
     * @param error The exception that caused the decoder failure.
     * @param nextMode The next DecoderMode in the fallback chain (or null if all options are exhausted).
     */
    fun onDecoderFailed(failedMode: DecoderMode, error: Throwable, nextMode: DecoderMode?)
}

/**
 * Controller providing smooth runtime switching between decoder modes and automatic fallback chains
 * with complete playback state preservation (position, playWhenReady, speed, track selections).
 */
@OptIn(UnstableApi::class)
class DecoderController(
    private val playerProvider: () -> ExoPlayer?,
    private val playerRebuilder: (DecoderMode) -> Unit,
    var fallbackListener: DecoderFallbackListener? = null
) {
    var currentMode: DecoderMode = DecoderMode.AUTO
        private set

    private var isSwitching = false

    // Fallback progression: HARDWARE -> SYSTEM_SOFTWARE -> FFMPEG
    fun getNextFallbackMode(mode: DecoderMode): DecoderMode? {
        return when (mode) {
            DecoderMode.HARDWARE -> DecoderMode.SYSTEM_SOFTWARE
            DecoderMode.SYSTEM_SOFTWARE -> DecoderMode.FFMPEG
            DecoderMode.AUTO -> DecoderMode.FFMPEG
            DecoderMode.FFMPEG -> null // Exhausted
        }
    }

    /**
     * Attaches an automatic error listener to the active player to trigger the fallback chain on decoder errors.
     */
    fun attachFallbackHandler() {
        val player = playerProvider() ?: return
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (isDecoderError(error) && !isSwitching) {
                    val next = getNextFallbackMode(currentMode)
                    fallbackListener?.onDecoderFailed(currentMode, error, next)
                    if (next != null) {
                        switchDecoderMode(next)
                    }
                }
            }
        })
    }

    private fun isDecoderError(error: PlaybackException): Boolean {
        return when (error.errorCode) {
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES -> true
            else -> {
                val causeName = error.cause?.javaClass?.simpleName ?: ""
                causeName.contains("Decoder") || causeName.contains("MediaCodec")
            }
        }
    }

    /**
     * Smoothly transitions the active player to a new DecoderMode without jarring stutter.
     * Preserves:
     * - playback position
     * - playWhenReady state
     * - playback speed / parameters
     * - selected audio and subtitle tracks (TrackSelectionParameters)
     * - subtitle timing, when every factory shares one [SubtitleTiming]
     * - playlist / current media item
     */
    fun switchDecoderMode(newMode: DecoderMode) {
        if (currentMode == newMode && !isSwitching) return
        isSwitching = true

        val oldPlayer = playerProvider()

        // 1. Snapshot all active playback state
        val savedPosition = oldPlayer?.currentPosition ?: 0L
        val savedPlayWhenReady = oldPlayer?.playWhenReady ?: false
        val savedPlaybackParameters = oldPlayer?.playbackParameters
        val savedMediaItem = oldPlayer?.currentMediaItem
        val savedTrackParameters: TrackSelectionParameters? = oldPlayer?.trackSelectionParameters

        currentMode = newMode

        // 2. Rebuild player with new renderers factory
        playerRebuilder(newMode)

        // 3. Restore all state seamlessly on the new player instance
        val newPlayer = playerProvider()
        if (newPlayer != null) {
            // Restore track selection preferences
            if (savedTrackParameters != null) {
                newPlayer.trackSelectionParameters = savedTrackParameters
            }

            // Restore media item and seek position
            if (savedMediaItem != null && newPlayer.currentMediaItem == null) {
                newPlayer.setMediaItem(savedMediaItem, savedPosition)
            } else {
                newPlayer.seekTo(savedPosition)
            }

            // Restore playback speed / pitch
            if (savedPlaybackParameters != null) {
                newPlayer.playbackParameters = savedPlaybackParameters
            }

            // Attach fallback handler to new player
            attachFallbackHandler()

            // Prepare and resume playback
            newPlayer.prepare()
            newPlayer.playWhenReady = savedPlayWhenReady
        }

        isSwitching = false
    }
}
