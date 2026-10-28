package io.github.playme.media.decoder

import androidx.media3.common.Format
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererConfiguration
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SampleStream
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Proxy

class TimedTextRendererTest {
    private val renderedAt = mutableListOf<Long>()
    private val textRenderer = proxy<Renderer> { name, arguments ->
        if (name == "render") renderedAt += arguments[0] as Long
    }
    private val timing = SubtitleTiming()
    private val renderer = TimedTextRenderer(textRenderer, timing).apply {
        enable(
            RendererConfiguration.DEFAULT, arrayOf<Format>(), proxy<SampleStream> { _, _ -> }, OFFSET_US, false, false,
            OFFSET_US, OFFSET_US, MediaSource.MediaPeriodId(Any()),
        )
    }

    @Test
    fun unchangedByDefault() {
        renderer.render(OFFSET_US + 10_000_000, 0)

        assertEquals(OFFSET_US + 10_000_000, renderedAt.single())
    }

    @Test
    fun delayShowsSubtitlesLater() {
        timing.delayMs = 500
        renderer.render(OFFSET_US + 10_000_000, 0)

        assertEquals(OFFSET_US + 9_500_000, renderedAt.single())
    }

    @Test
    fun speedScalesMediaTimeNotTheStreamOffset() {
        timing.speed = 1.1f
        renderer.render(OFFSET_US + 10_000_000, 0)

        assertEquals(OFFSET_US + 11_000_000, renderedAt.single(), 1_000.0)
    }

    @Test
    fun speedIsKeptInBounds() {
        timing.speed = 100f

        assertEquals(10f, timing.speed)
    }

    private fun assertEquals(expected: Long, actual: Long, delta: Double) = assertEquals(expected.toDouble(), actual.toDouble(), delta)

    private inline fun <reified T> proxy(crossinline onCall: (name: String, arguments: Array<Any?>) -> Unit): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, arguments ->
            onCall(method.name, arguments ?: emptyArray())
            when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                else -> null
            }
        } as T

    private companion object {
        // Media3 offsets every renderer position by a large constant.
        const val OFFSET_US = 1_000_000_000_000L
    }
}
