#include "media_input.h"

#include <cerrno>
#include <sys/stat.h>
#include <unistd.h>

extern "C" {
#include <libavutil/time.h>
}

namespace playmedia {

constexpr int kIoBufferSize = 64 * 1024;

class MediaInput::Reader {
public:
    virtual ~Reader() = default;
    virtual int read(uint8_t* into, int size) = 0;
    /** The new position, or a negative value when the source can't seek there. */
    virtual int64_t seek(int64_t offset, int whence) = 0;

protected:
    int64_t resolve(int64_t offset, int whence, int64_t length) const {
        switch (whence) {
            case SEEK_SET: return offset;
            case SEEK_CUR: return position_ + offset;
            case SEEK_END: return length >= 0 ? length + offset : -1;
            default: return -1;
        }
    }

    int64_t position_ = 0;
};

namespace {

// Reads with pread, so seeking is free. Descriptors that can't seek (pipes) are read in order.
class FdReader : public MediaInput::Reader {
public:
    explicit FdReader(int fd) : fd_(fd) {
        struct stat info = {};
        length_ = fstat(fd, &info) == 0 && S_ISREG(info.st_mode) ? info.st_size : -1;
        seekable_ = lseek(fd, 0, SEEK_CUR) >= 0;
    }

    int read(uint8_t* into, int size) override {
        ssize_t count;
        do {
            count = seekable_ ? pread(fd_, into, size, position_) : ::read(fd_, into, size);
        } while (count < 0 && errno == EINTR);
        if (count < 0) return AVERROR(errno);
        if (count == 0) return AVERROR_EOF;
        position_ += count;
        return static_cast<int>(count);
    }

    int64_t seek(int64_t offset, int whence) override {
        if (whence == AVSEEK_SIZE) return length_;
        if (!seekable_) return -1;
        const int64_t target = resolve(offset, whence, length_);
        if (target < 0) return -1;
        position_ = target;
        return target;
    }

private:
    int fd_;
    int64_t length_;
    bool seekable_;
};

// Reads an android.media.MediaDataSource: readAt(position, buffer, offset, size), getSize().
class SourceReader : public MediaInput::Reader {
public:
    SourceReader(JNIEnv* env, jobject source) {
        source_ = env->NewGlobalRef(source);
        jclass type = env->GetObjectClass(source);
        readAt_ = env->GetMethodID(type, "readAt", "(J[BII)I");
        getSize_ = env->GetMethodID(type, "getSize", "()J");
        env->DeleteLocalRef(type);
        jbyteArray chunk = env->NewByteArray(kIoBufferSize);
        chunk_ = static_cast<jbyteArray>(env->NewGlobalRef(chunk));
        env->DeleteLocalRef(chunk);
        length_ = env->CallLongMethod(source_, getSize_);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            length_ = -1;
        }
    }

    ~SourceReader() override {
        JNIEnv* env = currentEnv();
        if (env == nullptr) return;
        env->DeleteGlobalRef(source_);
        env->DeleteGlobalRef(chunk_);
    }

    int read(uint8_t* into, int size) override {
        JNIEnv* env = currentEnv();
        if (env == nullptr) return AVERROR(EIO);
        const int wanted = size < kIoBufferSize ? size : kIoBufferSize;
        const jint count = env->CallIntMethod(source_, readAt_, static_cast<jlong>(position_), chunk_, 0, wanted);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            return AVERROR(EIO);
        }
        if (count <= 0) return AVERROR_EOF;
        env->GetByteArrayRegion(chunk_, 0, count, reinterpret_cast<jbyte*>(into));
        position_ += count;
        return count;
    }

    int64_t seek(int64_t offset, int whence) override {
        if (whence == AVSEEK_SIZE) return length_;
        const int64_t target = resolve(offset, whence, length_);
        if (target < 0) return -1;
        position_ = target;
        return target;
    }

private:
    jobject source_ = nullptr;
    jmethodID readAt_ = nullptr;
    jmethodID getSize_ = nullptr;
    jbyteArray chunk_ = nullptr;
    int64_t length_ = -1;
};

int readPacket(void* opaque, uint8_t* into, int size) {
    return static_cast<MediaInput::Reader*>(opaque)->read(into, size);
}

int64_t seekTo(void* opaque, int64_t offset, int whence) {
    return static_cast<MediaInput::Reader*>(opaque)->seek(offset, whence & ~AVSEEK_FORCE);
}

int interrupted(void* opaque) {
    return static_cast<MediaInput*>(opaque)->timedOut() ? 1 : 0;
}

}  // namespace

MediaInput* MediaInput::fromFd(int fd, int64_t timeoutMs, std::string* error) {
    return open(new FdReader(fd), timeoutMs, error);
}

MediaInput* MediaInput::fromSource(JNIEnv* env, jobject source, int64_t timeoutMs, std::string* error) {
    return open(new SourceReader(env, source), timeoutMs, error);
}

MediaInput* MediaInput::open(Reader* reader, int64_t timeoutMs, std::string* error) {
    auto* input = new MediaInput();
    input->reader_ = reader;
    input->deadlineUs_ = timeoutMs > 0 ? av_gettime_relative() + timeoutMs * 1000 : 0;

    auto* buffer = static_cast<uint8_t*>(av_malloc(kIoBufferSize));
    input->io_ = buffer != nullptr ? avio_alloc_context(buffer, kIoBufferSize, 0, reader, readPacket, nullptr, seekTo) : nullptr;
    input->format_ = avformat_alloc_context();
    if (input->io_ == nullptr || input->format_ == nullptr) {
        if (input->io_ == nullptr) av_free(buffer);
        *error = "Out of memory";
        delete input;
        return nullptr;
    }
    input->format_->pb = input->io_;
    input->format_->flags |= AVFMT_FLAG_CUSTOM_IO;
    input->format_->interrupt_callback = AVIOInterruptCB{interrupted, input};

    // avformat_open_input frees the context on failure; the I/O stays ours.
    int result = avformat_open_input(&input->format_, nullptr, nullptr, nullptr);
    if (result >= 0) result = avformat_find_stream_info(input->format_, nullptr);
    if (result < 0) {
        *error = input->timedOut() ? "Timed out reading the media" : describeError(result);
        delete input;
        return nullptr;
    }
    return input;
}

bool MediaInput::timedOut() const { return deadlineUs_ > 0 && av_gettime_relative() > deadlineUs_; }

MediaInput::~MediaInput() {
    avformat_close_input(&format_);
    if (io_ != nullptr) {
        av_freep(&io_->buffer);
        avio_context_free(&io_);
    }
    delete reader_;
}

}  // namespace playmedia
