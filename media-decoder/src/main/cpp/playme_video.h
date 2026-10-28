#pragma once

#include "playme_common.h"
#include <android/native_window.h>
#include <android/native_window_jni.h>

class PlayMeVideoDecoderNative {
public:
    PlayMeVideoDecoderNative(const std::string& mimeType, int width, int height);
    ~PlayMeVideoDecoderNative();

    bool setSurface(JNIEnv* env, jobject surface);
    int decodeFrame(const uint8_t* data, size_t size, int64_t timeUs);
    void release();

private:
    std::string mMimeType;
    int mWidth;
    int mHeight;
    ANativeWindow* mNativeWindow;

#ifndef PLAYME_STUB_FFMPEG
    const AVCodec* mCodec;
    AVCodecContext* mCodecContext;
    AVFrame* mFrame;
    AVFrame* mRgbFrame;
    AVPacket* mPacket;
    SwsContext* mSwsContext;
#endif

    void renderFrameDirect();
};

