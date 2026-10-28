#pragma once

#include "common.h"

namespace playmedia {

/**
 * An opened container. FFmpeg reads it through our own I/O, either from a file descriptor or
 * from a Java android.media.MediaDataSource, so content URIs, network shares and plain files
 * all work, with seeking, and without FFmpeg's protocols. Reading gives up after a deadline.
 */
class MediaInput {
public:
    /** Opens [fd] (not taken over: the caller closes it). Null, with [error] set, on failure. */
    static MediaInput* fromFd(int fd, int64_t timeoutMs, std::string* error);

    /** Opens a Java MediaDataSource. Calls into it happen on this thread only. */
    static MediaInput* fromSource(JNIEnv* env, jobject source, int64_t timeoutMs, std::string* error);

    ~MediaInput();

    AVFormatContext* format() const { return format_; }

    /** True once the deadline passed; FFmpeg then fails with AVERROR_EXIT. */
    bool timedOut() const;

    class Reader;

private:
    MediaInput() = default;
    static MediaInput* open(Reader* reader, int64_t timeoutMs, std::string* error);

    Reader* reader_ = nullptr;
    AVIOContext* io_ = nullptr;
    AVFormatContext* format_ = nullptr;
    int64_t deadlineUs_ = 0;
};

}  // namespace playmedia
