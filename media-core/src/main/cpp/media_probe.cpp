// Container facts and frames for io.github.playme.media.info: MediaInfoNativeBridge builds
// MediaInfo objects and thumbnails from a file descriptor or a MediaDataSource.

#include <android/bitmap.h>

#include <algorithm>

#include <cmath>
#include <memory>

#include "media_input.h"

extern "C" {
#include <libavutil/display.h>
#include <libavutil/pixdesc.h>
#include <libswscale/swscale.h>
}

namespace {

using playmedia::MediaInput;

constexpr char kInfoPackage[] = "io/github/playme/media/info/";

std::unique_ptr<MediaInput> openInput(JNIEnv* env, jint fd, jobject source, jlong timeoutMs) {
    std::string error;
    MediaInput* input = source != nullptr ? MediaInput::fromSource(env, source, timeoutMs, &error)
                                          : MediaInput::fromFd(fd, timeoutMs, &error);
    if (input == nullptr) LOGW("Can't open media: %s", error.c_str());
    return std::unique_ptr<MediaInput>(input);
}

jclass infoClass(JNIEnv* env, const char* name) {
    return env->FindClass((std::string(kInfoPackage) + name).c_str());
}

jstring stringOrNull(JNIEnv* env, const char* value) {
    return value != nullptr && value[0] != '\0' ? env->NewStringUTF(value) : nullptr;
}

const char* tag(AVDictionary* metadata, const char* key) {
    AVDictionaryEntry* entry = av_dict_get(metadata, key, nullptr, 0);
    return entry != nullptr ? entry->value : nullptr;
}

// Degrees clockwise the picture must turn to be upright.
int rotationOf(const AVStream* stream) {
    const AVCodecParameters* parameters = stream->codecpar;
    const AVPacketSideData* matrix = av_packet_side_data_get(
        parameters->coded_side_data, parameters->nb_coded_side_data, AV_PKT_DATA_DISPLAYMATRIX);
    if (matrix == nullptr || matrix->size < 9 * sizeof(int32_t)) return 0;
    const double degrees = -av_display_rotation_get(reinterpret_cast<const int32_t*>(matrix->data));
    if (std::isnan(degrees)) return 0;
    int rotation = static_cast<int>(std::lround(degrees)) % 360;
    return rotation < 0 ? rotation + 360 : rotation;
}

const char* hdrTypeOf(const AVCodecParameters* parameters) {
    if (av_packet_side_data_get(parameters->coded_side_data, parameters->nb_coded_side_data, AV_PKT_DATA_DOVI_CONF) != nullptr) {
        return "DOLBY_VISION";
    }
    if (parameters->color_trc == AVCOL_TRC_SMPTE2084) return "HDR10";
    if (parameters->color_trc == AVCOL_TRC_ARIB_STD_B67) return "HLG";
    return "NONE";
}

int bitDepthOf(const AVCodecParameters* parameters) {
    const AVPixFmtDescriptor* descriptor = av_pix_fmt_desc_get(static_cast<AVPixelFormat>(parameters->format));
    if (descriptor != nullptr) return descriptor->comp[0].depth;
    return parameters->bits_per_raw_sample > 0 ? parameters->bits_per_raw_sample : 8;
}

jbyteArray bytesOf(JNIEnv* env, const uint8_t* data, int size) {
    if (data == nullptr || size <= 0) return nullptr;
    jbyteArray bytes = env->NewByteArray(size);
    if (bytes != nullptr) env->SetByteArrayRegion(bytes, 0, size, reinterpret_cast<const jbyte*>(data));
    return bytes;
}

const AVPacket* attachedPicture(const AVFormatContext* format) {
    for (unsigned i = 0; i < format->nb_streams; i++) {
        const AVStream* stream = format->streams[i];
        if ((stream->disposition & AV_DISPOSITION_ATTACHED_PIC) && stream->attached_pic.size > 0) return &stream->attached_pic;
    }
    return nullptr;
}

jobject describe(JNIEnv* env, const AVFormatContext* format, jstring path) {
    jclass listType = env->FindClass("java/util/ArrayList");
    jmethodID newList = env->GetMethodID(listType, "<init>", "()V");
    jmethodID add = env->GetMethodID(listType, "add", "(Ljava/lang/Object;)Z");
    jobject videos = env->NewObject(listType, newList);
    jobject audios = env->NewObject(listType, newList);
    jobject subtitles = env->NewObject(listType, newList);
    jobject chapters = env->NewObject(listType, newList);
    jobject attachments = env->NewObject(listType, newList);

    jclass videoType = infoClass(env, "VideoStream");
    jmethodID newVideo = env->GetMethodID(videoType, "<init>",
        "(ILjava/lang/String;IIJFIILio/github/playme/media/info/HdrType;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Z)V");
    jclass audioType = infoClass(env, "AudioStream");
    jmethodID newAudio = env->GetMethodID(audioType, "<init>", "(ILjava/lang/String;IIJLjava/lang/String;Ljava/lang/String;Z)V");
    jclass subtitleType = infoClass(env, "SubtitleStream");
    jmethodID newSubtitle = env->GetMethodID(subtitleType, "<init>", "(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;ZZ)V");
    jclass attachmentType = infoClass(env, "MediaAttachment");
    jmethodID newAttachment = env->GetMethodID(attachmentType, "<init>", "(JLjava/lang/String;Ljava/lang/String;J[B)V");
    jclass hdrType = infoClass(env, "HdrType");

    for (unsigned i = 0; i < format->nb_streams; i++) {
        const AVStream* stream = format->streams[i];
        const AVCodecParameters* parameters = stream->codecpar;
        if (stream->disposition & AV_DISPOSITION_ATTACHED_PIC) continue;
        const jint index = static_cast<jint>(i);
        const jboolean isDefault = (stream->disposition & AV_DISPOSITION_DEFAULT) != 0;
        jstring codec = env->NewStringUTF(avcodec_get_name(parameters->codec_id));
        jstring language = stringOrNull(env, tag(stream->metadata, "language"));
        jstring title = stringOrNull(env, tag(stream->metadata, "title"));
        jobject item = nullptr;

        switch (parameters->codec_type) {
            case AVMEDIA_TYPE_VIDEO: {
                const AVRational rate = stream->avg_frame_rate.den > 0 ? stream->avg_frame_rate : stream->r_frame_rate;
                const jfloat fps = rate.den > 0 ? static_cast<jfloat>(av_q2d(rate)) : 0.0f;
                jfieldID hdrField = env->GetStaticFieldID(hdrType, hdrTypeOf(parameters), "Lio/github/playme/media/info/HdrType;");
                jobject hdr = env->GetStaticObjectField(hdrType, hdrField);
                jstring space = stringOrNull(env, av_color_space_name(parameters->color_space));
                jstring transfer = stringOrNull(env, av_color_transfer_name(parameters->color_trc));
                jstring primaries = stringOrNull(env, av_color_primaries_name(parameters->color_primaries));
                item = env->NewObject(videoType, newVideo, index, codec, parameters->width, parameters->height,
                    static_cast<jlong>(parameters->bit_rate), fps, rotationOf(stream), bitDepthOf(parameters),
                    hdr, space, transfer, primaries, isDefault);
                env->CallBooleanMethod(videos, add, item);
                env->DeleteLocalRef(hdr);
                if (space) env->DeleteLocalRef(space);
                if (transfer) env->DeleteLocalRef(transfer);
                if (primaries) env->DeleteLocalRef(primaries);
                break;
            }
            case AVMEDIA_TYPE_AUDIO:
                item = env->NewObject(audioType, newAudio, index, codec, parameters->sample_rate,
                    parameters->ch_layout.nb_channels, static_cast<jlong>(parameters->bit_rate), language, title, isDefault);
                env->CallBooleanMethod(audios, add, item);
                break;
            case AVMEDIA_TYPE_SUBTITLE:
                item = env->NewObject(subtitleType, newSubtitle, index, codec, language, title, isDefault,
                    static_cast<jboolean>((stream->disposition & AV_DISPOSITION_FORCED) != 0));
                env->CallBooleanMethod(subtitles, add, item);
                break;
            case AVMEDIA_TYPE_ATTACHMENT: {
                // Matroska attachments, usually fonts for ASS subtitles: the file is the extradata.
                const char* fileName = tag(stream->metadata, "filename");
                const char* mime = tag(stream->metadata, "mimetype");
                jstring name = env->NewStringUTF(fileName != nullptr ? fileName : "attachment");
                jstring type = env->NewStringUTF(mime != nullptr ? mime : "application/octet-stream");
                jbyteArray data = bytesOf(env, parameters->extradata, parameters->extradata_size);
                item = env->NewObject(attachmentType, newAttachment, static_cast<jlong>(index), name, type,
                    static_cast<jlong>(parameters->extradata_size), data);
                env->CallBooleanMethod(attachments, add, item);
                env->DeleteLocalRef(name);
                env->DeleteLocalRef(type);
                if (data) env->DeleteLocalRef(data);
                break;
            }
            default:
                break;
        }
        if (item) env->DeleteLocalRef(item);
        env->DeleteLocalRef(codec);
        if (language) env->DeleteLocalRef(language);
        if (title) env->DeleteLocalRef(title);
    }

    jclass chapterType = infoClass(env, "Chapter");
    jmethodID newChapter = env->GetMethodID(chapterType, "<init>", "(JJJLjava/lang/String;)V");
    for (unsigned i = 0; i < format->nb_chapters; i++) {
        const AVChapter* chapter = format->chapters[i];
        const AVRational millis = {1, 1000};
        jstring title = stringOrNull(env, tag(chapter->metadata, "title"));
        jobject item = env->NewObject(chapterType, newChapter, static_cast<jlong>(chapter->id),
            static_cast<jlong>(av_rescale_q(chapter->start, chapter->time_base, millis)),
            static_cast<jlong>(av_rescale_q(chapter->end, chapter->time_base, millis)), title);
        env->CallBooleanMethod(chapters, add, item);
        env->DeleteLocalRef(item);
        if (title) env->DeleteLocalRef(title);
    }

    const AVPacket* picture = attachedPicture(format);
    jbyteArray cover = picture != nullptr ? bytesOf(env, picture->data, picture->size) : nullptr;
    jstring formatName = env->NewStringUTF(format->iformat != nullptr ? format->iformat->name : "unknown");
    const jlong durationMs = format->duration != AV_NOPTS_VALUE ? format->duration / 1000 : 0;

    jclass infoType = infoClass(env, "MediaInfo");
    jmethodID newInfo = env->GetMethodID(infoType, "<init>",
        "(Ljava/lang/String;Ljava/lang/String;JJLjava/util/List;Ljava/util/List;Ljava/util/List;Ljava/util/List;Ljava/util/List;[B)V");
    return env->NewObject(infoType, newInfo, path, formatName, durationMs, static_cast<jlong>(format->bit_rate),
        videos, audios, subtitles, chapters, attachments, cover);
}

// The size a frame of [width]x[height] (with [aspect] pixel shape) gets inside the bounds,
// keeping its proportions; 0 for a bound means no limit.
void fitInto(int width, int height, AVRational aspect, int maxWidth, int maxHeight, int* outWidth, int* outHeight) {
    double displayWidth = width;
    if (aspect.num > 0 && aspect.den > 0) displayWidth = width * av_q2d(aspect);
    double scale = 1.0;
    if (maxWidth > 0) scale = std::min(scale, maxWidth / displayWidth);
    if (maxHeight > 0) scale = std::min(scale, static_cast<double>(maxHeight) / height);
    if (maxWidth <= 0 && maxHeight <= 0) scale = 1.0;
    *outWidth = std::max(1, static_cast<int>(std::lround(displayWidth * scale)));
    *outHeight = std::max(1, static_cast<int>(std::lround(height * scale)));
}

jobject toBitmap(JNIEnv* env, const AVFrame* frame, int maxWidth, int maxHeight) {
    int width;
    int height;
    fitInto(frame->width, frame->height, frame->sample_aspect_ratio, maxWidth, maxHeight, &width, &height);

    jclass configType = env->FindClass("android/graphics/Bitmap$Config");
    jfieldID argb = env->GetStaticFieldID(configType, "ARGB_8888", "Landroid/graphics/Bitmap$Config;");
    jobject config = env->GetStaticObjectField(configType, argb);
    jclass bitmapType = env->FindClass("android/graphics/Bitmap");
    jmethodID create = env->GetStaticMethodID(bitmapType, "createBitmap", "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
    jobject bitmap = env->CallStaticObjectMethod(bitmapType, create, width, height, config);
    if (env->ExceptionCheck() || bitmap == nullptr) {
        env->ExceptionClear();
        return nullptr;
    }

    AndroidBitmapInfo info;
    void* pixels = nullptr;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != 0 || AndroidBitmap_lockPixels(env, bitmap, &pixels) != 0) return nullptr;
    SwsContext* scaler = sws_getContext(frame->width, frame->height, static_cast<AVPixelFormat>(frame->format),
        width, height, AV_PIX_FMT_RGBA, SWS_BICUBIC, nullptr, nullptr, nullptr);
    bool drawn = false;
    if (scaler != nullptr) {
        uint8_t* planes[] = {static_cast<uint8_t*>(pixels), nullptr, nullptr, nullptr};
        const int strides[] = {static_cast<int>(info.stride), 0, 0, 0};
        drawn = sws_scale(scaler, frame->data, frame->linesize, 0, frame->height, planes, strides) > 0;
        sws_freeContext(scaler);
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    return drawn ? bitmap : nullptr;
}

// Decodes the frame at [timeUs]: the key frame at or before it, or with [exact], the first frame
// at or after it. Returns null when there is no video or nothing decodes.
jobject frameAt(JNIEnv* env, MediaInput* input, jlong timeUs, jint maxWidth, jint maxHeight, jboolean exact, jintArray rotationOut) {
    AVFormatContext* format = input->format();
    const int index = av_find_best_stream(format, AVMEDIA_TYPE_VIDEO, -1, -1, nullptr, 0);
    if (index < 0 || (format->streams[index]->disposition & AV_DISPOSITION_ATTACHED_PIC)) return nullptr;
    AVStream* stream = format->streams[index];
    const AVCodec* codec = avcodec_find_decoder(stream->codecpar->codec_id);
    if (codec == nullptr) return nullptr;

    AVCodecContext* context = avcodec_alloc_context3(codec);
    AVPacket* packet = av_packet_alloc();
    AVFrame* frame = av_frame_alloc();
    AVFrame* best = av_frame_alloc();
    bool found = false;
    if (context != nullptr && packet != nullptr && frame != nullptr && best != nullptr &&
        avcodec_parameters_to_context(context, stream->codecpar) >= 0) {
        context->pkt_timebase = stream->time_base;
        context->thread_type = FF_THREAD_SLICE;
        context->thread_count = 0;
        if (!exact) context->skip_frame = AVDISCARD_NONKEY;
        if (avcodec_open2(context, codec, nullptr) >= 0) {
            int64_t target = av_rescale_q(timeUs, AVRational{1, 1000000}, stream->time_base);
            if (stream->start_time != AV_NOPTS_VALUE) target += stream->start_time;
            if (timeUs > 0 && av_seek_frame(format, index, target, AVSEEK_FLAG_BACKWARD) < 0) {
                av_seek_frame(format, index, 0, AVSEEK_FLAG_BACKWARD);
            }
            bool ended = false;
            while (!found && !input->timedOut()) {
                int result = 0;
                if (!ended) {
                    result = av_read_frame(format, packet);
                    if (result < 0) {
                        ended = true;
                        avcodec_send_packet(context, nullptr);
                    } else if (packet->stream_index != index) {
                        av_packet_unref(packet);
                        continue;
                    } else {
                        avcodec_send_packet(context, packet);
                        av_packet_unref(packet);
                    }
                }
                while (!found && (result = avcodec_receive_frame(context, frame)) >= 0) {
                    const int64_t pts = frame->best_effort_timestamp;
                    av_frame_unref(best);
                    av_frame_move_ref(best, frame);
                    found = !exact || pts == AV_NOPTS_VALUE || pts >= target;
                }
                // Past the end: settle for the last frame there was.
                if (ended && result == AVERROR_EOF) {
                    found = best->width > 0;
                    break;
                }
            }
        }
    }

    jobject bitmap = found ? toBitmap(env, best, maxWidth, maxHeight) : nullptr;
    if (bitmap != nullptr && rotationOut != nullptr) {
        const jint rotation = rotationOf(stream);
        env->SetIntArrayRegion(rotationOut, 0, 1, &rotation);
    }
    av_frame_free(&best);
    av_frame_free(&frame);
    av_packet_free(&packet);
    avcodec_free_context(&context);
    return bitmap;
}

}  // namespace

extern "C" {

JNIEXPORT jobject JNICALL
Java_io_github_playme_media_info_MediaInfoNativeBridge_probe(
    JNIEnv* env, jclass, jint fd, jobject source, jstring path, jlong timeoutMs) {
    std::unique_ptr<MediaInput> input = openInput(env, fd, source, timeoutMs);
    return input != nullptr ? describe(env, input->format(), path) : nullptr;
}

JNIEXPORT jobject JNICALL
Java_io_github_playme_media_info_MediaInfoNativeBridge_frame(
    JNIEnv* env, jclass, jint fd, jobject source, jlong timeUs, jint maxWidth, jint maxHeight,
    jboolean exact, jlong timeoutMs, jintArray rotationOut) {
    std::unique_ptr<MediaInput> input = openInput(env, fd, source, timeoutMs);
    return input != nullptr ? frameAt(env, input.get(), timeUs, maxWidth, maxHeight, exact, rotationOut) : nullptr;
}

JNIEXPORT jbyteArray JNICALL
Java_io_github_playme_media_info_MediaInfoNativeBridge_embeddedPicture(
    JNIEnv* env, jclass, jint fd, jobject source, jlong timeoutMs) {
    std::unique_ptr<MediaInput> input = openInput(env, fd, source, timeoutMs);
    if (input == nullptr) return nullptr;
    const AVPacket* picture = attachedPicture(input->format());
    return picture != nullptr ? bytesOf(env, picture->data, picture->size) : nullptr;
}

}  // extern "C"
