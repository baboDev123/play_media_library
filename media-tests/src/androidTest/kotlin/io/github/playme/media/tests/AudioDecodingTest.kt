package io.github.playme.media.tests

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.playme.media.decoder.DecoderMode
import io.github.playme.media.info.MediaInfoExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class AudioDecodingTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val player = TestPlayer(context)

    @Test
    fun ac3() = decodesAll("sample_ac3.mp4", "ac3")

    @Test
    fun eac3() = decodesAll("sample_eac3joc.mp4", "eac3")

    @Test
    fun dts() = decodesAll("sample_with_dts.mkv", "dca")

    @Test
    fun dtsHdMasterAudio() = decodesAll("sample_dts_hd_ma.mp4", "dca")

    @Test
    fun trueHd() = decodesAll("sample_dthd.mp4", "truehd")

    @Test
    fun flac() = decodesAll("bear_with_picture.flac", "flac")

    @Test
    fun vorbis() = decodesAll("sample_with_vorbis_audio.mkv", "vorbis")

    @Test
    fun opus() = decodesAll("sample_opus.mp4", "opus")

    @Test
    fun alac() = decodesAll("sample_alac.mp4", "alac")

    @Test
    fun aac() = decodesAll("sample.mp4", "aac")

    @Test
    fun mp2() = decodesAll("sample_mp2_audio.mp4", "mp2")

    @Test
    fun amrNarrowband() = decodesAll("bbb_mono_8kHz_12.2kbps_amrnb.3gp", "amrnb")

    @Test
    fun amrWideband() = decodesAll("bbb_mono_16kHz_23.05kbps_amrwb.3gp", "amrwb")

    @Test
    fun autoModeFallsBackToFfmpegWhereTheDeviceCant() {
        // Emulators have no DTS decoder.
        val result = player.play(TestMedia.file("sample_with_dts.mkv"), DecoderMode.AUTO)

        assertNull(result.error)
        assertEquals("playmedia-dca", result.audioDecoder)
    }

    /**
     * All of the audio comes out: as much PCM as the device's own decoder gives, or where the
     * device has none, as the stream's duration calls for.
     */
    private fun decodesAll(name: String, codec: String) {
        val file = TestMedia.file(name)
        val result = player.play(file, DecoderMode.FFMPEG)
        assertNull(result.error)
        assertEquals("playmedia-$codec", result.audioDecoder)

        val device = player.play(file, DecoderMode.AUTO)
        val expected = if (device.error == null && device.audioDecoder?.startsWith("playmedia") == false) {
            device.pcmBytes.toDouble()
        } else {
            val info = MediaInfoExtractor.extractSync(file.path)
            val audio = info.primaryAudio!!
            val channels = if (audio.channels in 1..8) audio.channels else 2
            info.durationMs / 1000.0 * audio.sampleRate * channels * 2
        }
        assertTrue(
            "PCM ${result.pcmBytes} bytes, expected about ${expected.toLong()} (device: ${device.audioDecoder})",
            abs(result.pcmBytes / expected - 1) < 0.05,
        )
    }
}
