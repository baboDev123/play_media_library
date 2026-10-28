package io.github.playme.media.decoder

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.RendererConfiguration
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SampleStream

/**
 * A renderer that takes formats only while [manager] allows its [kind] of decoder, and tells
 * the player when that changes so the track moves to another renderer.
 */
internal class ModeGatedRenderer(
    renderer: Renderer,
    private val kind: DecoderKind,
    private val manager: DecoderManager,
) : ForwardingRenderer(renderer) {

    private val wrapped = renderer.capabilities

    @Volatile
    private var listener: RendererCapabilities.Listener? = null

    private val gatedCapabilities = object : RendererCapabilities by wrapped {
        override fun supportsFormat(format: Format): Int =
            if (manager.allows(kind, getTrackType(), format)) wrapped.supportsFormat(format) else RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE)

        override fun setListener(listener: RendererCapabilities.Listener) {
            this@ModeGatedRenderer.listener = listener
            wrapped.setListener(listener)
        }

        override fun clearListener() {
            listener = null
            wrapped.clearListener()
        }
    }

    init {
        manager.register(this)
    }

    override fun getCapabilities(): RendererCapabilities = gatedCapabilities

    override fun enable(
        configuration: RendererConfiguration,
        formats: Array<out Format>,
        stream: SampleStream,
        positionUs: Long,
        joining: Boolean,
        mayRenderStartOfStream: Boolean,
        startPositionUs: Long,
        offsetUs: Long,
        mediaPeriodId: MediaSource.MediaPeriodId,
    ) {
        super.enable(configuration, formats, stream, positionUs, joining, mayRenderStartOfStream, startPositionUs, offsetUs, mediaPeriodId)
        manager.onRendererEnabled(kind, getTrackType(), enabled = true)
    }

    override fun disable() {
        super.disable()
        manager.onRendererEnabled(kind, getTrackType(), enabled = false)
    }

    fun notifyCapabilitiesChanged() {
        listener?.onRendererCapabilitiesChanged(this)
    }
}
