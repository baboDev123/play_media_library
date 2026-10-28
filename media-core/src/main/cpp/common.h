#pragma once

#include <android/log.h>
#include <jni.h>

#include <cstdint>
#include <string>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/avutil.h>
}

#define PM_LOG_TAG "PlayMedia"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, PM_LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, PM_LOG_TAG, __VA_ARGS__)

namespace playmedia {

/** The FFmpeg decoder for a Media3 video MIME type, or null when there is none. */
const AVCodec* videoDecoderFor(const char* mimeType);

/** The FFmpeg decoder for a Media3 audio MIME type, or null when there is none. */
const AVCodec* audioDecoderFor(const char* mimeType);

/** FFmpeg's description of an error code, for messages. */
std::string describeError(int error);

/** The JNI environment of the current thread, which must already be attached to the VM. */
JNIEnv* currentEnv();

/** Copies a Java byte array into a fresh FFmpeg buffer with the padding decoders expect. */
uint8_t* copyWithPadding(JNIEnv* env, jbyteArray bytes, int* size);

/** Throws a java.lang.IllegalStateException with [message]. */
void throwIllegalState(JNIEnv* env, const std::string& message);

}  // namespace playmedia
