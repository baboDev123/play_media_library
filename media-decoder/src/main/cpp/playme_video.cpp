#include "playme_video.h"

PlayMeVideoDecoderNative::PlayMeVideoDecoderNative(const std::string& mimeType, int width, int height)
    : mMimeType(mimeType),
      mWidth(width),
      mHeight(height),
      mNativeWindow(nullptr)
#ifndef PLAYME_STUB_FFMPEG
      , mCodec(nullptr),
      mCodecContext(nullptr),
      mFrame(nullptr),
      mRgbFrame(nullptr),
      mPacket(nullptr),
      mSwsContext(nullptr)
#endif
{
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
    }

    if (codecId != AV_CODEC_ID_NONE) {
        mCodec = avcodec_find_decoder(codecId);
        if (mCodec) {
            mCodecContext = avcodec_alloc_context3(mCodec);
            if (mCodecContext) {
                mCodecContext->width = width;
                mCodecContext->height = height;
                // Enable multi-threaded decoding
                mCodecContext->thread_count = 0; // Auto-detect CPU cores
                if (mCodec->capabilities & AV_CODEC_CAP_FRAME_THREADS) {
                    mCodecContext->thread_type = FF_THREAD_FRAME;
                } else if (mCodec->capabilities & AV_CODEC_CAP_SLICE_THREADS) {
                    mCodecContext->thread_type = FF_THREAD_SLICE;
                }
                avcodec_open2(mCodecContext, mCodec, nullptr);
            }
        }
        mFrame = av_frame_alloc();
        mPacket = av_packet_alloc();
    }
#endif
}

PlayMeVideoDecoderNative::~PlayMeVideoDecoderNative() {
    release();
}

bool PlayMeVideoDecoderNative::setSurface(JNIEnv* env, jobject surface) {
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

int PlayMeVideoDecoderNative::decodeFrame(const uint8_t* data, size_t size, int64_t timeUs) {
#ifndef PLAYME_STUB_FFMPEG
    if (!mCodecContext || !mFrame || !mPacket) return -1;

    mPacket->data = const_cast<uint8_t*>(data);
    mPacket->size = static_cast<int>(size);
    mPacket->pts = timeUs;

    int ret = avcodec_send_packet(mCodecContext, mPacket);
    if (ret < 0 && ret != AVERROR(EAGAIN)) {
        return ret;
    }

    ret = avcodec_receive_frame(mCodecContext, mFrame);
    if (ret == 0) {
        renderFrameDirect();
        return 0;
    }
    return ret == AVERROR(EAGAIN) ? 0 : ret;
#else
    return 0;
#endif
}

void PlayMeVideoDecoderNative::renderFrameDirect() {
#ifndef PLAYME_STUB_FFMPEG
    if (!mNativeWindow || !mFrame) return;

    int width = mFrame->width;
    int height = mFrame->height;
    if (width <= 0 || height <= 0) return;

    ANativeWindow_setBuffersGeometry(mNativeWindow, width, height, WINDOW_FORMAT_RGBA_8888);

    ANativeWindow_Buffer windowBuffer;
    if (ANativeWindow_lock(mNativeWindow, &windowBuffer, nullptr) == 0) {
        mSwsContext = sws_getCachedContext(
            mSwsContext,
            width, height, static_cast<AVPixelFormat>(mFrame->format),
            width, height, AV_PIX_FMT_RGBA,
            SWS_FAST_BILINEAR, nullptr, nullptr, nullptr
        );

        if (mSwsContext) {
            uint8_t* destData[4] = { static_cast<uint8_t*>(windowBuffer.bits), nullptr, nullptr, nullptr };
            int destLinesize[4] = { windowBuffer.stride * 4, 0, 0, 0 };

            sws_scale(
                mSwsContext,
                mFrame->data,
                mFrame->linesize,
                0,
                height,
                destData,
                destLinesize
            );
        }

        ANativeWindow_unlockAndPost(mNativeWindow);
    }
#endif
}

void PlayMeVideoDecoderNative::release() {
    if (mNativeWindow) {
        ANativeWindow_release(mNativeWindow);
        mNativeWindow = nullptr;
    }
#ifndef PLAYME_STUB_FFMPEG
    if (mSwsContext) {
        sws_freeContext(mSwsContext);
        mSwsContext = nullptr;
    }
    if (mFrame) {
        av_frame_free(&mFrame);
    }
    if (mPacket) {
        av_packet_free(&mPacket);
    }
    if (mCodecContext) {
        avcodec_free_context(&mCodecContext);
    }
#endif
}

