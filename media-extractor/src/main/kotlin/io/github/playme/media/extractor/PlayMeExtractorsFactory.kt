package io.github.playme.media.extractor

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.text.SubtitleParser
import io.github.playme.media.core.PlayMediaNative
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Media3's extractors, with [FfmpegExtractor] behind them for local files: Media3 reads
 * everything it can, and FFmpeg takes what it can't recognise. FFmpeg goes first only for files
 * Media3 is known to fail on: MP4s whose index wouldn't fit the Java heap, and files the app
 * marked with [FfmpegFallback] after Media3 failed on them.
 *
 * Network sources (anything not a file or content URI) stay with Media3 alone.
 */
class PlayMeExtractorsFactory @JvmOverloads constructor(
    context: Context,
    private val media3: DefaultExtractorsFactory = DefaultExtractorsFactory(),
) : ExtractorsFactory {

    private val resolver = context.applicationContext.contentResolver

    override fun createExtractors(): Array<Extractor> = media3.createExtractors()

    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> {
        val own = media3.createExtractors(uri, responseHeaders)
        if (!PlayMediaNative.isAvailable || !isLocal(uri)) return own
        val ffmpeg = FfmpegExtractor { open(uri) }
        return if (FfmpegFallback.isPreferred(uri) || hasOversizedMp4Index(uri)) arrayOf(ffmpeg, *own) else arrayOf(*own, ffmpeg)
    }

    // Subtitle and parsing settings reach Media3's extractors as before.
    override fun experimentalSetTextTrackTranscodingEnabled(textTrackTranscodingEnabled: Boolean): ExtractorsFactory = apply {
        media3.experimentalSetTextTrackTranscodingEnabled(textTrackTranscodingEnabled)
    }

    override fun setSubtitleParserFactory(subtitleParserFactory: SubtitleParser.Factory): ExtractorsFactory = apply {
        media3.setSubtitleParserFactory(subtitleParserFactory)
    }

    override fun experimentalSetCodecsToParseWithinGopSampleDependencies(codecsToParseWithinGopSampleDependencies: Int): ExtractorsFactory = apply {
        media3.experimentalSetCodecsToParseWithinGopSampleDependencies(codecsToParseWithinGopSampleDependencies)
    }

    override fun setParseHagcMetadata(parseHagcMetadata: Boolean): ExtractorsFactory = apply {
        media3.setParseHagcMetadata(parseHagcMetadata)
    }

    private fun isLocal(uri: Uri): Boolean = when (uri.scheme?.lowercase()) {
        ContentResolver.SCHEME_CONTENT, ContentResolver.SCHEME_FILE, null -> true
        else -> false
    }

    private fun open(uri: Uri): ParcelFileDescriptor? {
        if (uri.scheme?.lowercase() == ContentResolver.SCHEME_CONTENT) return resolver.openFileDescriptor(uri, "r")
        val path = uri.path ?: return null
        return ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun hasOversizedMp4Index(uri: Uri): Boolean = runCatching {
        open(uri)?.use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { stream ->
                (Mp4Boxes.movieBoxSize(stream.channel) ?: 0L) > MAX_MEDIA3_MP4_INDEX
            }
        } ?: false
    }.getOrDefault(false)

    private companion object {
        /**
         * Media3 holds an MP4's whole index in the Java heap, several times over while parsing:
         * a 114 MB index (a 27-hour recording) needed about 230 MB, more than many devices give
         * an app. Indexes larger than this go to FFmpeg, which keeps them in native memory.
         */
        const val MAX_MEDIA3_MP4_INDEX = 40L * 1024 * 1024
    }
}

/**
 * Files the app wants read with FFmpeg first, typically after Media3 failed on them
 * (an [androidx.media3.common.PlaybackException] for the container): call [prefer] and prepare
 * the player again.
 */
object FfmpegFallback {
    private val preferred = ConcurrentHashMap.newKeySet<String>()

    @JvmStatic
    fun prefer(uri: Uri) {
        preferred += keyOf(uri)
    }

    /** Back to Media3 first, say once the file changed. */
    @JvmStatic
    fun forget(uri: Uri) {
        preferred -= keyOf(uri)
    }

    @JvmStatic
    fun isPreferred(uri: Uri): Boolean = keyOf(uri) in preferred

    /** "file:/x", "file:///x" and a bare path are the same file. */
    private fun keyOf(uri: Uri): String = when (uri.scheme?.lowercase()) {
        ContentResolver.SCHEME_FILE, null -> "file:" + uri.path
        else -> uri.toString()
    }
}
