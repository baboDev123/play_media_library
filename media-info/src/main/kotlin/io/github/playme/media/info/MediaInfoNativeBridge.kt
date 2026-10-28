package io.github.playme.media.info

import android.graphics.Bitmap
import android.media.MediaDataSource
import io.github.playme.media.core.PlayMediaNative

/**
 * media-core's media_probe.cpp. Each call reads either the file descriptor [fd] or, when it
 * isn't null, the [source]; reading gives up after [timeoutMs] (0 for never).
 */
internal object MediaInfoNativeBridge {
    init {
        PlayMediaNative.requireAvailable()
    }

    /** The container's facts, labelled with [path]; null when it can't be read. */
    @JvmStatic external fun probe(fd: Int, source: MediaDataSource?, path: String, timeoutMs: Long): MediaInfo?

    /** The frame at [timeUs], unrotated; [rotation] gets the turn it needs. Null without video. */
    @JvmStatic external fun frame(
        fd: Int,
        source: MediaDataSource?,
        timeUs: Long,
        maxWidth: Int,
        maxHeight: Int,
        exact: Boolean,
        timeoutMs: Long,
        rotation: IntArray,
    ): Bitmap?

    /** The encoded cover picture (JPEG or PNG), or null. */
    @JvmStatic external fun embeddedPicture(fd: Int, source: MediaDataSource?, timeoutMs: Long): ByteArray?
}
