#include "media_info_reader.h"
#include "thumbnail_extractor.h"
#include <cstdio>

extern "C" {

// --- Path Based APIs ---

JNIEXPORT jobject JNICALL
Java_io_github_playme_media_info_MediaInfoNativeBridge_nativeParseMediaInfo(
    JNIEnv* env, jclass, jstring jFilePath) {
    if (!jFilePath) return nullptr;
    const char* path = env->GetStringUTFChars(jFilePath, nullptr);
    jobject result = MediaInfoReaderNative::parseMediaInfo(env, path);
    env->ReleaseStringUTFChars(jFilePath, path);
    return result;
}

JNIEXPORT jobject JNICALL
Java_io_github_playme_media_info_MediaInfoNativeBridge_nativeExtractFrameAtTime(
    JNIEnv* env, jclass, jstring jFilePath, jlong timeUs, jint targetWidth, jint targetHeight) {
    if (!jFilePath) return nullptr;
    const char* path = env->GetStringUTFChars(jFilePath, nullptr);
    jobject result = ThumbnailExtractorNative::extractFrameAtTime(env, path, timeUs, targetWidth, targetHeight);
    env->ReleaseStringUTFChars(jFilePath, path);
    return result;
}

// --- FileDescriptor / Scoped Storage (Android 10+) APIs ---

JNIEXPORT jobject JNICALL
Java_io_github_playme_media_info_MediaInfoNativeBridge_nativeParseMediaInfoFd(
    JNIEnv* env, jclass, jint fd) {
    if (fd < 0) return nullptr;
    char pipeUrl[64];
    snprintf(pipeUrl, sizeof(pipeUrl), "pipe:%d", fd);
    return MediaInfoReaderNative::parseMediaInfo(env, pipeUrl);
}

JNIEXPORT jobject JNICALL
Java_io_github_playme_media_info_MediaInfoNativeBridge_nativeExtractFrameAtTimeFd(
    JNIEnv* env, jclass, jint fd, jlong timeUs, jint targetWidth, jint targetHeight) {
    if (fd < 0) return nullptr;
    char pipeUrl[64];
    snprintf(pipeUrl, sizeof(pipeUrl), "pipe:%d", fd);
    return ThumbnailExtractorNative::extractFrameAtTime(env, pipeUrl, timeUs, targetWidth, targetHeight);
}

} // extern "C"
