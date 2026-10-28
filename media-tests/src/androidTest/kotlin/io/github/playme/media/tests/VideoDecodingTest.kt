package io.github.playme.media.tests

import android.graphics.ImageFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.playme.media.decoder.DecoderMode
import io.github.playme.media.info.MediaInfoExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class VideoDecodingTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val player = TestPlayer(context)

    @Test
    fun h264WithBFramePyramid() = decodesEveryFrame("bbb_800x640_768kbps_30fps_avc_pyramid_3b.mp4", "h264")

    @Test
    fun hevcWithBFrames() = decodesEveryFrame("h265_bframes.mp4", "hevc")

    @Test
    fun hevc10Bit() = decodesEveryFrame("hlg10-color-test.mp4", "hevc")

    @Test
    fun vp9() = decodesEveryFrame("bbb_642x642_768kbps_30fps_vp9.webm", "vp9")

    @Test
    fun vp8() = decodesEveryFrame("bbb_1920x1080_60fps_vp8.webm", "vp8")

    @Test
    fun av1() = decodesEveryFrame("sample_av1.mp4", "libdav1d")

    @Test
    fun mpeg4() = decodesEveryFrame("bbb_176x144_192kbps_15fps_mpeg4.mp4", "mpeg4")

    @Test
    fun h263() = decodesEveryFrame("bbb_176x144_128kbps_15fps_h263.3gp", "h263")

    @Test
    fun mpeg2() = decodesEveryFrame("sample_h262_mpeg_audio.ts", "mpeg2video")

    @Test
    fun drawsPicturesOntoTheSurface() {
        val file = TestMedia.file("sample.mp4")
        val video = MediaInfoExtractor.extractSync(file.path).primaryVideo!!
        val thread = HandlerThread("frames").apply { start() }
        val reader = ImageReader.newInstance(video.width, video.height, ImageFormat.YV12, 3)
        val pictures = AtomicInteger()
        val detailed = AtomicInteger()
        reader.setOnImageAvailableListener({ source ->
            source.acquireLatestImage()?.use { image ->
                pictures.incrementAndGet()
                val luma = image.planes[0].buffer
                val samples = IntArray(64) { luma.get((it * (luma.limit() - 1) / 63)).toInt() and 0xFF }
                if (samples.max() - samples.min() > 20) detailed.incrementAndGet()
            }
        }, Handler(thread.looper))

        val result = player.play(file, DecoderMode.FFMPEG, surface = reader.surface)
        reader.close()
        thread.quitSafely()

        assertNull(result.error)
        assertTrue("rendered ${result.renderedFrames}", result.renderedFrames > 0)
        assertTrue("pictures ${pictures.get()}", pictures.get() > 0)
        assertTrue("pictures with detail ${detailed.get()}", detailed.get() > 0)
    }

    @Test
    fun seeksAndKeepsPlaying() {
        val file = TestMedia.file("bbb_800x640_768kbps_30fps_avc_pyramid_3b.mp4")
        val whole = player.play(file, DecoderMode.FFMPEG)
        val fromMiddle = player.play(file, DecoderMode.FFMPEG, seekToMs = 5_000)

        assertNull(fromMiddle.error)
        assertTrue("${fromMiddle.videoFrames} of ${whole.videoFrames}", fromMiddle.videoFrames in 1 until whole.videoFrames)
    }

    @Test
    fun autoModeKeepsDeviceDecodersFirst() {
        val result = player.play(TestMedia.file("sample.mp4"), DecoderMode.AUTO)

        assertNull(result.error)
        assertTrue("${result.videoDecoder}", result.videoDecoder?.startsWith("playmedia") == false)
    }

    /**
     * FFmpeg must hand over as many frames as the device decoder does: none lost to threading
     * or B-frame reordering, including the last ones when the stream ends.
     */
    private fun decodesEveryFrame(name: String, codec: String) {
        val file = TestMedia.file(name)
        val ffmpeg = player.play(file, DecoderMode.FFMPEG)
        assertNull(ffmpeg.error)
        assertEquals("playmedia-$codec", ffmpeg.videoDecoder)
        assertTrue("frames ${ffmpeg.videoFrames}", ffmpeg.videoFrames > 0)

        val device = player.play(file, DecoderMode.SYSTEM_SOFTWARE)
        val deviceDecoded = device.error == null && device.videoDecoder != null && device.videoFrames > 0
        if (deviceDecoded) assertEquals("frames vs ${device.videoDecoder}", device.videoFrames, ffmpeg.videoFrames)
    }
}
