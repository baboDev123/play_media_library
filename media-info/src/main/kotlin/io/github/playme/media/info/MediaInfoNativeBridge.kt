package io.github.playme.media.info

import android.graphics.Bitmap
import androidx.annotation.Keep
import java.io.InputStream

@Keep
internal object MediaInfoNativeBridge {

    init {
        try {
            System.loadLibrary("playme_info")
        } catch (e: UnsatisfiedLinkError) {
            System.err.println("MediaInfoNativeBridge: Failed to load native library: ${e.message}")
        }
    }

    // Path based
    @JvmStatic
    external fun nativeParseMediaInfo(filePath: String): MediaInfo?

    @JvmStatic
    external fun nativeExtractFrameAtTime(filePath: String, timeUs: Long, targetWidth: Int, targetHeight: Int): Bitmap?

    // FileDescriptor / Scoped Storage (Android 10+) based
    @JvmStatic
    external fun nativeParseMediaInfoFd(fd: Int): MediaInfo?

    @JvmStatic
    external fun nativeExtractFrameAtTimeFd(fd: Int, timeUs: Long, targetWidth: Int, targetHeight: Int): Bitmap?

    // Custom AVIOContext / Network Stream (SMB, FTP, SFTP) based
    @JvmStatic
    external fun nativeParseMediaInfoStream(inputStream: InputStream, streamLength: Long): MediaInfo?

    @JvmStatic
    external fun nativeExtractFrameAtTimeStream(
        inputStream: InputStream,
        streamLength: Long,
        timeUs: Long,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap?
}
