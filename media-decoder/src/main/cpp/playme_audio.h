#pragma once

#include "playme_common.h"

class PlayMeAudioDecoderNative {
public:
    PlayMeAudioDecoderNative(const std::string& mimeType, int sampleRate, int channelCount);
    ~PlayMeAudioDecoderNative();

    int decodeAudio(const uint8_t* inData, size_t inSize, uint8_t* outData, size_t outCapacity);
    void flush();
    void release();

private:
    std::string mMimeType;
    int mSampleRate;
    int mChannelCount;

#ifndef PLAYME_STUB_FFMPEG
    const AVCodec* mCodec;
    AVCodecContext* mCodecContext;
    AVFrame* mFrame;
    AVPacket* mPacket;
    SwrContext* mSwrContext;
#endif
};

