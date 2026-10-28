#include "playme_common.h"
#include "playme_video.h"
#include "playme_audio.h"

extern "C" {

JNIEXPORT jboolean JNICALL
Java_io_github_playme_media_decoder_PlayMeNativeBridge_isNativeAvailable(JNIEnv*, jclass) {
    return JNI_TRUE;
}

JNIEXPORT jstring JNICALL
Java_io_github_playme_media_decoder_PlayMeNativeBridge_getFfmpegVersion(JNIEnv* env, jclass) {
#ifndef PLAYME_STUB_FFMPEG
    return env->NewStringUTF(av_version_info());
#else
    return env->NewStringUTF("PlayMe-Native-Engine-1.0.0");
#endif
}

JNIEXPORT jboolean JNICALL
Java_io_github_playme_media_decoder_PlayMeNativeBridge_isCodecSupported(JNIEnv* env, jclass, jstring jMime) {
    if (!jMime) return JNI_FALSE;
    const char* mime = env->GetStringUTFChars(jMime, nullptr);
    std::string mimeStr(mime);
    env->ReleaseStringUTFChars(jMime, mime);

    // Audio formats
    if (mimeStr == "audio/ac3" || mimeStr == "audio/eac3" ||
        mimeStr == "audio/vnd.dts" || mimeStr == "audio/dts" ||
        mimeStr == "audio/true-hd" || mimeStr == "audio/flac" ||
        mimeStr == "audio/vorbis" || mimeStr == "audio/opus" ||
        mimeStr == "audio/alac" || mimeStr == "audio/mp4a-latm") {
        return JNI_TRUE;
    }

    // Video formats
    if (mimeStr == "video/avc" || mimeStr == "video/h264" ||
        mimeStr == "video/hevc" || mimeStr == "video/h265" ||
        mimeStr == "video/x-vnd.on2.vp8" || mimeStr == "video/x-vnd.on2.vp9" ||
        mimeStr == "video/av01" || mimeStr == "video/mpeg2") {
        return JNI_TRUE;
    }

    return JNI_FALSE;
}

// --- Video Decoder Native JNI ---

JNIEXPORT jlong JNICALL
Java_io_github_playme_media_decoder_PlayMeNativeBridge_nativeInitVideoDecoder(
    JNIEnv* env, jclass, jstring jMime, jobject surface, jint width, jint height) {
    const char* mime = env->GetStringUTFChars(jMime, nullptr);
    auto* decoder = new PlayMeVideoDecoderNative(mime, width, height);
    env->ReleaseStringUTFChars(jMime, mime);

    if (surface) {
        decoder->setSurface(env, surface);
    }
    return reinterpret_cast<jlong>(decoder);
}

JNIEXPORT jboolean JNICALL
Java_io_github_playme_media_decoder_PlayMeNativeBridge_nativeSetVideoSurface(
    JNIEnv* env, jclass, jlong handle, jobject surface) {
    auto* decoder = reinterpret_cast<PlayMeVideoDecoderNative*>(handle);
    if (!decoder) return JNI_FALSE;
    return decoder->setSurface(env, surface) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_io_github_playme_media_decoder_PlayMeNativeBridge_nativeDecodeVideoFrame(
    JNIEnv* env, jclass, jlong handle, jobject jInputData, jint inputSize, jlong timeUs) {
    auto* decoder = reinterpret_cast<PlayMeVideoDecoderNative*>(handle);
    if (!decoder || !jInputData) return -1;

    auto* data = static_cast<uint8_t*>(env->GetDirectBufferAddress(jInputData));
    if (!data) return -1;

    return decoder->decodeFrame(data, static_cast<size_t>(inputSize), timeUs);
}

JNIEXPORT void JNICALL
Java_io_github_playme_media_decoder_PlayMeNativeBridge_nativeReleaseVideoDecoder(
    JNIEnv*, jclass, jlong handle) {
    auto* decoder = reinterpret_cast<PlayMeVideoDecoderNative*>(handle);
    delete decoder;
}

// --- Audio Decoder Native JNI ---

JNIEXPORT jlong JNICALL
Java_io_github_playme_media_decoder_PlayMeNativeBridge_nativeInitAudioDecoder(
    JNIEnv* env, jclass, jstring jMime, jint sampleRate, jint channelCount) {
    const char* mime = env->GetStringUTFChars(jMime, nullptr);
    auto* decoder = new PlayMeAudioDecoderNative(mime, sampleRate, channelCount);
    env->ReleaseStringUTFChars(jMime, mime);
    return reinterpret_cast<jlong>(decoder);
}

JNIEXPORT jint JNICALL
Java_io_github_playme_media_decoder_PlayMeNativeBridge_nativeDecodeAudioFrame(
    JNIEnv* env, jclass, jlong handle, jobject jInputData, jint inputSize, jobject jOutputData, jint outputCapacity) {
    auto* decoder = reinterpret_cast<PlayMeAudioDecoderNative*>(handle);
    if (!decoder || !jInputData || !jOutputData) return -1;

    auto* inData = static_cast<uint8_t*>(env->GetDirectBufferAddress(jInputData));
    auto* outData = static_cast<uint8_t*>(env->GetDirectBufferAddress(jOutputData));
    if (!inData || !outData) return -1;

    return decoder->decodeAudio(inData, static_cast<size_t>(inputSize), outData, static_cast<size_t>(outputCapacity));
}

JNIEXPORT void JNICALL
Java_io_github_playme_media_decoder_PlayMeNativeBridge_nativeFlushAudioDecoder(
    JNIEnv*, jclass, jlong handle) {
    auto* decoder = reinterpret_cast<PlayMeAudioDecoderNative*>(handle);
    if (decoder) {
        decoder->flush();
    }
}

JNIEXPORT void JNICALL
Java_io_github_playme_media_decoder_PlayMeNativeBridge_nativeReleaseAudioDecoder(
    JNIEnv*, jclass, jlong handle) {
    auto* decoder = reinterpret_cast<PlayMeAudioDecoderNative*>(handle);
    delete decoder;
}

} // extern "C"

