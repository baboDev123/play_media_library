#include "playme_video.h"
#include <algorithm>

PlayMeVideoDecoderNative::PlayMeVideoDecoderNative(
    const std::string& mimeType,
    const uint8_t* extraData,
    size_t extraDataSize,
    int width,
    int height
) : mMimeType(mimeType),
    mWidth(width),
    mHeight(height),
    mNativeWindow(nullptr)
#ifndef PLAYME_STUB_FFMPEG
    , mCodec(nullptr),
    mCodecContext(nullptr),
    mPacket(nullptr),
    mSwsContext(nullptr)
#endif
{
    std::lock_guard<std::mutex> lock(mMutex);

#ifndef PLAYME_STUB_FFMPEG
    AVCodecID codecId = AV_CODEC_ID_NONE;
    if (mimeType == "video/avc" || mimeType == "video/h264") {
        codecId = AV_CODEC_ID_H264;
    } else if (mimeType == "video/hevc" || mimeType == "video/h265") {
        codecId = AV_CODEC_ID_HEVC;
    } else if (mimeType == "video/x-vnd.on2.vp8") {
        codecId = AV_CODEC_ID_VP8;
    } else if (mimeType == "video/x-vnd.on2.vp9") {
        codecId = AV_CODEC_ID_VP9;
    } else if (mimeType == "video/av01") {
        codecId = AV_CODEC_ID_AV1;
    } else if (mimeType == "video/mpeg2") {
        codecId = AV_CODEC_ID_MPEG2VIDEO;
    } else if (mimeType == "video/mp4v-es") {
        codecId = AV_CODEC_ID_MPEG4;
    } else if (mimeType == "video/wvc1" || mimeType == "video/x-ms-wmv") {
        codecId = AV_CODEC_ID_VC1;
    } else if (mimeType == "video/x-ms-wmv3") {
        codecId = AV_CODEC_ID_WMV3;
    } else if (mimeType == "video/mjpeg") {
        codecId = AV_CODEC_ID_MJPEG;
    }

    if (codecId != AV_CODEC_ID_NONE) {
        mCodec = avcodec_find_decoder(codecId);
        if (mCodec) {
            mCodecContext = avcodec_alloc_context3(mCodec);
            if (mCodecContext) {
                mCodecContext->width = width;
                mCodecContext->height = height;

                // Point b: extradata (SPS/PPS) must be provided before avcodec_open2
                if (extraData && extraDataSize > 0) {
                    mCodecContext->extradata = static_cast<uint8_t*>(
                        av_mallocz(extraDataSize + AV_INPUT_BUFFER_PADDING_SIZE)
                    );
                    if (mCodecContext->extradata) {
                        memcpy(mCodecContext->extradata, extraData, extraDataSize);
                        mCodecContext->extradata_size = static_cast<int>(extraDataSize);
                    }
                }

                // Enable multi-threading for fast decode
                mCodecContext->thread_count = 0; // Auto-detect CPU cores
                if (mCodec->capabilities & AV_CODEC_CAP_FRAME_THREADS) {
                    mCodecContext->thread_type = FF_THREAD_FRAME;
                } else if (mCodec->capabilities & AV_CODEC_CAP_SLICE_THREADS) {
                    mCodecContext->thread_type = FF_THREAD_SLICE;
                }

                avcodec_open2(mCodecContext, mCodec, nullptr);
            }
        }
        mPacket = av_packet_alloc();
    }
#endif
}

PlayMeVideoDecoderNative::~PlayMeVideoDecoderNative() {
    release();
}

// Point f: Thread-safe Surface Lock
bool PlayMeVideoDecoderNative::setSurface(JNIEnv* env, jobject surface) {
    std::lock_guard<std::mutex> lock(mMutex);
    if (mNativeWindow) {
        ANativeWindow_release(mNativeWindow);
        mNativeWindow = nullptr;
    }
    if (surface) {
        mNativeWindow = ANativeWindow_fromSurface(env, surface);
        if (mNativeWindow && mWidth > 0 && mHeight > 0) {
            ANativeWindow_setBuffersGeometry(mNativeWindow, mWidth, mHeight, WINDOW_FORMAT_RGBA_8888);
        }
        return mNativeWindow != nullptr;
    }
    return true;
}

// Point e: Multi-frame drain loop (Ek packet se nikalne wale saare frames lena)
int PlayMeVideoDecoderNative::sendPacket(const uint8_t* data, size_t size, int64_t timeUs) {
    std::lock_guard<std::mutex> lock(mMutex);
#ifndef PLAYME_STUB_FFMPEG
    if (!mCodecContext || !mPacket) return -1;

    if (data && size > 0) {
        mPacket->data = const_cast<uint8_t*>(data);
        mPacket->size = static_cast<int>(size);
        mPacket->pts = timeUs;
    } else {
        // End of stream flush packet
        mPacket->data = nullptr;
        mPacket->size = 0;
    }

    int ret = avcodec_send_packet(mCodecContext, mPacket);
    if (ret < 0 && ret != AVERROR(EAGAIN) && ret != AVERROR_EOF) {
        return ret;
    }

    // Drain loop: fetch ALL available frames produced by this packet
    while (true) {
        AVFrame* frame = av_frame_alloc();
        ret = avcodec_receive_frame(mCodecContext, frame);
        if (ret == 0) {
            mFrameQueue.push(frame);
        } else {
            av_frame_free(&frame);
            break;
        }
    }

    return mFrameQueue.empty() ? AVERROR(EAGAIN) : 0;
#else
    return 0;
#endif
}

