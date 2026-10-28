package io.github.playme.media.tests

import android.os.Debug
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.playme.media.decoder.DecoderMode
import io.github.playme.media.info.MediaInfoExtractor
import io.github.playme.media.info.ThumbnailExtractor
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opening, seeking and closing again and again must not grow native memory. */
@RunWith(AndroidJUnit4::class)
class MemoryTest {
    private val player = TestPlayer(InstrumentationRegistry.getInstrumentation().targetContext)

    @Test
    fun repeatedPlaybackDoesNotLeak() {
        val video = TestMedia.file("sample.mp4")
        val audio = TestMedia.file("sample_with_dts.mkv")
        val cycle = {
            assertNull(player.play(video, DecoderMode.FFMPEG, seekToMs = 300).error)
            assertNull(player.play(audio, DecoderMode.FFMPEG).error)
        }
        repeat(2) { cycle() }
        val before = settledNativeHeap()
        repeat(10) { cycle() }
        val growth = settledNativeHeap() - before

        assertTrue("native heap grew ${growth / 1024} KB", growth < 4 * 1024 * 1024)
    }

    @Test
    fun repeatedProbingDoesNotLeak() {
        val path = TestMedia.file("sample_with_ass_subtitles.mkv").path
        val probe = {
            MediaInfoExtractor.extractSync(path)
            ThumbnailExtractor.getFrameAtSync(path, timeUs = 500_000, targetWidth = 160)?.recycle()
        }
        repeat(10) { probe() }
        val before = settledNativeHeap()
        repeat(200) { probe() }
        val growth = settledNativeHeap() - before

        assertTrue("native heap grew ${growth / 1024} KB", growth < 2 * 1024 * 1024)
    }

    private fun settledNativeHeap(): Long {
        repeat(3) {
            Runtime.getRuntime().gc()
            System.runFinalization()
            Thread.sleep(200)
        }
        return Debug.getNativeHeapAllocatedSize()
    }
}
