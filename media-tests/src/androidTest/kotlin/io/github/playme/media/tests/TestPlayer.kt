package io.github.playme.media.tests

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import io.github.playme.media.decoder.DecoderManager
import io.github.playme.media.decoder.DecoderMode
import io.github.playme.media.decoder.PlayMeRenderersFactory
import io.github.playme.media.decoder.SubtitleTiming
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** What one play-through of a file did. */
data class PlayResult(
    val videoDecoder: String?,
    val audioDecoder: String?,
    /** Every decoder used, in order, when they changed during playback. */
    val videoDecoders: List<String>,
    val audioDecoders: List<String>,
    /** Video frames that came out of the decoder: rendered, dropped or skipped. */
    val videoFrames: Int,
    val renderedFrames: Int,
    val pcmBytes: Long,
    val error: PlaybackException?,
)

/** Plays test media to the end on a player thread of its own, as an app would. */
class TestPlayer(private val context: Context) {

    fun play(
        file: File,
        mode: DecoderMode,
        surface: Surface? = null,
        seekToMs: Long? = null,
        subtitleTiming: SubtitleTiming = SubtitleTiming(),
        decoderManager: DecoderManager = DecoderManager(mode, mode),
        /** Run on the player thread this many milliseconds after playback starts. */
        actions: List<Pair<Long, (ExoPlayer) -> Unit>> = emptyList(),
        timeoutSeconds: Long = 90,
    ): PlayResult {
        val thread = HandlerThread("TestPlayer").apply { start() }
        val handler = Handler(thread.looper)
        val pcmBytes = AtomicLong()
        val done = CountDownLatch(1)
        val videoDecoders = java.util.Collections.synchronizedList(mutableListOf<String>())
        val audioDecoders = java.util.Collections.synchronizedList(mutableListOf<String>())
        var videoCounters: DecoderCounters? = null
        var error: PlaybackException? = null
        lateinit var player: ExoPlayer

        handler.post {
            val renderers = object : PlayMeRenderersFactory(context, mode, subtitleTiming, decoderManager) {
                override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioOutputPlaybackParams: Boolean): AudioSink? =
                    object : ForwardingAudioSink(super.buildAudioSink(context, enableFloatOutput, enableAudioOutputPlaybackParams)!!) {
                        override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
                            val before = buffer.remaining()
                            return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
                                .also { pcmBytes.addAndGet((before - buffer.remaining()).toLong()) }
                        }
                    }
            }
            player = ExoPlayer.Builder(context, renderers).setLooper(thread.looper).build()
            decoderManager.attach(player)
            player.addAnalyticsListener(object : AnalyticsListener {
                override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                    videoDecoders += decoderName
                }

                override fun onAudioTrackInitialized(eventTime: AnalyticsListener.EventTime, audioTrackConfig: AudioSink.AudioTrackConfig) {
                    android.util.Log.i("TestPlayer", "track ${audioTrackConfig.sampleRate} Hz mask ${audioTrackConfig.channelConfig} enc ${audioTrackConfig.encoding}")
                }

                override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                    audioDecoders += decoderName
                }

            })
            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) {
                        videoCounters = player.videoDecoderCounters
                        done.countDown()
                    }
                }

                override fun onPlayerError(playbackError: PlaybackException) {
                    error = playbackError
                    done.countDown()
                }
            })
            surface?.let(player::setVideoSurface)
            player.setMediaItem(MediaItem.fromUri(file.toURI().toString()))
            player.prepare()
            seekToMs?.let(player::seekTo)
            player.play()
            for ((delayMs, action) in actions) handler.postDelayed({ action(player) }, delayMs)
        }

        val finished = done.await(timeoutSeconds, TimeUnit.SECONDS)
        val released = CountDownLatch(1)
        handler.post {
            player.release()
            released.countDown()
        }
        released.await(10, TimeUnit.SECONDS)
        thread.quitSafely()
        check(finished) { "${file.name} didn't finish playing in $timeoutSeconds s" }

        val counters = videoCounters
        return PlayResult(
            videoDecoder = videoDecoders.lastOrNull(),
            audioDecoder = audioDecoders.lastOrNull(),
            videoDecoders = videoDecoders.toList(),
            audioDecoders = audioDecoders.toList(),
            videoFrames = counters?.let { it.renderedOutputBufferCount + it.droppedBufferCount + it.skippedOutputBufferCount } ?: 0,
            renderedFrames = counters?.renderedOutputBufferCount ?: 0,
            pcmBytes = pcmBytes.get(),
            error = error,
        )
    }
}
