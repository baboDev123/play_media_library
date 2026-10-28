package io.github.playme.media.decoder

import androidx.media3.common.Format
import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererConfiguration
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SampleStream

/**
 * Shifts and stretches subtitle timing, for subtitles out of sync with the video. Changes apply
 * on the next rendered frame. One instance can outlive players: give the same one to every
 * [PlayMeRenderersFactory] and the setting carries over when a player is rebuilt.
 */
class SubtitleTiming {
    /** Positive shows subtitles later, negative earlier. */
    @Volatile
    var delayMs: Long = 0

    /**
     * How fast subtitles run against the video: above 1 they drift ahead, below 1 behind.
     * Corrects subtitles made for a different frame rate (e.g. 25 vs 23.976 fps).
     */
    @Volatile
    var speed: Float = 1f
        set(value) {
            field = value.coerceIn(0.1f, 10f)
        }
}

/** Wraps Media3's text renderer, feeding it the subtitle position [timing] calls for. */
internal class TimedTextRenderer(
    textRenderer: Renderer,
    private val timing: SubtitleTiming,
) : ForwardingRenderer(textRenderer) {

    /** Added by Media3 to positions of the current stream; scaling must leave it out. */
    private var streamOffsetUs = 0L

    override fun enable(
        configuration: RendererConfiguration,
        formats: Array<out Format>,
        stream: SampleStream,
        positionUs: Long,
        joining: Boolean,
        mayRenderStartOfStream: Boolean,
        startPositionUs: Long,
        offsetUs: Long,
        mediaPeriodId: MediaSource.MediaPeriodId,
    ) {
        streamOffsetUs = offsetUs
        super.enable(
            configuration, formats, stream, subtitlePosition(positionUs), joining, mayRenderStartOfStream,
            startPositionUs, offsetUs, mediaPeriodId,
        )
    }

    override fun replaceStream(
        formats: Array<out Format>,
        stream: SampleStream,
        startPositionUs: Long,
        offsetUs: Long,
        mediaPeriodId: MediaSource.MediaPeriodId,
    ) {
        streamOffsetUs = offsetUs
        super.replaceStream(formats, stream, startPositionUs, offsetUs, mediaPeriodId)
    }

    override fun resetPosition(positionUs: Long, sampleStreamIsResetToKeyFrame: Boolean) {
        super.resetPosition(subtitlePosition(positionUs), sampleStreamIsResetToKeyFrame)
    }

    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        super.render(subtitlePosition(positionUs), elapsedRealtimeUs)
    }

    override fun getDurationToProgressUs(positionUs: Long, elapsedRealtimeUs: Long): Long =
        super.getDurationToProgressUs(subtitlePosition(positionUs), elapsedRealtimeUs)

    private fun subtitlePosition(positionUs: Long): Long {
        val speed = timing.speed
        val mediaTimeUs = positionUs - streamOffsetUs
        val scaledUs = if (speed == 1f) mediaTimeUs else (mediaTimeUs * speed.toDouble()).toLong()
        return streamOffsetUs + scaledUs - timing.delayMs * 1_000
    }
}
