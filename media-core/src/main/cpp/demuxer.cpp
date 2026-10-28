// Container demuxing for io.github.playme.media.extractor.FfmpegExtractor: FFmpeg reads the file
// (through MediaInput, from a file descriptor) and hands over one packet at a time. Everything
// here runs on Media3's loading thread.

#include <cmath>
#include <cstring>
#include <memory>
#include <vector>

#include "media_input.h"

extern "C" {
#include <libavutil/display.h>
#include <libavutil/pixdesc.h>
}

namespace {

using playmedia::MediaInput;

struct Demuxer {
    std::unique_ptr<MediaInput> input;
    AVPacket* packet = nullptr;
    // Container start time, subtracted so that the first sample is at about 0.
    int64_t startUs = 0;
    // Per stream: bytes in each NAL unit's length prefix (MP4/Matroska H.264 and HEVC), or 0
    // when packets are already Annex-B and copy as they are.
    std::vector<int> nalLengthSizes;
};

int64_t toMicros(int64_t value, AVRational timeBase) {
    return value == AV_NOPTS_VALUE ? AV_NOPTS_VALUE : av_rescale_q(value, timeBase, AVRational{1, 1000000});
}

jstring stringOrNull(JNIEnv* env, const char* value) {
    return value != nullptr && value[0] != '\0' ? env->NewStringUTF(value) : nullptr;
}

const char* tag(AVDictionary* metadata, const char* key) {
    AVDictionaryEntry* entry = av_dict_get(metadata, key, nullptr, 0);
    return entry != nullptr ? entry->value : nullptr;
}

int rotationOf(const AVCodecParameters* parameters) {
    const AVPacketSideData* matrix = av_packet_side_data_get(
        parameters->coded_side_data, parameters->nb_coded_side_data, AV_PKT_DATA_DISPLAYMATRIX);
    if (matrix == nullptr || matrix->size < 9 * sizeof(int32_t)) return 0;
    const double degrees = -av_display_rotation_get(reinterpret_cast<const int32_t*>(matrix->data));
    if (std::isnan(degrees)) return 0;
    const int rotation = static_cast<int>(std::lround(degrees)) % 360;
    return rotation < 0 ? rotation + 360 : rotation;
}

// Luma bit depth of a video stream; 0 when not known.
int bitDepthOf(const AVCodecParameters* parameters) {
    if (parameters->codec_type != AVMEDIA_TYPE_VIDEO) return 0;
    const AVPixFmtDescriptor* descriptor = av_pix_fmt_desc_get(static_cast<AVPixelFormat>(parameters->format));
    return descriptor != nullptr ? descriptor->comp[0].depth : 0;
}

// Size of a packet once its NAL length prefixes become 4-byte start codes.
int annexBSize(const uint8_t* data, int size, int lengthSize) {
    int out = 0;
    int at = 0;
    while (at + lengthSize <= size) {
        uint32_t length = 0;
        for (int i = 0; i < lengthSize; i++) length = (length << 8) | data[at + i];
        at += lengthSize;
        if (length > static_cast<uint32_t>(size - at)) return -1;
        out += 4 + static_cast<int>(length);
        at += static_cast<int>(length);
    }
    return out;
}

void toAnnexB(const uint8_t* data, int size, int lengthSize, uint8_t* out) {
    int at = 0;
    while (at + lengthSize <= size) {
        uint32_t length = 0;
        for (int i = 0; i < lengthSize; i++) length = (length << 8) | data[at + i];
        at += lengthSize;
        out[0] = 0;
        out[1] = 0;
        out[2] = 0;
        out[3] = 1;
        memcpy(out + 4, data + at, length);
        out += 4 + length;
        at += static_cast<int>(length);
    }
}

}  // namespace

