package io.github.playme.media

/**
 * Enterprise typed exceptions for PlayMedia Engine.
 * Replaces generic error codes with clean, actionable exception types.
 */
sealed class PlayMediaException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /**
     * Thrown when the container or stream uses an unsupported or disabled audio/video codec.
     */
    class UnsupportedCodecException(val codecName: String, message: String = "Unsupported codec: $codecName") :
        PlayMediaException(message)

    /**
     * Thrown when the stream contains invalid headers, corrupt packets, or truncated container data.
     */
    class CorruptStreamException(val ffmpegErrorCode: Int, message: String = "Corrupt stream data (error code $ffmpegErrorCode)") :
        PlayMediaException(message)

    /**
     * Thrown when native buffer allocation or frame decoding runs out of memory.
     */
    class OutOfMemoryException(message: String = "Native memory allocation failed in media engine") :
        PlayMediaException(message)

    /**
     * Thrown when the requested audio, video, or subtitle stream is absent in the container.
     */
    class StreamNotFoundException(message: String = "Requested media stream not found in container") :
        PlayMediaException(message)

    /**
     * Thrown when network streaming (SMB, FTP, SFTP, HTTP) encounters a socket timeout or disconnection.
     */
    class NetworkTimeoutException(message: String = "Network read timed out or connection dropped") :
        PlayMediaException(message)

    /**
     * Thrown when the input file path, Content Uri, FileDescriptor, or InputStream cannot be opened.
     */
    class InvalidDataSourceException(message: String = "Unable to open or read from media data source") :
        PlayMediaException(message)
}
