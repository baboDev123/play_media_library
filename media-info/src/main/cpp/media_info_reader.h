#pragma once

#include <android/log.h>
#include <jni.h>
#include <string>
#include <vector>

#define LOG_TAG "PlayMeInfoNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#ifndef PLAYME_STUB_FFMPEG
extern "C" {
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/avutil.h>
#include <libavutil/dict.h>
}
#endif

class MediaInfoReaderNative {
public:
    static jobject parseMediaInfo(JNIEnv* env, const std::string& filePath);
};

