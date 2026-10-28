#include "media_info_reader.h"
#include "thumbnail_extractor.h"

extern "C" {

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

} // extern "C"

