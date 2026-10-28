// Audio decoding for io.github.playme.media.decoder.FfmpegAudioDecoder, called from its
// decoding thread only. Each sample decodes to interleaved 16-bit PCM at the source's own
// rate, with up to 8 channels in the order Android expects.

#include <algorithm>
#include <vector>

#include "common.h"

extern "C" {
#include <libswresample/swresample.h>
}

namespace {

constexpr int kMaxOutputChannels = 8;
constexpr int kMaxConsecutiveCorruptPackets = 30;

struct AudioDecoder {
    AVCodecContext* context = nullptr;
    AVPacket* packet = nullptr;
    AVFrame* frame = nullptr;
    // Splits samples holding several frames (as Media3 hands over E-AC3, DTS, TrueHD...).
    AVCodecParserContext* parser = nullptr;
    AVPacket* piece = nullptr;
    SwrContext* resampler = nullptr;
    // What the resampler was set up for; a change rebuilds it.
    AVChannelLayout inputLayout = {};
    int inputFormat = AV_SAMPLE_FMT_NONE;
    int inputRate = 0;
    int outputChannels = 0;
    int outputRate = 0;
    std::vector<uint8_t> output;
    int corruptPackets = 0;
    // Samples at the start that only prime the decoder (Opus's pre-skip).
    int samplesToSkip = 0;
};

void dropResampler(AudioDecoder* decoder) {
    swr_free(&decoder->resampler);
    av_channel_layout_uninit(&decoder->inputLayout);
    decoder->inputFormat = AV_SAMPLE_FMT_NONE;
    decoder->inputRate = 0;
}

int ensureResampler(AudioDecoder* decoder, const AVFrame* frame) {
    AVChannelLayout input = {};
    if (frame->ch_layout.order == AV_CHANNEL_ORDER_UNSPEC) {
        av_channel_layout_default(&input, frame->ch_layout.nb_channels);
    } else {
        av_channel_layout_copy(&input, &frame->ch_layout);
    }
    const bool unchanged = decoder->resampler != nullptr && frame->format == decoder->inputFormat &&
                           frame->sample_rate == decoder->inputRate &&
                           av_channel_layout_compare(&input, &decoder->inputLayout) == 0;
    if (unchanged) {
        av_channel_layout_uninit(&input);
        return 0;
    }

    dropResampler(decoder);
    // The output layout is fixed by the first frame: the audio sink was set up for it.
    if (decoder->outputChannels == 0) decoder->outputChannels = input.nb_channels <= kMaxOutputChannels ? input.nb_channels : 2;
    const int channels = decoder->outputChannels;
    AVChannelLayout output = {};
    av_channel_layout_default(&output, channels);
    int result = swr_alloc_set_opts2(
        &decoder->resampler, &output, AV_SAMPLE_FMT_S16, decoder->outputRate > 0 ? decoder->outputRate : frame->sample_rate,
        &input, static_cast<AVSampleFormat>(frame->format), frame->sample_rate, 0, nullptr);
    av_channel_layout_uninit(&output);
    if (result >= 0) result = swr_init(decoder->resampler);
    if (result < 0) {
        av_channel_layout_uninit(&input);
        swr_free(&decoder->resampler);
        return result;
    }
    decoder->inputLayout = input;
    decoder->inputFormat = frame->format;
    decoder->inputRate = frame->sample_rate;
    if (decoder->outputRate == 0) decoder->outputRate = frame->sample_rate;
    return 0;
}

// Appends [frame], converted, to the decoder's output.
int append(AudioDecoder* decoder, const AVFrame* frame) {
    int result = ensureResampler(decoder, frame);
    if (result < 0) return result;
    const int capacity = swr_get_out_samples(decoder->resampler, frame->nb_samples);
    if (capacity <= 0) return 0;
    const size_t bytesPerSample = static_cast<size_t>(decoder->outputChannels) * 2;
    const size_t start = decoder->output.size();
    decoder->output.resize(start + capacity * bytesPerSample);
    uint8_t* destination = decoder->output.data() + start;
    const int converted = swr_convert(
        decoder->resampler, &destination, capacity,
        const_cast<const uint8_t**>(frame->extended_data), frame->nb_samples);
    if (converted < 0) {
        decoder->output.resize(start);
        return converted;
    }
    int kept = converted;
    if (decoder->samplesToSkip > 0) {
        const int skipped = std::min(decoder->samplesToSkip, converted);
        decoder->samplesToSkip -= skipped;
        kept -= skipped;
        memmove(destination, destination + skipped * bytesPerSample, kept * bytesPerSample);
    }
    decoder->output.resize(start + kept * bytesPerSample);
    return 0;
}

int toleratingCorruption(AudioDecoder* decoder, int result) {
    if (result != AVERROR_INVALIDDATA) return result;
    if (++decoder->corruptPackets > kMaxConsecutiveCorruptPackets) return result;
    return 0;
}

// Sends one compressed frame and collects the audio it gives. Returns 0 or a fatal FFmpeg error.
int decodeFrame(AudioDecoder* decoder, AVPacket* packet) {
    int result = avcodec_send_packet(decoder->context, packet);
    if (result < 0) return toleratingCorruption(decoder, result);
    while ((result = avcodec_receive_frame(decoder->context, decoder->frame)) >= 0) {
        result = append(decoder, decoder->frame);
        av_frame_unref(decoder->frame);
        if (result < 0) return result;
    }
    // A bad frame loses only itself: what decoded before it is kept.
    if (result != AVERROR(EAGAIN) && result != AVERROR_EOF) return toleratingCorruption(decoder, result);
    decoder->corruptPackets = 0;
    return 0;
}

// Splitting into frames is FFmpeg's own job for these codecs; others get whole samples.
AVCodecParserContext* parserFor(AVCodecID codec) {
    switch (codec) {
        case AV_CODEC_ID_AC3:
        case AV_CODEC_ID_EAC3:
        case AV_CODEC_ID_DTS:
        case AV_CODEC_ID_TRUEHD:
        case AV_CODEC_ID_MLP:
        case AV_CODEC_ID_MP1:
        case AV_CODEC_ID_MP2:
        case AV_CODEC_ID_MP3: {
            AVCodecParserContext* parser = av_parser_init(codec);
            if (parser != nullptr) parser->flags |= PARSER_FLAG_COMPLETE_FRAMES;
            return parser;
        }
        default:
            return nullptr;
    }
}

void destroy(AudioDecoder* decoder) {
    dropResampler(decoder);
    av_parser_close(decoder->parser);
    avcodec_free_context(&decoder->context);
    av_packet_free(&decoder->packet);
    av_packet_free(&decoder->piece);
    av_frame_free(&decoder->frame);
    delete decoder;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_io_github_playme_media_decoder_AudioNative_create(
    JNIEnv* env, jclass, jstring mimeType, jbyteArray extraData, jint sampleRate, jint channels, jint skipSamples) {
    const char* mime = env->GetStringUTFChars(mimeType, nullptr);
    const AVCodec* codec = playmedia::audioDecoderFor(mime);
    env->ReleaseStringUTFChars(mimeType, mime);
    if (codec == nullptr) {
        playmedia::throwIllegalState(env, "No FFmpeg audio decoder for this format");
        return 0;
    }

    auto* decoder = new AudioDecoder();
    decoder->context = avcodec_alloc_context3(codec);
    decoder->packet = av_packet_alloc();
    decoder->frame = av_frame_alloc();
    decoder->piece = av_packet_alloc();
    decoder->parser = parserFor(codec->id);
    AVCodecContext* context = decoder->context;
    if (context == nullptr || decoder->packet == nullptr || decoder->frame == nullptr || decoder->piece == nullptr) {
        destroy(decoder);
        playmedia::throwIllegalState(env, "Out of memory creating the audio decoder");
        return 0;
    }
    if (sampleRate > 0) context->sample_rate = sampleRate;
    if (channels > 0) av_channel_layout_default(&context->ch_layout, channels);
    context->pkt_timebase = AVRational{1, 1000000};
    context->extradata = playmedia::copyWithPadding(env, extraData, &context->extradata_size);

    const int result = avcodec_open2(context, codec, nullptr);
    if (result < 0) {
        destroy(decoder);
        playmedia::throwIllegalState(env, std::string("Opening ") + codec->name + " failed: " + playmedia::describeError(result));
        return 0;
    }
    decoder->samplesToSkip = std::max(0, skipSamples);
    return reinterpret_cast<jlong>(decoder);
}

JNIEXPORT jstring JNICALL
Java_io_github_playme_media_decoder_AudioNative_name(JNIEnv* env, jclass, jlong handle) {
    auto* decoder = reinterpret_cast<AudioDecoder*>(handle);
    return env->NewStringUTF(decoder->context->codec->name);
}

// Decodes one sample into the decoder's output. Returns its size in bytes (0 when the codec
// needs more input, or skipped a corrupt frame), or a negative FFmpeg error.
JNIEXPORT jint JNICALL
Java_io_github_playme_media_decoder_AudioNative_decode(
    JNIEnv* env, jclass, jlong handle, jobject data, jint offset, jint size) {
    auto* decoder = reinterpret_cast<AudioDecoder*>(handle);
    decoder->output.clear();
    auto* bytes = static_cast<const uint8_t*>(env->GetDirectBufferAddress(data));
    if (bytes == nullptr || size <= 0) return 0;

    AVPacket* packet = decoder->packet;
    int result = av_new_packet(packet, size);
    if (result < 0) return result;
    memcpy(packet->data, bytes + offset, size);

    if (decoder->parser == nullptr) {
        result = decodeFrame(decoder, packet);
    } else {
        // Every frame in the sample, then a flush so none waits for the next sample.
        const uint8_t* remaining = packet->data;
        int left = packet->size;
        bool flushed = false;
        while (result >= 0 && !flushed) {
            flushed = left == 0;
            const int used = av_parser_parse2(decoder->parser, decoder->context, &decoder->piece->data, &decoder->piece->size,
                remaining, left, AV_NOPTS_VALUE, AV_NOPTS_VALUE, 0);
            if (used < 0) break;
            remaining += used;
            left -= used;
            if (decoder->piece->size > 0) result = decodeFrame(decoder, decoder->piece);
        }
    }
    av_packet_unref(packet);
    return result < 0 ? result : static_cast<jint>(decoder->output.size());
}

// Copies the last decode's output to [target]'s position.
JNIEXPORT void JNICALL
Java_io_github_playme_media_decoder_AudioNative_copyOutput(JNIEnv* env, jclass, jlong handle, jobject target, jint position) {
    auto* decoder = reinterpret_cast<AudioDecoder*>(handle);
    auto* destination = static_cast<uint8_t*>(env->GetDirectBufferAddress(target));
    if (destination != nullptr) memcpy(destination + position, decoder->output.data(), decoder->output.size());
}

JNIEXPORT jint JNICALL
Java_io_github_playme_media_decoder_AudioNative_outputChannels(JNIEnv*, jclass, jlong handle) {
    auto* decoder = reinterpret_cast<AudioDecoder*>(handle);
    if (decoder->outputChannels > 0) return decoder->outputChannels;
    return std::min(decoder->context->ch_layout.nb_channels, kMaxOutputChannels);
}

JNIEXPORT jint JNICALL
Java_io_github_playme_media_decoder_AudioNative_outputSampleRate(JNIEnv*, jclass, jlong handle) {
    auto* decoder = reinterpret_cast<AudioDecoder*>(handle);
    return decoder->outputRate > 0 ? decoder->outputRate : decoder->context->sample_rate;
}

JNIEXPORT void JNICALL
Java_io_github_playme_media_decoder_AudioNative_flush(JNIEnv*, jclass, jlong handle) {
    auto* decoder = reinterpret_cast<AudioDecoder*>(handle);
    avcodec_flush_buffers(decoder->context);
    // The parser and resampler may hold data from before the seek.
    if (decoder->parser != nullptr) {
        av_parser_close(decoder->parser);
        decoder->parser = parserFor(decoder->context->codec_id);
    }
    dropResampler(decoder);
    decoder->output.clear();
    decoder->corruptPackets = 0;
}

JNIEXPORT void JNICALL
Java_io_github_playme_media_decoder_AudioNative_destroy(JNIEnv*, jclass, jlong handle) {
    destroy(reinterpret_cast<AudioDecoder*>(handle));
}

}  // extern "C"
