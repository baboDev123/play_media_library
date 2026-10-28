#pragma once

#include "media_info_reader.h"
#include <android/bitmap.h>

class ThumbnailExtractorNative {
public:
    static jobject extractFrameAtTime(JNIEnv* env, const std::string& filePath, int64_t timeUs, int targetWidth, int targetHeight);
};

