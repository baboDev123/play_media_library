// Video decoding for io.github.playme.media.decoder.FfmpegVideoDecoder.
//
// The Kotlin decoder owns one decoding thread and calls everything here from it, except
// freeFrame, which runs wherever an output buffer is released. Decoded frames are handed out
// as AVFrame pointers; their pixel buffers are reference counted, so a frame stays valid after
// the decoder that made it is gone.
//
// Frames come out as 8-bit 4:2:0, which is what FrameWindowNative draws. Frames in other formats
// (10-bit video above all) are converted here, on the decoding thread, so the playback thread,
// which also feeds the audio, only has to draw them.

#include <deque>

#include "common.h"

extern "C" {
#include <libavutil/imgutils.h>
#include <libavutil/pixdesc.h>
#include <libswscale/swscale.h>
}

namespace {

using playmedia::describeError;

// Corrupt packets are skipped; only this many in a row fail playback.
constexpr int kMaxConsecutiveCorruptPackets = 30;

struct VideoDecoder {
    AVCodecContext* context = nullptr;
    AVPacket* packet = nullptr;
    std::deque<AVFrame*> ready;
    int corruptPackets = 0;
    // Converting frames of other formats to 8-bit 4:2:0.
    SwsContext* converter = nullptr;
    AVBufferPool* pool = nullptr;
    int poolSize = 0;
};

bool isYuv420(int format) { return format == AV_PIX_FMT_YUV420P || format == AV_PIX_FMT_YUVJ420P; }

// Planar 4:2:0 above 8 bits (10-bit video, 12-bit), little endian: the shift that brings it to 8.
int highBitDepthShift(int format) {
    const AVPixFmtDescriptor* descriptor = av_pix_fmt_desc_get(static_cast<AVPixelFormat>(format));
    if (descriptor == nullptr || descriptor->nb_components != 3 || descriptor->log2_chroma_w != 1 ||
        descriptor->log2_chroma_h != 1 || (descriptor->flags & (AV_PIX_FMT_FLAG_BE | AV_PIX_FMT_FLAG_RGB)) ||
        !(descriptor->flags & AV_PIX_FMT_FLAG_PLANAR)) {
        return 0;
    }
    const int depth = descriptor->comp[0].depth;
    return depth > 8 && depth <= 16 && descriptor->comp[0].step == 2 ? depth - 8 : 0;
}

// Rounds each sample to 8 bits; a plain loop the compiler vectorises.
void narrowPlane(const uint8_t* source, int sourceStride, uint8_t* target, int targetStride, int width, int rows, int shift) {
    const int half = 1 << (shift - 1);
    const int top = (1 << (shift + 8)) - 1;
    for (int y = 0; y < rows; y++) {
        const auto* in = reinterpret_cast<const uint16_t*>(source + static_cast<ptrdiff_t>(y) * sourceStride);
        uint8_t* out = target + static_cast<ptrdiff_t>(y) * targetStride;
        for (int x = 0; x < width; x++) {
            int value = in[x] + half;
            value = value > top ? top : value;
            out[x] = static_cast<uint8_t>(value >> shift);
        }
    }
}

// A new 8-bit 4:2:0 frame of [frame]'s size, its pixels from the decoder's pool.
AVFrame* newYuv420Frame(VideoDecoder* decoder, const AVFrame* frame) {
    const int size = av_image_get_buffer_size(AV_PIX_FMT_YUV420P, frame->width, frame->height, 64);
    if (size < 0) return nullptr;
    if (decoder->pool == nullptr || decoder->poolSize != size) {
        // Buffers still out keep the old pool alive until they come back.
        av_buffer_pool_uninit(&decoder->pool);
        decoder->pool = av_buffer_pool_init(size, nullptr);
        decoder->poolSize = size;
        if (decoder->pool == nullptr) return nullptr;
    }
    AVFrame* converted = av_frame_alloc();
    if (converted == nullptr) return nullptr;
    converted->buf[0] = av_buffer_pool_get(decoder->pool);
    if (converted->buf[0] == nullptr ||
        av_image_fill_arrays(converted->data, converted->linesize, converted->buf[0]->data, AV_PIX_FMT_YUV420P,
                             frame->width, frame->height, 64) < 0 ||
        av_frame_copy_props(converted, frame) < 0) {
        av_frame_free(&converted);
        return nullptr;
    }
    converted->format = AV_PIX_FMT_YUV420P;
    converted->width = frame->width;
    converted->height = frame->height;
    // Full range survives the conversion, as the colour matrix and range fields do.
    if (frame->format == AV_PIX_FMT_YUVJ420P) converted->color_range = AVCOL_RANGE_JPEG;
    return converted;
}

// [frame] as 8-bit 4:2:0, taking it over: the same frame when it already is, else a converted
// one (and [frame] is freed). Null when it can't be converted.
AVFrame* asYuv420(VideoDecoder* decoder, AVFrame* frame) {
    if (isYuv420(frame->format)) return frame;
    AVFrame* converted = newYuv420Frame(decoder, frame);
    if (converted != nullptr) {
        const int shift = highBitDepthShift(frame->format);
        if (shift > 0) {
            const int chromaWidth = (frame->width + 1) / 2;
            const int chromaRows = (frame->height + 1) / 2;
            narrowPlane(frame->data[0], frame->linesize[0], converted->data[0], converted->linesize[0], frame->width, frame->height, shift);
            narrowPlane(frame->data[1], frame->linesize[1], converted->data[1], converted->linesize[1], chromaWidth, chromaRows, shift);
            narrowPlane(frame->data[2], frame->linesize[2], converted->data[2], converted->linesize[2], chromaWidth, chromaRows, shift);
        } else {
            decoder->converter = sws_getCachedContext(
                decoder->converter, frame->width, frame->height, static_cast<AVPixelFormat>(frame->format),
                frame->width, frame->height, AV_PIX_FMT_YUV420P, SWS_POINT, nullptr, nullptr, nullptr);
            if (decoder->converter == nullptr) {
                av_frame_free(&converted);
            } else {
                sws_scale(decoder->converter, frame->data, frame->linesize, 0, frame->height, converted->data, converted->linesize);
            }
        }
    }
    av_frame_free(&frame);
    return converted;
}

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
        frame = asYuv420(decoder, frame);
        if (frame == nullptr) return AVERROR(ENOMEM);
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

// While [skip] is set the codec leaves out frames no other frame refers to, so a decoder that
// can't keep up shows fewer frames rather than falling ever further behind.
JNIEXPORT void JNICALL
Java_io_github_playme_media_decoder_VideoNative_skipNonReferenceFrames(JNIEnv*, jclass, jlong handle, jboolean skip) {
    auto* decoder = reinterpret_cast<VideoDecoder*>(handle);
    decoder->context->skip_frame = skip ? AVDISCARD_NONREF : AVDISCARD_DEFAULT;
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
    sws_freeContext(decoder->converter);
    av_buffer_pool_uninit(&decoder->pool);
    delete decoder;
}

JNIEXPORT void JNICALL
Java_io_github_playme_media_decoder_VideoNative_freeFrame(JNIEnv*, jclass, jlong frameHandle) {
    auto* frame = reinterpret_cast<AVFrame*>(frameHandle);
    av_frame_free(&frame);
}

}  // extern "C"
