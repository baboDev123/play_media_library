#include "avio_stream_context.h"
#include <cstdlib>
#include <cstring>

#ifndef PLAYME_STUB_FFMPEG

struct InputStreamOpaque {
    JavaVM* jvm;
    jobject inputStreamGlobal;
    jmethodID readMethod;
    jmethodID skipMethod;
    jbyteArray bufferArray;
    int64_t totalLength;
    int64_t currentPosition;
};

static int readPacketCallback(void* opaque, uint8_t* buf, int buf_size) {
    auto* ctx = static_cast<InputStreamOpaque*>(opaque);
    if (!ctx || !ctx->inputStreamGlobal) return AVERROR_EOF;

    JNIEnv* env = nullptr;
    if (ctx->jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (ctx->jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            return AVERROR(EIO);
        }
    }

    int bytesToRead = buf_size;
    jint bytesRead = env->CallIntMethod(
        ctx->inputStreamGlobal,
        ctx->readMethod,
        ctx->bufferArray,
        0,
        bytesToRead
    );

    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return AVERROR(EIO);
    }

    if (bytesRead < 0) {
        return AVERROR_EOF;
    }

    if (bytesRead > 0) {
        env->GetByteArrayRegion(ctx->bufferArray, 0, bytesRead, reinterpret_cast<jbyte*>(buf));
        ctx->currentPosition += bytesRead;
    }

    return bytesRead;
}

static int64_t seekCallback(void* opaque, int64_t offset, int whence) {
    auto* ctx = static_cast<InputStreamOpaque*>(opaque);
    if (!ctx) return -1;

    if (whence == AVSEEK_SIZE) {
        return ctx->totalLength > 0 ? ctx->totalLength : -1;
    }

    // Basic forward seek support via InputStream.skip
    if (whence == SEEK_CUR && offset >= 0) {
        JNIEnv* env = nullptr;
        if (ctx->jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
            if (ctx->jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) return -1;
        }

        jlong skipped = env->CallLongMethod(ctx->inputStreamGlobal, ctx->skipMethod, static_cast<jlong>(offset));
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            return -1;
        }
        ctx->currentPosition += skipped;
        return ctx->currentPosition;
    }

    return -1; // Arbitrary backward seeks unsupported on simple stream
}

AVFormatContext* AvioStreamContext::openInputStream(JNIEnv* env, jobject jInputStream, int64_t streamLength) {
    if (!jInputStream) return nullptr;

    JavaVM* jvm = nullptr;
    env->GetJavaVM(&jvm);

    jclass inputStreamClass = env->GetObjectClass(jInputStream);
    jmethodID readMethod = env->GetMethodID(inputStreamClass, "read", "([BII)I");
    jmethodID skipMethod = env->GetMethodID(inputStreamClass, "skip", "(J)J");

    constexpr size_t AVIO_BUFFER_SIZE = 32768;
    auto* avioBuffer = static_cast<unsigned char*>(av_malloc(AVIO_BUFFER_SIZE));
    if (!avioBuffer) return nullptr;

    auto* opaque = new InputStreamOpaque();
    opaque->jvm = jvm;
    opaque->inputStreamGlobal = env->NewGlobalRef(jInputStream);
    opaque->readMethod = readMethod;
    opaque->skipMethod = skipMethod;
    opaque->bufferArray = env->NewByteArray(AVIO_BUFFER_SIZE);
    opaque->bufferArray = static_cast<jbyteArray>(env->NewGlobalRef(opaque->bufferArray));
    opaque->totalLength = streamLength;
    opaque->currentPosition = 0;

    AVIOContext* avioCtx = avio_alloc_context(
        avioBuffer,
        AVIO_BUFFER_SIZE,
        0, // read-only
        opaque,
        readPacketCallback,
        nullptr,
        seekCallback
    );

    if (!avioCtx) {
        av_free(avioBuffer);
        env->DeleteGlobalRef(opaque->inputStreamGlobal);
        env->DeleteGlobalRef(opaque->bufferArray);
        delete opaque;
        return nullptr;
    }

    AVFormatContext* formatCtx = avformat_alloc_context();
    if (!formatCtx) {
        avio_context_free(&avioCtx);
        env->DeleteGlobalRef(opaque->inputStreamGlobal);
        env->DeleteGlobalRef(opaque->bufferArray);
        delete opaque;
        return nullptr;
    }

    formatCtx->pb = avioCtx;
    formatCtx->flags |= AVFMT_FLAG_CUSTOM_IO;

    if (avformat_open_input(&formatCtx, nullptr, nullptr, nullptr) < 0) {
        closeContext(formatCtx);
        return nullptr;
    }

    return formatCtx;
}

void AvioStreamContext::closeContext(AVFormatContext* formatCtx) {
    if (!formatCtx) return;

    if (formatCtx->pb) {
        auto* opaque = static_cast<InputStreamOpaque*>(formatCtx->pb->opaque);
        if (opaque) {
            JNIEnv* env = nullptr;
            if (opaque->jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK) {
                if (opaque->inputStreamGlobal) env->DeleteGlobalRef(opaque->inputStreamGlobal);
                if (opaque->bufferArray) env->DeleteGlobalRef(opaque->bufferArray);
            }
            delete opaque;
        }
        av_freep(&formatCtx->pb->buffer);
        avio_context_free(&formatCtx->pb);
    }
    avformat_close_input(&formatCtx);
}

#endif

