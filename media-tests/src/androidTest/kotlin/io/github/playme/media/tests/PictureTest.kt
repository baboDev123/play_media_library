package io.github.playme.media.tests

import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.view.PixelCopy
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.playme.media.decoder.DecoderManager
import io.github.playme.media.decoder.DecoderMode
import io.github.playme.media.decoder.PlayMeRenderersFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** What FFmpeg puts on screen must look like what the device's decoder puts there. */
@RunWith(AndroidJUnit4::class)
class PictureTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun ffmpegFrameLooksLikeTheDeviceDecoderFrame() = compareAt("sample.mp4", 300)

    @Test
    fun vp9FrameLooksLikeTheDeviceDecoderFrame() = compareAt("bbb_642x642_768kbps_30fps_vp9.webm", 2_000)

    private fun compareAt(name: String, positionMs: Long) {
        val file = TestMedia.file(name)
        ActivityScenario.launch(SurfaceActivity::class.java).use { scenario ->
            lateinit var activity: SurfaceActivity
            scenario.onActivity { activity = it }
            instrumentation.waitForIdleSync()
            Thread.sleep(500)

            val firstFrames = LinkedBlockingQueue<String>()
            val manager = DecoderManager(DecoderMode.FFMPEG, DecoderMode.AUTO)
            lateinit var player: ExoPlayer
            instrumentation.runOnMainSync {
                player = ExoPlayer.Builder(activity, PlayMeRenderersFactory(activity, decoderManager = manager)).build()
                manager.attach(player)
                var decoder = ""
                player.addAnalyticsListener(object : AnalyticsListener {
                    override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                        decoder = decoderName
                    }

                    override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
                        firstFrames += decoder
                    }
                })
                player.setVideoSurfaceView(activity.surfaceView)
                player.setMediaItem(MediaItem.fromUri(file.toURI().toString()), positionMs)
                player.prepare()
            }
            try {
                val first = firstFrames.poll(20, TimeUnit.SECONDS)
                if (first == null) {
                    var state = ""
                    instrumentation.runOnMainSync {
                        val counters = player.videoDecoderCounters
                        state = "state ${player.playbackState} at ${player.currentPosition} error ${player.playerError} " +
                            "decoded ${counters?.renderedOutputBufferCount}/${counters?.skippedOutputBufferCount}/${counters?.droppedBufferCount} " +
                            "inputs ${counters?.queuedInputBufferCount} surface ${activity.surfaceView.holder.surface.isValid}"
                    }
                    throw AssertionError(state)
                }
                assertEquals("playmedia-", first.take(10))
                val ffmpeg = capture(activity)
                instrumentation.runOnMainSync { manager.selectVideoDecoder(DecoderMode.SYSTEM_SOFTWARE) }
                val deviceDecoder = firstFrames.poll(20, TimeUnit.SECONDS).orEmpty()
                assertTrue(deviceDecoder, deviceDecoder.startsWith("c2.") || deviceDecoder.startsWith("OMX."))
                var ready = false
                repeat(50) {
                    if (!ready) {
                        instrumentation.runOnMainSync { ready = player.playbackState == Player.STATE_READY && player.playerError == null }
                        if (!ready) Thread.sleep(100)
                    }
                }
                assertTrue("not ready after switching to $deviceDecoder", ready)
                val device = capture(activity)

                val difference = meanDifference(ffmpeg, device)
                assertTrue("FFmpeg's picture differs from $deviceDecoder's by $difference", difference < 12.0)
            } finally {
                instrumentation.runOnMainSync { player.release() }
            }
        }
    }

    private fun capture(activity: SurfaceActivity): Bitmap {
        Thread.sleep(700)
        val view = activity.surfaceView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val thread = HandlerThread("PixelCopy").apply { start() }
        val done = CountDownLatch(1)
        var result = -1
        PixelCopy.request(view, bitmap, { result = it; done.countDown() }, Handler(thread.looper))
        done.await(5, TimeUnit.SECONDS)
        thread.quitSafely()
        assertEquals(PixelCopy.SUCCESS, result)
        return bitmap
    }

    /** Average difference of the red, green and blue channels over a coarse grid, 0-255. */
    private fun meanDifference(a: Bitmap, b: Bitmap): Double {
        val small = 32
        val left = Bitmap.createScaledBitmap(a, small, small, true)
        val right = Bitmap.createScaledBitmap(b, small, small, true)
        var total = 0.0
        for (y in 0 until small) {
            for (x in 0 until small) {
                val p = left.getPixel(x, y)
                val q = right.getPixel(x, y)
                total += abs((p shr 16 and 0xFF) - (q shr 16 and 0xFF)) + abs((p shr 8 and 0xFF) - (q shr 8 and 0xFF)) + abs((p and 0xFF) - (q and 0xFF))
            }
        }
        return total / (small * small * 3)
    }
}
