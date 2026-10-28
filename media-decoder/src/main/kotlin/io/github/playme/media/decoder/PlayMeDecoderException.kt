package io.github.playme.media.decoder

import androidx.media3.decoder.DecoderException

/** A failure inside the FFmpeg decoders; Media3 reports it as a decoding error. */
class PlayMeDecoderException(message: String, cause: Throwable? = null) : DecoderException(message, cause)
