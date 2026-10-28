package io.github.playme.media.info

/**
 * Enterprise typed exceptions for PlayMedia Info Engine.
 */
sealed class PlayMediaException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class UnsupportedCodecException(val codecName: String, message: String = "Unsupported codec: $codecName") : PlayMediaException(message)
    class CorruptStreamException(val ffmpegErrorCode: Int, message: String = "Corrupt stream data (error code $ffmpegErrorCode)") : PlayMediaException(message)
    class OutOfMemoryException(message: String = "Native memory allocation failed in media engine") : PlayMediaException(message)
    class StreamNotFoundException(message: String = "Requested media stream not found in container") : PlayMediaException(message)
    class NetworkTimeoutException(message: String = "Network read timed out or connection dropped") : PlayMediaException(message)
    class InvalidDataSourceException(message: String = "Unable to open or read from media data source") : PlayMediaException(message)
}
