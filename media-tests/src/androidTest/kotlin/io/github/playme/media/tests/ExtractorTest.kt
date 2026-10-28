package io.github.playme.media.tests

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.playme.media.decoder.DecoderMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * FFmpeg must read containers as Media3 does: the same video frames and the same length of
 * audio from each file, through the device's own decoders.
 */
@RunWith(AndroidJUnit4::class)
class ExtractorTest {
    private val player = TestPlayer(InstrumentationRegistry.getInstrumentation().targetContext)

    @Test
    fun mp4H264WithBFrames() = readsLikeMedia3("bbb_800x640_768kbps_30fps_avc_pyramid_3b.mp4")

    @Test
    fun mp4Hevc() = readsLikeMedia3("h265_bframes.mp4", DecoderMode.SYSTEM_SOFTWARE) // the emulator's HEVC "hardware" stalls

    @Test
    fun mp4WithAac() = readsLikeMedia3("sample.mp4")

    @Test
    fun mp4Opus() {
        // Its edit list plays 1003 ms (from media time 21912 of 48 kHz): FFmpeg follows it, while
        // Media3 gives 0.7 s, so compare with the edit list rather than with Media3.
        val result = player.play(TestMedia.file("sample_opus.mp4"), DecoderMode.AUTO, ffmpegExtractor = true)
        assertNull(result.error)
        val expected = 1.003 * 48_000 * 2 * 2
        assertTrue("PCM ${result.pcmBytes}, edit list ${expected.toLong()}", abs(result.pcmBytes / expected - 1) < 0.03)
    }

    @Test
    fun mp4Alac() = readsLikeMedia3("sample_alac.mp4")

    @Test
    fun mp4EAc3() = readsLikeMedia3("sample_eac3joc.mp4")

    @Test
    fun mkvH264Vorbis() = readsLikeMedia3("sample_with_vorbis_audio.mkv")

    @Test
    fun webmVp9() = readsLikeMedia3("bbb_642x642_768kbps_30fps_vp9.webm")

    @Test
    fun mkvDts() = readsLikeMedia3("sample_with_dts.mkv")

    @Test
    fun mpegTs() = readsLikeMedia3("sample_h262_mpeg_audio.ts")

    @Test
    fun threeGpMpeg4() = readsLikeMedia3("bbb_176x144_192kbps_15fps_mpeg4.mp4")

    @Test
    fun amr() = readsLikeMedia3("bbb_mono_8kHz_12.2kbps_amrnb.3gp")

    @Test
    fun flac() = readsLikeMedia3("bear_with_picture.flac")

    @Test
    fun avi() = readsLikeMedia3("sample.avi")

    @Test
    fun flv() = readsLikeMedia3("sample.flv")

    @Test
    fun seeksInsideTheFile() {
        val file = TestMedia.file("bbb_800x640_768kbps_30fps_avc_pyramid_3b.mp4")
        val whole = player.play(file, DecoderMode.AUTO, ffmpegExtractor = true)
        val fromMiddle = player.play(file, DecoderMode.AUTO, seekToMs = 2_000, ffmpegExtractor = true)

        assertNull(fromMiddle.error)
        // A 4-second file: from 2 s on, about half its frames.
        assertTrue("${fromMiddle.videoFrames} of ${whole.videoFrames}", fromMiddle.videoFrames in whole.videoFrames / 3 until whole.videoFrames * 2 / 3)
    }

    @Test
    fun ffmpegDecodersTakeWhatFfmpegRead() {
        val result = player.play(TestMedia.file("sample_with_dts.mkv"), DecoderMode.FFMPEG, ffmpegExtractor = true)

        assertNull(result.error)
        assertEquals("playmedia-dca", result.audioDecoder)
    }

    private fun readsLikeMedia3(name: String, mode: DecoderMode = DecoderMode.AUTO) {
        val file = TestMedia.file(name)
        val media3 = player.play(file, mode)
        val ffmpeg = player.play(file, mode, ffmpegExtractor = true)

        // Left alone, files Media3 reads stay with Media3.
        val media3Read = listOfNotNull(media3.videoFormatId, media3.audioFormatId)
        assertTrue("Media3 formats $media3Read", media3Read.none { it.startsWith("playmedia/") })
        assertNull(ffmpeg.error)
        val read = listOfNotNull(ffmpeg.videoFormatId, ffmpeg.audioFormatId)
        assertTrue("formats $read", read.isNotEmpty() && read.all { it.startsWith("playmedia/") })
        if (media3.error != null) return
        assertTrue("video frames ${ffmpeg.videoFrames}, Media3 ${media3.videoFrames}", abs(ffmpeg.videoFrames - media3.videoFrames) <= 1)
        if (media3.pcmBytes > 0) {
            val ratio = ffmpeg.pcmBytes.toDouble() / media3.pcmBytes
            assertTrue("PCM ${ffmpeg.pcmBytes}, Media3 ${media3.pcmBytes}", abs(ratio - 1) < 0.03)
        }
    }
}
