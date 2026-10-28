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
    SwrContext* resampler = nullptr;
    // What the resampler was set up for; a change rebuilds it.
    AVChannelLayout inputLayout = {};
    int inputFormat = AV_SAMPLE_FMT_NONE;
    int inputRate = 0;
    int outputChannels = 0;
    int outputRate = 0;
    std::vector<uint8_t> output;
    int corruptPackets = 0;
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
    const int channels = input.nb_channels <= kMaxOutputChannels ? input.nb_channels : 2;
    AVChannelLayout output = {};
    av_channel_layout_default(&output, channels);
    int result = swr_alloc_set_opts2(
        &decoder->resampler, &output, AV_SAMPLE_FMT_S16, frame->sample_rate,
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
    decoder->outputChannels = channels;
    decoder->outputRate = frame->sample_rate;
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
    decoder->output.resize(start + converted * bytesPerSample);
    return 0;
}

int toleratingCorruption(AudioDecoder* decoder, int result) {
    if (result != AVERROR_INVALIDDATA) return result;
    if (++decoder->corruptPackets > kMaxConsecutiveCorruptPackets) return result;
    return 0;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_io_github_playme_media_decoder_AudioNative_create(
    JNIEnv* env, jclass, jstring mimeType, jbyteArray extraData, jint sampleRate, jint channels) {
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
    AVCodecContext* context = decoder->context;
    if (context == nullptr || decoder->packet == nullptr || decoder->frame == nullptr) {
        avcodec_free_context(&decoder->context);
        av_packet_free(&decoder->packet);
        av_frame_free(&decoder->frame);
        delete decoder;
        playmedia::throwIllegalState(env, "Out of memory creating the audio decoder");
        return 0;
    }
    if (sampleRate > 0) context->sample_rate = sampleRate;
    if (channels > 0) av_channel_layout_default(&context->ch_layout, channels);
    context->pkt_timebase = AVRational{1, 1000000};
    context->extradata = playmedia::copyWithPadding(env, extraData, &context->extradata_size);

    const int result = avcodec_open2(context, codec, nullptr);
    if (result < 0) {
        avcodec_free_context(&decoder->context);
        av_packet_free(&decoder->packet);
        av_frame_free(&decoder->frame);
        delete decoder;
        playmedia::throwIllegalState(env, std::string("Opening ") + codec->name + " failed: " + playmedia::describeError(result));
        return 0;
    }
    return reinterpret_cast<jlong>(decoder);
}

JNIEXPORT jstring JNICALL
Java_io_github_playme_media_decoder_AudioNative_name(JNIEnv* env, jclass, jlong handle) {
    auto* decoder = reinterpret_cast<AudioDecoder*>(handle);
    return env->NewStringUTF(decoder->context->codec->name);
}

// Decodes one sample into the decoder's output. Returns its size in bytes (0 when the codec
// needs more input, or skipped a corrupt packet), or a negative FFmpeg error.
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
    result = avcodec_send_packet(decoder->context, packet);
    av_packet_unref(packet);
    if (result < 0) return toleratingCorruption(decoder, result);

    while ((result = avcodec_receive_frame(decoder->context, decoder->frame)) >= 0) {
        result = append(decoder, decoder->frame);
        av_frame_unref(decoder->frame);
        if (result < 0) return result;
    }
    if (result != AVERROR(EAGAIN) && result != AVERROR_EOF) return toleratingCorruption(decoder, result);
    decoder->corruptPackets = 0;
    return static_cast<jint>(decoder->output.size());
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
    // The resampler may hold samples from before the seek.
    dropResampler(decoder);
    decoder->output.clear();
    decoder->corruptPackets = 0;
}

JNIEXPORT void JNICALL
Java_io_github_playme_media_decoder_AudioNative_destroy(JNIEnv*, jclass, jlong handle) {
    auto* decoder = reinterpret_cast<AudioDecoder*>(handle);
    dropResampler(decoder);
    avcodec_free_context(&decoder->context);
    av_packet_free(&decoder->packet);
    av_frame_free(&decoder->frame);
    delete decoder;
}

}  // extern "C"
