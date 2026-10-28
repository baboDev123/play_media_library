package io.github.playme.media.decoder

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer

/**
 * Controller providing runtime switching between Hardware and Software decoders
 * while maintaining current playback state (position, playback speed, play/pause).
 */
@OptIn(UnstableApi::class)
class DecoderController(
    private val playerProvider: () -> ExoPlayer?,
    private val playerRebuilder: (DecoderMode) -> Unit
) {
    var currentMode: DecoderMode = DecoderMode.AUTO
        private set

    /**
     * Seamlessly transitions the active player to the desired DecoderMode.
     */
    fun switchDecoderMode(newMode: DecoderMode) {
        if (currentMode == newMode) return

        val player = playerProvider()
        val currentPosition = player?.currentPosition ?: 0L
        val wasPlaying = player?.isPlaying ?: false
        val playbackParameters = player?.playbackParameters

        currentMode = newMode
        playerRebuilder(newMode)

        val newPlayer = playerProvider()
        newPlayer?.seekTo(currentPosition)
        if (playbackParameters != null) {
            newPlayer?.playbackParameters = playbackParameters
        }
        if (wasPlaying) {
            newPlayer?.play()
        }
    }
}

