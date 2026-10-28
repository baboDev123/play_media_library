#pragma once

#include "playme_common.h"
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <mutex>
#include <queue>
#include <vector>

class PlayMeVideoDecoderNative {
public:
    PlayMeVideoDecoderNative(
        const std::string& mimeType,
        const uint8_t* extraData,
        size_t extraDataSize,
        int width,
        int height
    );
    ~PlayMeVideoDecoderNative();

    // Surface management with thread synchronization (Point f)
    bool setSurface(JNIEnv* env, jobject surface);

    // Send packet and drain all ready frames into queue (Point e)
    int sendPacket(const uint8_t* data, size_t size, int64_t timeUs);

    // Populate Media3 VideoDecoderOutputBuffer with YUV planes (Point c)
    int receiveYuvFrame(
        JNIEnv* env,
        jobject jOutputBuffer,
        jint outputMode,
        jlongArray outMetadata // [0]=pts, [1]=width, [2]=height, [3]=frameHandle
    );

    // Render frame to surface when Media3 requests presentation (Point c & f)
    bool renderFrameToSurface(JNIEnv* env, jobject surface, jlong frameHandle);

    // Release frame when displayed or dropped by Media3 (Point c)
    void releaseFrame(jlong frameHandle);

    // Flush codec buffers and frame queue on seek (Point d)
    void flush();

    // Release all native resources safely
    void release();

private:
    std::mutex mMutex; // Thread-safe lock (Point f)
    std::string mMimeType;
    int mWidth;
    int mHeight;
    ANativeWindow* mNativeWindow;

#ifndef PLAYME_STUB_FFMPEG
    const AVCodec* mCodec;
    AVCodecContext* mCodecContext;
    AVPacket* mPacket;
    SwsContext* mSwsContext;
    std::queue<AVFrame*> mFrameQueue; // Multi-frame drain queue (Point e)
    std::vector<AVFrame*> mActiveFrames; // Frames held by Media3 output buffers
#endif

    void clearFrameQueueLocked();
};