// Point c: Return decoded YUV frame to Media3 output buffer
int PlayMeVideoDecoderNative::receiveYuvFrame(
    JNIEnv* env,
    jobject jOutputBuffer,
    jint outputMode,
    jlongArray outMetadata
) {
    std::lock_guard<std::mutex> lock(mMutex);
#ifndef PLAYME_STUB_FFMPEG
    if (mFrameQueue.empty()) {
        return AVERROR(EAGAIN);
    }

    AVFrame* frame = mFrameQueue.front();
    mFrameQueue.pop();
    mActiveFrames.push_back(frame);

    int64_t pts = frame->pts != AV_NOPTS_VALUE ? frame->pts : 0;
    int width = frame->width;
    int height = frame->height;
    int64_t handle = reinterpret_cast<int64_t>(frame);

    jlong metadata[4] = { pts, static_cast<jlong>(width), static_cast<jlong>(height), handle };
    env->SetLongArrayRegion(outMetadata, 0, 4, metadata);

    return 0;
#else
    return 0;
#endif
}

// Point c & f: Media3 calls this at the exact synchronized presentation timestamp!
bool PlayMeVideoDecoderNative::renderFrameToSurface(JNIEnv* env, jobject surface, jlong frameHandle) {
    std::lock_guard<std::mutex> lock(mMutex);
#ifndef PLAYME_STUB_FFMPEG
    auto* frame = reinterpret_cast<AVFrame*>(frameHandle);
    if (!frame) return false;

    ANativeWindow* window = nullptr;
    if (surface) {
        window = ANativeWindow_fromSurface(env, surface);
    } else {
        window = mNativeWindow;
    }
    if (!window) return false;

    int width = frame->width;
    int height = frame->height;
    if (width <= 0 || height <= 0) return false;

    ANativeWindow_setBuffersGeometry(window, width, height, WINDOW_FORMAT_RGBA_8888);

    ANativeWindow_Buffer windowBuffer;
    if (ANativeWindow_lock(window, &windowBuffer, nullptr) == 0) {
        mSwsContext = sws_getCachedContext(
            mSwsContext,
            width, height, static_cast<AVPixelFormat>(frame->format),
            width, height, AV_PIX_FMT_RGBA,
            SWS_FAST_BILINEAR, nullptr, nullptr, nullptr
        );

        if (mSwsContext) {
            uint8_t* destData[4] = { static_cast<uint8_t*>(windowBuffer.bits), nullptr, nullptr, nullptr };
            int destLinesize[4] = { windowBuffer.stride * 4, 0, 0, 0 };

            sws_scale(
                mSwsContext,
                frame->data,
                frame->linesize,
                0,
                height,
                destData,
                destLinesize
            );
        }

        ANativeWindow_unlockAndPost(window);
    }

    if (surface && window != mNativeWindow) {
        ANativeWindow_release(window);
    }
    return true;
#else
    return true;
#endif
}

// Point c: Called by Media3 when buffer is displayed or dropped
void PlayMeVideoDecoderNative::releaseFrame(jlong frameHandle) {
    std::lock_guard<std::mutex> lock(mMutex);
#ifndef PLAYME_STUB_FFMPEG
    auto* frame = reinterpret_cast<AVFrame*>(frameHandle);
    if (!frame) return;

    auto it = std::find(mActiveFrames.begin(), mActiveFrames.end(), frame);
    if (it != mActiveFrames.end()) {
        mActiveFrames.erase(it);
    }
    av_frame_free(&frame);
#endif
}

// Point d: Seek par Flush (clear codec buffers and all frame queues)
void PlayMeVideoDecoderNative::flush() {
    std::lock_guard<std::mutex> lock(mMutex);
#ifndef PLAYME_STUB_FFMPEG
    if (mCodecContext) {
        avcodec_flush_buffers(mCodecContext);
    }
    clearFrameQueueLocked();
#endif
}

void PlayMeVideoDecoderNative::clearFrameQueueLocked() {
#ifndef PLAYME_STUB_FFMPEG
    while (!mFrameQueue.empty()) {
        AVFrame* frame = mFrameQueue.front();
        mFrameQueue.pop();
        av_frame_free(&frame);
    }
    for (AVFrame* frame : mActiveFrames) {
        av_frame_free(&frame);
    }
    mActiveFrames.clear();
#endif
}

void PlayMeVideoDecoderNative::release() {
    std::lock_guard<std::mutex> lock(mMutex);
    if (mNativeWindow) {
        ANativeWindow_release(mNativeWindow);
        mNativeWindow = nullptr;
    }
#ifndef PLAYME_STUB_FFMPEG
    clearFrameQueueLocked();
    if (mSwsContext) {
        sws_freeContext(mSwsContext);
        mSwsContext = nullptr;
    }
    if (mPacket) {
        av_packet_free(&mPacket);
    }
    if (mCodecContext) {
        avcodec_free_context(&mCodecContext);
    }
#endif
}
