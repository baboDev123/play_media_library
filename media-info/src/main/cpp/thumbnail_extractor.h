#pragma once

#include <android/bitmap.h>
#include <jni.h>
#include <string>

class ThumbnailExtractorNative {
public:
    static jobject extractFrameAtTime(
        JNIEnv* env,
        const std::string& filePath,
        int64_t timeUs,
        int targetWidth,
        int targetHeight
    );

    static jobject extractFrameAtTimeStream(
        JNIEnv* env,
        jobject jInputStream,
        int64_t streamLength,
        int64_t timeUs,
        int targetWidth,
        int targetHeight
    );
};
