package io.github.playme.media.decoder

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameSkippingTest {
    @Test
    fun startsSkippingOnceFramesComeOutTooLateToShow() {
        assertFalse(skipsNonReferenceFrames(skipping = false, earlyUs = -20_000))
        assertTrue(skipsNonReferenceFrames(skipping = false, earlyUs = -31_000))
    }

    @Test
    fun keepsSkippingUntilFramesAreNearlyOnTime() {
        assertTrue(skipsNonReferenceFrames(skipping = true, earlyUs = -20_000))
        assertTrue(skipsNonReferenceFrames(skipping = true, earlyUs = -6_000))
        assertFalse(skipsNonReferenceFrames(skipping = true, earlyUs = -4_000))
        assertFalse(skipsNonReferenceFrames(skipping = true, earlyUs = 10_000))
    }

    @Test
    fun aFrameInBetweenChangesNothing() {
        assertFalse(skipsNonReferenceFrames(skipping = false, earlyUs = -10_000))
        assertTrue(skipsNonReferenceFrames(skipping = true, earlyUs = -10_000))
    }
}
