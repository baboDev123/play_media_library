package io.github.playme.media.tests

import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.playme.media.decoder.DecoderManager
import io.github.playme.media.decoder.DecoderMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Changing decoders while playing, as the app's decoder menu does. */
@RunWith(AndroidJUnit4::class)
class DecoderSwitchTest {
    private val player = TestPlayer(InstrumentationRegistry.getInstrumentation().targetContext)

    @Test
    fun videoSwitchesBackAndForthWhilePlaying() {
        val manager = DecoderManager()
        val activeModes = mutableListOf<DecoderMode?>()
        val result = player.play(
            TestMedia.file("bbb_800x640_768kbps_30fps_avc_pyramid_3b.mp4"),
            DecoderMode.AUTO,
            decoderManager = manager,
            actions = listOf(
                1_000L to { _ -> activeModes += manager.activeVideoMode; manager.selectVideoDecoder(DecoderMode.FFMPEG) },
                2_500L to { _ -> activeModes += manager.activeVideoMode; manager.selectVideoDecoder(DecoderMode.SYSTEM_SOFTWARE) },
                4_000L to { player -> activeModes += manager.activeVideoMode; check(player.playbackState == Player.STATE_READY) },
            ),
        )

        assertNull(result.error)
        assertEquals(listOf(DecoderMode.AUTO, DecoderMode.FFMPEG, DecoderMode.SYSTEM_SOFTWARE), activeModes)
        assertEquals(3, result.videoDecoders.size)
        assertEquals("playmedia-h264", result.videoDecoders[1])
        assertTrue(result.videoDecoders[2], result.videoDecoders[2].startsWith("c2.android"))
    }

    @Test
    fun audioSwitchesWhilePlaying() {
        val manager = DecoderManager()
        val result = player.play(
            TestMedia.file("sample_with_vorbis_audio.mkv"),
            DecoderMode.AUTO,
            decoderManager = manager,
            actions = listOf(500L to { _ -> manager.selectAudioDecoder(DecoderMode.FFMPEG) }),
        )

        assertNull(result.error)
        assertEquals("playmedia-vorbis", result.audioDecoders.last())
        assertTrue(result.audioDecoders.first(), !result.audioDecoders.first().startsWith("playmedia"))
    }

    @Test
    fun hardwareModeUsesOnlyHardwareDecoders() {
        val result = player.play(TestMedia.file("sample.mp4"), DecoderMode.HARDWARE)

        assertNull(result.error)
        val decoder = result.videoDecoders.single()
        assertTrue(decoder, !decoder.startsWith("c2.android") && !decoder.startsWith("OMX.google") && !decoder.startsWith("playmedia"))
    }
}
