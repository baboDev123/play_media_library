package io.github.playme.media.core

import android.util.Log
import androidx.annotation.RestrictTo

/**
 * The native engine (FFmpeg and dav1d) shared by the decoder and info modules. Loading it is
 * cheap and happens once; everything else checks [isAvailable] first, so an app keeps working
 * (without FFmpeg) on a device where the library can't load.
 */
object PlayMediaNative {

    /** True when the native engine loaded. */
    @JvmStatic
    val isAvailable: Boolean = try {
        System.loadLibrary("playmedia")
        true
    } catch (error: UnsatisfiedLinkError) {
        Log.e("PlayMedia", "Native engine unavailable", error)
        false
    }

    /** The FFmpeg version inside, or null when the engine isn't available. */
    @JvmStatic
    val ffmpegVersion: String?
        get() = if (isAvailable) ffmpegVersion() else null

    /** Whether FFmpeg can decode video of this Media3 MIME type. */
    @JvmStatic
    fun canDecodeVideo(mimeType: String): Boolean = isAvailable && supportsVideo(mimeType)

    /** Whether FFmpeg can decode audio of this Media3 MIME type. */
    @JvmStatic
    fun canDecodeAudio(mimeType: String): Boolean = isAvailable && supportsAudio(mimeType)

    /** Loads the engine, for the other modules' native bridges. */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    fun requireAvailable() {
        check(isAvailable) { "PlayMedia's native engine couldn't be loaded" }
    }

    @JvmStatic
    private external fun ffmpegVersion(): String

    @JvmStatic
    private external fun supportsVideo(mimeType: String): Boolean

    @JvmStatic
    private external fun supportsAudio(mimeType: String): Boolean
}
