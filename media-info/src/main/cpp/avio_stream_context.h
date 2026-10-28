#pragma once

#include <jni.h>
#include <cstdint>

#ifndef PLAYME_STUB_FFMPEG
extern "C" {
#include <libavformat/avformat.h>
#include <libavformat/avio.h>
}
#endif

/**
 * Custom AVIOContext enabling FFmpeg to read directly from Java InputStream
 * (Supports Network streams: SMB, FTP, SFTP, HTTP, and Media3 DataSources).
 */
class AvioStreamContext {
public:
#ifndef PLAYME_STUB_FFMPEG
    static AVFormatContext* openInputStream(JNIEnv* env, jobject jInputStream, int64_t streamLength);
    static void closeContext(AVFormatContext* formatCtx);
#endif
};

