package io.github.playme.media.decoder

import android.os.Handler
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.decoder.CryptoConfig
import androidx.media3.decoder.Decoder
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.decoder.VideoDecoderOutputBuffer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.video.DecoderVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import io.github.playme.media.core.PlayMediaNative

/**
 * Plays video decoded by FFmpeg. Media3 decides when each frame is due, and drops late ones;
 * the frame is then drawn straight onto the player's Surface.
 *
 * A video too heavy to decode in time (4K at 60 fps on a phone's CPU, say) would fall further
 * and further behind, showing a frame now and then. Once frames come out late the decoder
 * skips the frames no other frame needs, which costs the least to the picture, until it has
 * caught up.
 *
 * @param threads how many threads decode each video, by default one per core (at most 8).
 */
class PlayMeVideoRenderer @JvmOverloads constructor(
    allowedJoiningTimeMs: Long = 5_000,
    eventHandler: Handler? = null,
    eventListener: VideoRendererEventListener? = null,
    maxDroppedFramesToNotify: Int = 50,
    private val threads: Int = defaultThreads(),
) : DecoderVideoRenderer(allowedJoiningTimeMs, eventHandler, eventListener, maxDroppedFramesToNotify) {

    /** Native window state for the Surface being drawn on; 0 until the first frame. */
    private var window = 0L

    private var decoder: FfmpegVideoDecoder? = null

    override fun getName(): String = "PlayMeVideoRenderer"

    override fun supportsFormat(format: Format): Int {
        val mimeType = format.sampleMimeType
        return when {
            mimeType == null || !MimeTypes.isVideo(mimeType) -> RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE)
            !PlayMediaNative.canDecodeVideo(mimeType) -> RendererCapabilities.create(C.FORMAT_UNSUPPORTED_SUBTYPE)
            format.cryptoType != C.CRYPTO_TYPE_NONE -> RendererCapabilities.create(C.FORMAT_UNSUPPORTED_DRM)
            else -> RendererCapabilities.create(
                C.FORMAT_HANDLED,
                RendererCapabilities.ADAPTIVE_SEAMLESS,
                RendererCapabilities.TUNNELING_NOT_SUPPORTED,
            )
        }
    }

    override fun createDecoder(
        format: Format,
        cryptoConfig: CryptoConfig?,
    ): Decoder<DecoderInputBuffer, VideoDecoderOutputBuffer, PlayMeDecoderException> =
        FfmpegVideoDecoder(format, threads).also { decoder = it }

    override fun shouldDropOutputBuffer(earlyUs: Long, elapsedSinceLastRenderUs: Long): Boolean {
        decoder?.let { it.skipsNonReferenceFrames = skipsNonReferenceFrames(it.skipsNonReferenceFrames, earlyUs) }
        return super.shouldDropOutputBuffer(earlyUs, elapsedSinceLastRenderUs)
    }

    override fun onPositionReset(positionUs: Long, joining: Boolean, sampleStreamIsResetToKeyFrame: Boolean) {
        super.onPositionReset(positionUs, joining, sampleStreamIsResetToKeyFrame)
        // After a seek the decoder starts afresh, on time.
        decoder?.skipsNonReferenceFrames = false
    }

    override fun renderOutputBufferToSurface(outputBuffer: VideoDecoderOutputBuffer, surface: Surface) {
        val frame = (outputBuffer as FrameBuffer).frame
        if (frame != 0L) {
            if (window == 0L) window = FrameWindowNative.create()
            FrameWindowNative.render(window, surface, frame)
        }
        outputBuffer.release()
    }

    // Frames are always drawn onto a Surface here; no YUV buffers are produced for GL views.
    override fun setDecoderOutputMode(outputMode: Int) = Unit

    override fun onDisabled() {
        super.onDisabled()
        decoder = null
        if (window != 0L) {
            FrameWindowNative.release(window)
            window = 0L
        }
    }

    companion object {
        /** One decoding thread per core, at most 8: more stops helping and costs memory. */
        @JvmStatic
        fun defaultThreads(): Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
    }
}

/**
 * How late frames come out before the decoder starts skipping: as late as Media3 drops a frame
 * rather than show it, as the decoder can't keep up from there.
 */
private const val BEHIND_US = 30_000L

/** How close to on time frames come out before it stops. */
private const val CAUGHT_UP_US = 5_000L

/**
 * Whether the decoder should skip the frames no other frame needs, given whether it [skipping]
 * already and how early (negative: late) the frame now due came out.
 */
internal fun skipsNonReferenceFrames(skipping: Boolean, earlyUs: Long): Boolean = when {
    earlyUs < -BEHIND_US -> true
    earlyUs > -CAUGHT_UP_US -> false
    else -> skipping
}
