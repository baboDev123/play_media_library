// Video decoding for io.github.playme.media.decoder.FfmpegVideoDecoder.
//
// The Kotlin decoder owns one decoding thread and calls everything here from it, except
// freeFrame, which runs wherever an output buffer is released. Decoded frames are handed out
// as AVFrame pointers; their pixel buffers are reference counted, so a frame stays valid after
// the decoder that made it is gone.

#include <deque>

#include "common.h"

namespace {

using playmedia::describeError;

// Corrupt packets are skipped; only this many in a row fail playback.
constexpr int kMaxConsecutiveCorruptPackets = 30;

struct VideoDecoder {
    AVCodecContext* context = nullptr;
    AVPacket* packet = nullptr;
    std::deque<AVFrame*> ready;
    int corruptPackets = 0;
};

void dropReady(VideoDecoder* decoder) {
    for (AVFrame* frame : decoder->ready) av_frame_free(&frame);
    decoder->ready.clear();
}

// Moves every frame the codec has finished into the ready queue.
int receiveAll(VideoDecoder* decoder) {
    while (true) {
        AVFrame* frame = av_frame_alloc();
        if (frame == nullptr) return AVERROR(ENOMEM);
        const int result = avcodec_receive_frame(decoder->context, frame);
        if (result < 0) {
            av_frame_free(&frame);
            return result == AVERROR(EAGAIN) || result == AVERROR_EOF ? 0 : result;
        }
        decoder->ready.push_back(frame);
    }
}

// A corrupt packet costs at most a glitch; anything else, or too many in a row, is fatal.
int toleratingCorruption(VideoDecoder* decoder, int result) {
    if (result != AVERROR_INVALIDDATA) return result;
    if (++decoder->corruptPackets > kMaxConsecutiveCorruptPackets) return result;
    return 0;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_io_github_playme_media_decoder_VideoNative_create(
    JNIEnv* env, jclass, jstring mimeType, jbyteArray extraData, jint width, jint height, jint threads) {
    const char* mime = env->GetStringUTFChars(mimeType, nullptr);
    const AVCodec* codec = playmedia::videoDecoderFor(mime);
    env->ReleaseStringUTFChars(mimeType, mime);
    if (codec == nullptr) {
        playmedia::throwIllegalState(env, "No FFmpeg video decoder for this format");
        return 0;
    }

    auto* decoder = new VideoDecoder();
    decoder->context = avcodec_alloc_context3(codec);
    decoder->packet = av_packet_alloc();
    if (decoder->context == nullptr || decoder->packet == nullptr) {
        avcodec_free_context(&decoder->context);
        av_packet_free(&decoder->packet);
        delete decoder;
        playmedia::throwIllegalState(env, "Out of memory creating the video decoder");
        return 0;
    }
    AVCodecContext* context = decoder->context;
    context->width = width;
    context->height = height;
    context->pkt_timebase = AVRational{1, 1000000};
    context->extradata = playmedia::copyWithPadding(env, extraData, &context->extradata_size);
    context->thread_count = threads;
    context->thread_type = FF_THREAD_FRAME | FF_THREAD_SLICE;

    const int result = avcodec_open2(context, codec, nullptr);
    if (result < 0) {
        avcodec_free_context(&decoder->context);
        av_packet_free(&decoder->packet);
        delete decoder;
        playmedia::throwIllegalState(env, std::string("Opening ") + codec->name + " failed: " + describeError(result));
        return 0;
    }
    return reinterpret_cast<jlong>(decoder);
}

JNIEXPORT jstring JNICALL
Java_io_github_playme_media_decoder_VideoNative_name(JNIEnv* env, jclass, jlong handle) {
    auto* decoder = reinterpret_cast<VideoDecoder*>(handle);
    return env->NewStringUTF(decoder->context->codec->name);
}

// Decodes one sample. Returns how many frames are now ready, or a negative FFmpeg error.
JNIEXPORT jint JNICALL
Java_io_github_playme_media_decoder_VideoNative_send(
    JNIEnv* env, jclass, jlong handle, jobject data, jint offset, jint size, jlong timeUs, jboolean keyFrame) {
    auto* decoder = reinterpret_cast<VideoDecoder*>(handle);
    auto* bytes = static_cast<const uint8_t*>(env->GetDirectBufferAddress(data));
    if (bytes == nullptr || size <= 0) return static_cast<jint>(decoder->ready.size());

    AVPacket* packet = decoder->packet;
    // A copy: decoders read a little past the end, and Media3's buffer has no padding.
    int result = av_new_packet(packet, size);
    if (result < 0) return result;
    memcpy(packet->data, bytes + offset, size);
    packet->pts = timeUs;
    packet->dts = AV_NOPTS_VALUE;
    if (keyFrame) packet->flags |= AV_PKT_FLAG_KEY;

    result = avcodec_send_packet(decoder->context, packet);
    av_packet_unref(packet);
    if (result < 0) {
        result = toleratingCorruption(decoder, result);
        return result < 0 ? result : static_cast<jint>(decoder->ready.size());
    }
    result = toleratingCorruption(decoder, receiveAll(decoder));
    if (result < 0) return result;
    decoder->corruptPackets = 0;
    return static_cast<jint>(decoder->ready.size());
}

// Ends the stream, so the codec gives up the frames it still holds. Returns frames ready.
JNIEXPORT jint JNICALL
Java_io_github_playme_media_decoder_VideoNative_drain(JNIEnv*, jclass, jlong handle) {
    auto* decoder = reinterpret_cast<VideoDecoder*>(handle);
    int result = avcodec_send_packet(decoder->context, nullptr);
    if (result < 0 && result != AVERROR_EOF) return result;
    result = receiveAll(decoder);
    return result < 0 ? result : static_cast<jint>(decoder->ready.size());
}

// The next ready frame, now owned by the caller (see freeFrame), or 0. Fills
// [info] with its presentation time in microseconds, width and height.
JNIEXPORT jlong JNICALL
Java_io_github_playme_media_decoder_VideoNative_nextFrame(JNIEnv* env, jclass, jlong handle, jlongArray info) {
    auto* decoder = reinterpret_cast<VideoDecoder*>(handle);
    if (decoder->ready.empty()) return 0;
    AVFrame* frame = decoder->ready.front();
    decoder->ready.pop_front();
    const int64_t timeUs = frame->best_effort_timestamp != AV_NOPTS_VALUE ? frame->best_effort_timestamp : frame->pts;
    const jlong values[] = {timeUs, frame->width, frame->height};
    env->SetLongArrayRegion(info, 0, 3, values);
    return reinterpret_cast<jlong>(frame);
}

JNIEXPORT void JNICALL
Java_io_github_playme_media_decoder_VideoNative_flush(JNIEnv*, jclass, jlong handle) {
    auto* decoder = reinterpret_cast<VideoDecoder*>(handle);
    avcodec_flush_buffers(decoder->context);
    dropReady(decoder);
    decoder->corruptPackets = 0;
}

JNIEXPORT void JNICALL
Java_io_github_playme_media_decoder_VideoNative_destroy(JNIEnv*, jclass, jlong handle) {
    auto* decoder = reinterpret_cast<VideoDecoder*>(handle);
    dropReady(decoder);
    avcodec_free_context(&decoder->context);
    av_packet_free(&decoder->packet);
    delete decoder;
}

JNIEXPORT void JNICALL
Java_io_github_playme_media_decoder_VideoNative_freeFrame(JNIEnv*, jclass, jlong frameHandle) {
    auto* frame = reinterpret_cast<AVFrame*>(frameHandle);
    av_frame_free(&frame);
}

}  // extern "C"
