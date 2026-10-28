package io.github.playme.media.decoder

import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.text.TextRenderer

/**
 * Custom TextRenderer for Media3 ExoPlayer with precise Subtitle Timing adjustments.
 *
 * Supports:
 * - Subtitle Delay (ms): Shifts subtitles forward/backward in time.
 * - Subtitle Speed: Scales subtitle progression relative to playback (corrects gradual sync drift).
 */
@OptIn(UnstableApi::class)
class PlayMeTextRenderer(
    output: TextOutput,
    outputLooper: Looper?
) : TextRenderer(output, outputLooper) {

    /**
     * Subtitle delay in milliseconds.
     * Positive value: Subtitles appear later (delayed).
     * Negative value: Subtitles appear earlier (advanced).
     */
    @Volatile
    var subtitleDelayMilliseconds: Long = 0L

    /**
     * Subtitle speed multiplier.
     * Default is 1.0f.
     * > 1.0f: Subtitles advance faster than video.
     * < 1.0f: Subtitles advance slower than video.
     */
    @Volatile
    var subtitleSpeed: Float = 1.0f

    override fun getName(): String = "PlayMeTextRenderer"

    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        // Calculate adjusted presentation timestamp:
        // adjusted = (original * speed) - (delay_in_us)
        val speedAdjusted = (positionUs * subtitleSpeed.toDouble()).toLong()
        val adjustedPositionUs = speedAdjusted - (subtitleDelayMilliseconds * 1000L)

        super.render(adjustedPositionUs, elapsedRealtimeUs)
    }

    override fun handleMessage(messageType: Int, message: Any?) {
        when (messageType) {
            MSG_SET_SUBTITLE_DELAY -> {
                if (message is Long) {
                    subtitleDelayMilliseconds = message
                }
            }
            MSG_SET_SUBTITLE_SPEED -> {
                if (message is Float) {
                    subtitleSpeed = message.coerceIn(0.1f, 10.0f)
                }
            }
            else -> super.handleMessage(messageType, message)
        }
    }

    companion object {
        const val MSG_SET_SUBTITLE_DELAY = 10001
        const val MSG_SET_SUBTITLE_SPEED = 10002
    }
}

// ==============================================================================
// ExoPlayer Extension Properties for Subtitle Delay & Speed
// ==============================================================================

private val activeRenderersField by lazy {
    try {
        val exoPlayerImplClass = Class.forName("androidx.media3.exoplayer.ExoPlayerImpl")
        val field = exoPlayerImplClass.getDeclaredField("renderers")
        field.isAccessible = true
        field
    } catch (e: Exception) {
        null
    }
}

@OptIn(UnstableApi::class)
private fun ExoPlayer.findTextRenderer(): PlayMeTextRenderer? {
    val field = activeRenderersField ?: return null
    return try {
        val renderers = field.get(this) as? Array<*> ?: return null
        renderers.filterIsInstance<PlayMeTextRenderer>().firstOrNull()
    } catch (e: Exception) {
        null
    }
}

/**
 * Subtitle delay in milliseconds on the active ExoPlayer instance.
 * Updates immediately on the next render cycle.
 */
@get:OptIn(UnstableApi::class)
@set:OptIn(UnstableApi::class)
var ExoPlayer.subtitleDelayMilliseconds: Long
    get() = findTextRenderer()?.subtitleDelayMilliseconds ?: 0L
    set(value) {
        val renderer = findTextRenderer()
        if (renderer != null) {
            renderer.subtitleDelayMilliseconds = value
        } else {
            createMessage { messageType, payload ->
                if (payload is PlayMeTextRenderer) {
                    payload.subtitleDelayMilliseconds = value
                }
            }.send()
        }
    }

/**
 * Subtitle speed multiplier on the active ExoPlayer instance (Default 1.0f).
 */
@get:OptIn(UnstableApi::class)
@set:OptIn(UnstableApi::class)
var ExoPlayer.subtitleSpeed: Float
    get() = findTextRenderer()?.subtitleSpeed ?: 1.0f
    set(value) {
        val clamped = value.coerceIn(0.1f, 10.0f)
        val renderer = findTextRenderer()
        if (renderer != null) {
            renderer.subtitleSpeed = clamped
        } else {
            createMessage { messageType, payload ->
                if (payload is PlayMeTextRenderer) {
                    payload.subtitleSpeed = clamped
                }
            }.send()
        }
    }
