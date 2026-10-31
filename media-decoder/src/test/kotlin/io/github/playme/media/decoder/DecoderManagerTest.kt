package io.github.playme.media.decoder

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DecoderManagerTest {
    private val video8k = Format.Builder()
        .setSampleMimeType(MimeTypes.VIDEO_H265)
        .setWidth(7680)
        .setHeight(4320)
        .setFrameRate(60f)
        .build()

    @Test
    fun autoLeavesVideoTheHardwareDecodesToTheDevice() {
        val manager = DecoderManager().apply { hardwareDecodes = { true } }

        assertFalse(manager.allows(DecoderKind.FFMPEG, C.TRACK_TYPE_VIDEO, video8k))
        assertTrue(manager.allows(DecoderKind.DEVICE, C.TRACK_TYPE_VIDEO, video8k))
    }

    @Test
    fun autoGivesFfmpegVideoTheHardwareCantDecode() {
        val manager = DecoderManager().apply { hardwareDecodes = { false } }

        assertTrue(manager.allows(DecoderKind.FFMPEG, C.TRACK_TYPE_VIDEO, video8k))
    }

    @Test
    fun ffmpegModeKeepsVideoOnFfmpeg() {
        val manager = DecoderManager(videoMode = DecoderMode.FFMPEG).apply { hardwareDecodes = { true } }

        assertTrue(manager.allows(DecoderKind.FFMPEG, C.TRACK_TYPE_VIDEO, video8k))
    }

    @Test
    fun hardwareModeNeverUsesFfmpeg() {
        val manager = DecoderManager(videoMode = DecoderMode.HARDWARE)

        assertFalse(manager.allows(DecoderKind.FFMPEG, C.TRACK_TYPE_VIDEO, video8k))
    }
}