extern "C" {

// Opens [fd] (not taken over). 0 when FFmpeg can't read it.
JNIEXPORT jlong JNICALL
Java_io_github_playme_media_extractor_DemuxerNative_open(JNIEnv*, jclass, jint fd) {
    std::string error;
    MediaInput* input = MediaInput::fromFd(fd, 0, &error);
    if (input == nullptr) {
        LOGW("FFmpeg can't open the container: %s", error.c_str());
        return 0;
    }
    auto* demuxer = new Demuxer();
    demuxer->input.reset(input);
    demuxer->packet = av_packet_alloc();
    const AVFormatContext* format = input->format();
    demuxer->startUs = format->start_time != AV_NOPTS_VALUE ? format->start_time : 0;
    demuxer->nalLengthSizes.assign(format->nb_streams, 0);
    return reinterpret_cast<jlong>(demuxer);
}

JNIEXPORT jstring JNICALL
Java_io_github_playme_media_extractor_DemuxerNative_formatName(JNIEnv* env, jclass, jlong handle) {
    const AVFormatContext* format = reinterpret_cast<Demuxer*>(handle)->input->format();
    return env->NewStringUTF(format->iformat != nullptr ? format->iformat->name : "unknown");
}

JNIEXPORT jlong JNICALL
Java_io_github_playme_media_extractor_DemuxerNative_durationUs(JNIEnv*, jclass, jlong handle) {
    const AVFormatContext* format = reinterpret_cast<Demuxer*>(handle)->input->format();
    return format->duration != AV_NOPTS_VALUE ? format->duration : -1;
}

JNIEXPORT jint JNICALL
Java_io_github_playme_media_extractor_DemuxerNative_streamCount(JNIEnv*, jclass, jlong handle) {
    return static_cast<jint>(reinterpret_cast<Demuxer*>(handle)->input->format()->nb_streams);
}

// Stream [index] as an io.github.playme.media.extractor.DemuxedStream.
JNIEXPORT jobject JNICALL
Java_io_github_playme_media_extractor_DemuxerNative_stream(JNIEnv* env, jclass, jlong handle, jint index) {
    const AVStream* stream = reinterpret_cast<Demuxer*>(handle)->input->format()->streams[index];
    const AVCodecParameters* parameters = stream->codecpar;
    const char* type;
    switch (parameters->codec_type) {
        case AVMEDIA_TYPE_VIDEO: type = (stream->disposition & AV_DISPOSITION_ATTACHED_PIC) ? "picture" : "video"; break;
        case AVMEDIA_TYPE_AUDIO: type = "audio"; break;
        case AVMEDIA_TYPE_SUBTITLE: type = "subtitle"; break;
        default: type = "other"; break;
    }
    const AVRational rate = stream->avg_frame_rate.den > 0 ? stream->avg_frame_rate : stream->r_frame_rate;
    jbyteArray extraData = nullptr;
    if (parameters->extradata_size > 0) {
        extraData = env->NewByteArray(parameters->extradata_size);
        env->SetByteArrayRegion(extraData, 0, parameters->extradata_size, reinterpret_cast<const jbyte*>(parameters->extradata));
    }
    jclass streamType = env->FindClass("io/github/playme/media/extractor/DemuxedStream");
    jmethodID create = env->GetMethodID(streamType, "<init>",
        "(ILjava/lang/String;Ljava/lang/String;IIFIFIIIJIIIIILjava/lang/String;Ljava/lang/String;I[B)V");
    jstring jType = env->NewStringUTF(type);
    jstring codec = env->NewStringUTF(avcodec_get_name(parameters->codec_id));
    jstring language = stringOrNull(env, tag(stream->metadata, "language"));
    jstring title = stringOrNull(env, tag(stream->metadata, "title"));
    const AVRational aspect = parameters->sample_aspect_ratio;
    const jfloat pixelRatio = aspect.num > 0 && aspect.den > 0 ? static_cast<jfloat>(av_q2d(aspect)) : 1.0f;
    jobject result = env->NewObject(streamType, create,
        index, jType, codec, parameters->width, parameters->height,
        rate.den > 0 ? static_cast<jfloat>(av_q2d(rate)) : -1.0f, rotationOf(parameters), pixelRatio,
        parameters->sample_rate, parameters->ch_layout.nb_channels, parameters->bits_per_coded_sample,
        static_cast<jlong>(parameters->bit_rate), static_cast<jint>(parameters->color_primaries),
        static_cast<jint>(parameters->color_trc), static_cast<jint>(parameters->color_space),
        static_cast<jint>(parameters->color_range), bitDepthOf(parameters), language, title,
        static_cast<jint>(stream->disposition), extraData);
    env->DeleteLocalRef(jType);
    env->DeleteLocalRef(codec);
    if (language) env->DeleteLocalRef(language);
    if (title) env->DeleteLocalRef(title);
    if (extraData) env->DeleteLocalRef(extraData);
    return result;
}

// Packets of [index] carry NAL units with [lengthSize]-byte length prefixes, to copy out as Annex-B.
JNIEXPORT void JNICALL
Java_io_github_playme_media_extractor_DemuxerNative_setNalLengthSize(JNIEnv*, jclass, jlong handle, jint index, jint lengthSize) {
    auto* demuxer = reinterpret_cast<Demuxer*>(handle);
    if (index >= 0 && index < static_cast<jint>(demuxer->nalLengthSizes.size())) demuxer->nalLengthSizes[index] = lengthSize;
}

// Reads the next packet. Returns the size it copies out as (see copyPacket), -1 at the end,
// or another negative FFmpeg error. [info]: stream index, time in µs, key frame (1/0), byte
// position in the file (-1 when unknown).
JNIEXPORT jint JNICALL
Java_io_github_playme_media_extractor_DemuxerNative_nextPacket(JNIEnv* env, jclass, jlong handle, jlongArray info) {
    auto* demuxer = reinterpret_cast<Demuxer*>(handle);
    AVFormatContext* format = demuxer->input->format();
    AVPacket* packet = demuxer->packet;
    const AVStream* stream = nullptr;
    int64_t timeUs = AV_NOPTS_VALUE;
    while (true) {
        av_packet_unref(packet);
        const int result = av_read_frame(format, packet);
        if (result == AVERROR_EOF) return -1;
        if (result < 0) return result == -1 ? AVERROR(EIO) : result;
        stream = format->streams[packet->stream_index];
        timeUs = toMicros(packet->pts != AV_NOPTS_VALUE ? packet->pts : packet->dts, stream->time_base);
        // The file's edit list cuts this packet out, as Media3's own MP4 reading would.
        if ((packet->flags & AV_PKT_FLAG_DISCARD) == 0) break;
    }
    if (timeUs != AV_NOPTS_VALUE) timeUs -= demuxer->startUs;
    const jlong values[] = {packet->stream_index, timeUs, (packet->flags & AV_PKT_FLAG_KEY) ? 1 : 0, packet->pos};
    env->SetLongArrayRegion(info, 0, 4, values);

    const int lengthSize = demuxer->nalLengthSizes[packet->stream_index];
    if (lengthSize <= 0) return packet->size;
    const int converted = annexBSize(packet->data, packet->size, lengthSize);
    // Not length-prefixed after all (some files mix in Annex-B): copy as it is.
    if (converted < 0) demuxer->nalLengthSizes[packet->stream_index] = 0;
    return converted < 0 ? packet->size : converted;
}

// Copies the packet nextPacket read into [target], which holds at least its returned size.
JNIEXPORT void JNICALL
Java_io_github_playme_media_extractor_DemuxerNative_copyPacket(JNIEnv* env, jclass, jlong handle, jbyteArray target) {
    auto* demuxer = reinterpret_cast<Demuxer*>(handle);
    const AVPacket* packet = demuxer->packet;
    auto* out = static_cast<uint8_t*>(env->GetPrimitiveArrayCritical(target, nullptr));
    if (out == nullptr) return;
    const int lengthSize = demuxer->nalLengthSizes[packet->stream_index];
    if (lengthSize > 0) {
        toAnnexB(packet->data, packet->size, lengthSize, out);
    } else {
        memcpy(out, packet->data, packet->size);
    }
    env->ReleasePrimitiveArrayCritical(target, out, 0);
}

// Moves to the key frame at or before [timeUs].
JNIEXPORT jboolean JNICALL
Java_io_github_playme_media_extractor_DemuxerNative_seek(JNIEnv*, jclass, jlong handle, jlong timeUs) {
    auto* demuxer = reinterpret_cast<Demuxer*>(handle);
    AVFormatContext* format = demuxer->input->format();
    const int64_t target = timeUs + demuxer->startUs;
    int result = avformat_seek_file(format, -1, INT64_MIN, target, target, AVSEEK_FLAG_BACKWARD);
    if (result < 0) result = av_seek_frame(format, -1, target, AVSEEK_FLAG_BACKWARD);
    return result >= 0;
}

// Where the key frame at or before [timeUs] of [index] is: {time in µs, byte position}, from the
// container's index; null when there is no index.
JNIEXPORT jlongArray JNICALL
Java_io_github_playme_media_extractor_DemuxerNative_seekPoint(JNIEnv* env, jclass, jlong handle, jint index, jlong timeUs) {
    auto* demuxer = reinterpret_cast<Demuxer*>(handle);
    AVStream* stream = demuxer->input->format()->streams[index];
    const int64_t target = av_rescale_q(timeUs + demuxer->startUs, AVRational{1, 1000000}, stream->time_base);
    const AVIndexEntry* entry = avformat_index_get_entry_from_timestamp(stream, target, AVSEEK_FLAG_BACKWARD);
    if (entry == nullptr) entry = avformat_index_get_entry_from_timestamp(stream, target, 0);
    if (entry == nullptr || entry->pos < 0) return nullptr;
    const jlong values[] = {toMicros(entry->timestamp, stream->time_base) - demuxer->startUs, entry->pos};
    jlongArray point = env->NewLongArray(2);
    env->SetLongArrayRegion(point, 0, 2, values);
    return point;
}

JNIEXPORT void JNICALL
Java_io_github_playme_media_extractor_DemuxerNative_close(JNIEnv*, jclass, jlong handle) {
    auto* demuxer = reinterpret_cast<Demuxer*>(handle);
    av_packet_free(&demuxer->packet);
    delete demuxer;
}

}  // extern "C"
