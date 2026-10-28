package io.github.playme.media.tests

import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.playme.media.info.HdrType
import io.github.playme.media.info.MediaInfoExtractor
import io.github.playme.media.info.PlayMediaException
import io.github.playme.media.info.ThumbnailExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaInfoTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun describesStreams() {
        val info = MediaInfoExtractor.extractSync(TestMedia.file("sample.mp4").path)

        assertEquals("h264", info.primaryVideo?.codecName)
        assertEquals("aac", info.primaryAudio?.codecName)
        assertTrue(info.durationMs > 0)
        assertTrue(info.primaryVideo!!.width > 0 && info.primaryVideo!!.frameRate > 0)
    }

    @Test
    fun everyInputReadsTheSame() {
        val name = "sample_with_ass_subtitles.mkv"
        val file = TestMedia.file(name)
        val fromPath = MediaInfoExtractor.extractSync(file.path)
        val fromDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { MediaInfoExtractor.extractSync(it) }
        val fromUri = MediaInfoExtractor.extractSync(context, Uri.fromFile(file))
        val fromSource = MediaInfoExtractor.extractSync(BytesDataSource(TestMedia.bytes(name)))
        val fromStream = file.inputStream().use { MediaInfoExtractor.extractSync(it, file.length()) }

        for (info in listOf(fromDescriptor, fromUri, fromSource, fromStream)) {
            assertEquals(fromPath.durationMs, info.durationMs)
            assertEquals(fromPath.videoStreams, info.videoStreams)
            assertEquals(fromPath.subtitleStreams, info.subtitleStreams)
        }
        assertEquals("ass", fromPath.subtitleStreams.single().codecName)
    }

    @Test
    fun readsMp4FromARandomAccessSource() {
        // MP4s often keep their index at the end: reading them needs seeking.
        val info = MediaInfoExtractor.extractSync(BytesDataSource(TestMedia.bytes("bbb_800x640_768kbps_30fps_avc_pyramid_3b.mp4")))

        assertEquals("h264", info.primaryVideo?.codecName)
    }

    @Test
    fun chapters() {
        val info = MediaInfoExtractor.extractSync(TestMedia.file("sample_with_chapters.mkv").path)

        assertTrue(info.chapters.isNotEmpty())
        assertTrue(info.chapters.zipWithNext().all { (a, b) -> a.startTimeMs <= b.startTimeMs })
    }

    @Test
    fun hdrAndBitDepth() {
        val video = MediaInfoExtractor.extractSync(TestMedia.file("hlg10-color-test.mp4").path).primaryVideo!!

        assertEquals(HdrType.HLG, video.hdrType)
        assertEquals(10, video.bitDepth)
    }

    @Test
    fun coverArt() {
        val info = MediaInfoExtractor.extractSync(TestMedia.file("bear_with_picture.flac").path)

        assertTrue(info.hasCoverArt)
        assertTrue(info.videoStreams.isEmpty())
    }

    @Test
    fun unreadableInputFails() {
        try {
            MediaInfoExtractor.extractSync(BytesDataSource(ByteArray(4096) { 7 }))
            fail("Garbage was read as media")
        } catch (_: PlayMediaException) {
        }
    }

    @Test
    fun givesUpAfterTheTimeout() {
        val slow = BytesDataSource(TestMedia.bytes("bbb_800x640_768kbps_30fps_avc_pyramid_3b.mp4"), delayPerReadMs = 250)
        val started = System.nanoTime()
        try {
            MediaInfoExtractor.extractSync(slow, timeoutMs = 100)
            fail("A slow source was read past its timeout")
        } catch (_: PlayMediaException) {
        }
        assertTrue((System.nanoTime() - started) / 1_000_000 < 1_500)
    }

    @Test
    fun thumbnailFitsTheBounds() {
        val frame = ThumbnailExtractor.getFrameAtSync(TestMedia.file("sample.mp4").path, timeUs = 500_000, targetWidth = 320, targetHeight = 180)

        assertNotNull(frame)
        assertTrue("${frame!!.width}x${frame.height}", frame.width <= 320 && frame.height <= 180 && maxOf(frame.width, frame.height) >= 179)
        assertTrue(hasDetail(frame))
    }

    @Test
    fun exactThumbnail() {
        val source = BytesDataSource(TestMedia.bytes("bbb_800x640_768kbps_30fps_avc_pyramid_3b.mp4"))
        val frame = ThumbnailExtractor.getFrameAtSync(source, timeUs = 3_200_000, targetWidth = 200, exact = true)

        assertNotNull(frame)
        assertTrue(hasDetail(frame!!))
    }

    @Test
    fun portraitThumbnailIsUpright() {
        val file = TestMedia.file("sample_portrait.mp4")
        val video = MediaInfoExtractor.extractSync(file.path).primaryVideo!!
        val frame = ThumbnailExtractor.getFrameAtSync(file.path)!!

        val turned = video.rotation % 180 != 0
        val displayedTall = if (turned) video.width > video.height else video.height > video.width
        assertEquals("rotation ${video.rotation}, ${video.width}x${video.height}", displayedTall, frame.height > frame.width)
    }

    @Test
    fun audioThumbnailIsTheCover() {
        assertNotNull(ThumbnailExtractor.getFrameAtSync(TestMedia.file("bear_with_picture.flac").path, targetWidth = 100))
        assertNull(ThumbnailExtractor.getFrameAtSync(TestMedia.file("sample_ac3.mp4").path))
    }

    private fun hasDetail(bitmap: Bitmap): Boolean {
        val pixels = IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }
        val greens = pixels.map { (it shr 8) and 0xFF }
        return greens.max() - greens.min() > 20
    }
}
