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
    return env->NewStringUTF("PlayMedia-Engine-1.0.0-LGPL");
#endif
}

JNIEXPORT jboolean JNICALL
Java_io_github_playme_media_decoder_PlayMeNativeBridge_isCodecSupported(JNIEnv* env, jclass, jstring jMime) {
    if (!jMime) return JNI_FALSE;
    const char* mime = env->GetStringUTFChars(jMime, nullptr);
    std::string mimeStr(mime);
    env->ReleaseStringUTFChars(jMime, mime);

    // Audio formats (LGPL)
    if (mimeStr == "audio/ac3" || mimeStr == "audio/eac3" ||
        mimeStr == "audio/vnd.dts" || mimeStr == "audio/dts" || mimeStr == "audio/vnd.dts.hd" ||
        mimeStr == "audio/true-hd" || mimeStr == "audio/flac" ||
        mimeStr == "audio/vorbis" || mimeStr == "audio/opus" ||
        mimeStr == "audio/alac" || mimeStr == "audio/mp4a-latm" ||
        mimeStr == "audio/mpeg" || mimeStr == "audio/mp3" ||
        mimeStr == "audio/mpeg-L2" || mimeStr == "audio/mp2" ||
        mimeStr == "audio/x-ms-wma" || mimeStr == "audio/x-ms-wmapro" ||
        mimeStr == "audio/3gpp" || mimeStr == "audio/amr-wb" ||
        mimeStr == "audio/raw" || mimeStr == "audio/g711-alaw" || mimeStr == "audio/g711-mlaw") {
        return JNI_TRUE;
    }

    // Video formats (LGPL)
    if (mimeStr == "video/avc" || mimeStr == "video/h264" ||
        mimeStr == "video/hevc" || mimeStr == "video/h265" ||
        mimeStr == "video/x-vnd.on2.vp8" || mimeStr == "video/x-vnd.on2.vp9" ||
        mimeStr == "video/av01" || mimeStr == "video/mpeg2" ||
        mimeStr == "video/mp4v-es" || mimeStr == "video/wvc1" ||
        mimeStr == "video/x-ms-wmv" || mimeStr == "video/x-ms-wmv3" ||
        mimeStr == "video/mjpeg") {
        return JNI_TRUE;
    }

    return JNI_FALSE;
}

// ==============================================================================
// Video Decoder Native JNI
// ==============================================================================

// Point b: extradata (SPS/PPS) support
JNIEXPORT jlong JNICALL
Java_io_github_playme_media_decoder_PlayMeNativeBridge_nativeInitVideoDecoder(
    JNIEnv* env, jclass, jstring jMime, jbyteArray jExtraData, jint width, jint height) {
    const char* mime = env->GetStringUTFChars(jMime, nullptr);

    uint8_t* extraDataPtr = nullptr;
    size_t extraDataSize = 0;
    if (jExtraData) {
        extraDataSize = static_cast<size_t>(env->GetArrayLength(jExtraData));
        extraDataPtr = static_cast<uint8_t*>(malloc(extraDataSize));
        env->GetByteArrayRegion(jExtraData, 0, extraDataSize, reinterpret_cast<jbyte*>(extraDataPtr));
    }

    auto* decoder = new PlayMeVideoDecoderNative(mime, extraDataPtr, extraDataSize, width, height);

    if (extraDataPtr) {
        free(extraDataPtr);
    }
    env->ReleaseStringUTFChars(jMime, mime);

    return reinterpret_cast<jlong>(decoder);
}

// Point f: Thread-safe surface update
JNIEXPORT jboolean JNICALL
Java_io_github_playme_media_decoder_PlayMeNativeBridge_nativeSetVideoSurface(
    JNIEnv* env, jclass, jlong handle, jobject surface) {
    auto* decoder = reinterpret_cast<PlayMeVideoDecoderNative*>(handle);
    if (!decoder) return JNI_FALSE;
    return decoder->setSurface(env, surface) ? JNI_TRUE : JNI_FALSE;
}

// Point e: Multi-frame drain loop
JNIEXPORT jint JNICALL
Java_io_github_playme_media_decoder_PlayMeNativeBridge_nativeSendVideoPacket(
    JNIEnv* env, jclass, jlong handle, jobject jInputData, jint inputSize, jlong timeUs) {
    auto* decoder = reinterpret_cast<PlayMeVideoDecoderNative*>(handle);
    if (!decoder) return -1;

    uint8_t* data = nullptr;
    if (jInputData && inputSize > 0) {
        data = static_cast<uint8_t*>(env->GetDirectBufferAddress(jInputData));
    }
    return decoder->sendPacket(data, static_cast<size_t>(inputSize), timeUs);
}

// Point c: Return decoded YUV frame metadata and handle to Media3
JNIEXPORT jint JNICALL
Java_io_github_playme_media_decoder_PlayMeNativeBridge_nativeReceiveVideoFrame(
    JNIEnv* env, jclass, jlong handle, jobject jOutputBuffer, jint outputMode, jlongArray metadata) {
    auto* decoder = reinterpret_cast<PlayMeVideoDecoderNative*>(handle);
    if (!decoder) return -1;
    return decoder->receiveYuvFrame(env, jOutputBuffer, outputMode, metadata);
}

// Point c & f: Render frame at exact Media3 presentation timestamp
JNIEXPORT jboolean JNICALL
Java_io_github_playme_media_decoder_PlayMeNativeBridge_nativeRenderVideoFrameToSurface(
    JNIEnv* env, jclass, jlong handle, jobject surface, jlong frameHandle) {
    auto* decoder = reinterpret_cast<PlayMeVideoDecoderNative*>(handle);
    if (!decoder) return JNI_FALSE;
    return decoder->renderFrameToSurface(env, surface, frameHandle) ? JNI_TRUE : JNI_FALSE;
}

// Point c: Release frame when displayed or dropped by Media3
JNIEXPORT void JNICALL
Java_io_github_playme_media_decoder_PlayMeNativeBridge_nativeReleaseVideoFrame(
    JNIEnv*, jclass, jlong handle, jlong frameHandle) {
    auto* decoder = reinterpret_cast<PlayMeVideoDecoderNative*>(handle);
    if (decoder) {
        decoder->releaseFrame(frameHandle);
    }
}

// Point d: Seek par Flush
JNIEXPORT void JNICALL
Java_io_github_playme_media_decoder_PlayMeNativeBridge_nativeFlushVideoDecoder(
    JNIEnv*, jclass, jlong handle) {
    auto* decoder = reinterpret_cast<PlayMeVideoDecoderNative*>(handle);
    if (decoder) {
        decoder->flush();
    }
}

JNIEXPORT void JNICALL
Java_io_github_playme_media_decoder_PlayMeNativeBridge_nativeReleaseVideoDecoder(
    JNIEnv*, jclass, jlong handle) {
    auto* decoder = reinterpret_cast<PlayMeVideoDecoderNative*>(handle);
    delete decoder;
}

// ==============================================================================
// Audio Decoder Native JNI
// ==============================================================================

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
