package io.github.playme.media.decoder

import android.os.Handler
import android.view.Surface
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.CryptoConfig
import androidx.media3.decoder.Decoder
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.decoder.VideoDecoderOutputBuffer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.video.DecoderVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener

@OptIn(UnstableApi::class)
class PlayMeVideoRenderer @JvmOverloads constructor(
    allowedJoiningTimeMs: Long = 5000,
    eventHandler: Handler? = null,
    eventListener: VideoRendererEventListener? = null,
    maxDroppedFramesToNotify: Int = 50
) : DecoderVideoRenderer(allowedJoiningTimeMs, eventHandler, eventListener, maxDroppedFramesToNotify) {

    private var activeDecoder: PlayMeVideoDecoder? = null
    private var outputSurface: Surface? = null

    override fun getName(): String = "PlayMeVideoRenderer"

    override fun supportsFormat(format: Format): Int {
        val mimeType = format.sampleMimeType ?: return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE)
        if (!MimeTypes.isVideo(mimeType)) {
            return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE)
        }

        return if (PlayMeNativeBridge.isCodecSupported(mimeType)) {
            RendererCapabilities.create(
                C.FORMAT_HANDLED,
                ADAPTIVE_SEAMLESS,
                TUNNELING_NOT_SUPPORTED
            )
        } else {
            RendererCapabilities.create(C.FORMAT_UNSUPPORTED_SUBTYPE)
        }
    }

    override fun createDecoder(format: Format, cryptoConfig: CryptoConfig?): Decoder<DecoderInputBuffer, VideoDecoderOutputBuffer, PlayMeDecoderException> {
        val decoder = PlayMeVideoDecoder(format)
        decoder.setOutputSurface(outputSurface)
        activeDecoder = decoder
        return decoder
    }

    override fun renderOutputBufferToSurface(outputBuffer: VideoDecoderOutputBuffer, surface: Surface) {
        // Direct surface rendering is executed directly in C++ via ANativeWindow during decode.
        // Release buffer when completed.
        outputBuffer.release()
    }

    override fun setDecoderOutputMode(outputMode: Int) {
        // Handled internally in native surface mode
    }

    override fun handleMessage(messageType: Int, message: Any?) {
        when (messageType) {
            MSG_SET_VIDEO_OUTPUT -> {
                val surface = message as? Surface
                this.outputSurface = surface
                activeDecoder?.setOutputSurface(surface)
            }
            else -> super.handleMessage(messageType, message)
        }
    }
}

