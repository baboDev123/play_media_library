package io.github.playme.media.info

import android.graphics.Bitmap
import androidx.annotation.Keep

@Keep
internal object MediaInfoNativeBridge {

    init {
        try {
            System.loadLibrary("playme_info")
        } catch (e: UnsatisfiedLinkError) {
            System.err.println("MediaInfoNativeBridge: Failed to load native library: ${e.message}")
        }
    }

    @JvmStatic
    external fun nativeParseMediaInfo(filePath: String): MediaInfo?

    @JvmStatic
    external fun nativeExtractFrameAtTime(filePath: String, timeUs: Long, targetWidth: Int, targetHeight: Int): Bitmap?
}

