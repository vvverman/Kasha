#import <AVFoundation/AVFoundation.h>
#include "whisper.h"
#include <algorithm>
#include <memory>
#include <mutex>
#include <string>
#include <vector>
#include <stdexcept>
#include <cstring>
#include <limits>

using Cancel = int (*)(void *);
struct Cancellation {
    Cancel callback; void *data;
    static bool aborted(void *value) {
        auto *s = static_cast<Cancellation *>(value);
        return s->callback && s->callback(s->data) != 0;
    }
    void check() { if (aborted(this)) throw std::runtime_error("operationCancelled"); }
};

// AVAudioConverter читает исходный файл. Плеер, микрофон и пользовательское аудио не изменяются.
static std::vector<float> decode(const char *path, Cancellation &cancel) {
    NSError *error = nil;
    AVAudioFile *file = [[AVAudioFile alloc] initForReading:[NSURL fileURLWithPath:[NSString stringWithUTF8String:path]]
        commonFormat:AVAudioPCMFormatFloat32 interleaved:NO error:&error];
    if (!file || error || file.length <= 0) throw std::runtime_error("audioOpenFailed");
    AVAudioFormat *format = [[AVAudioFormat alloc] initWithCommonFormat:AVAudioPCMFormatFloat32
        sampleRate:16000 channels:1 interleaved:NO];
    std::vector<float> samples;
    auto append = [&](AVAudioPCMBuffer *buffer) {
        if (samples.size() + buffer.frameLength > static_cast<size_t>(std::numeric_limits<int>::max()))
            throw std::runtime_error("audioTooLarge");
        if (buffer.frameLength) {
            if (!buffer.floatChannelData) throw std::runtime_error("audioReadFailed");
            samples.insert(samples.end(), buffer.floatChannelData[0], buffer.floatChannelData[0] + buffer.frameLength);
        }
    };
    // Для уже подходящего PCM не нужен преобразователь частоты/каналов.
    if (file.processingFormat.sampleRate == 16000 && file.processingFormat.channelCount == 1) {
        AVAudioPCMBuffer *buffer = [[AVAudioPCMBuffer alloc] initWithPCMFormat:file.processingFormat frameCapacity:4096];
        while (file.framePosition < file.length) {
            cancel.check(); error = nil;
            const auto count = static_cast<AVAudioFrameCount>(std::min<AVAudioFramePosition>(4096, file.length - file.framePosition));
            if (![file readIntoBuffer:buffer frameCount:count error:&error] || error || !buffer.frameLength)
                throw std::runtime_error("audioReadFailed");
            append(buffer);
        }
    } else {
        AVAudioConverter *converter = [[AVAudioConverter alloc] initFromFormat:file.processingFormat toFormat:format];
        if (!converter) throw std::runtime_error("audioConversionUnavailable");
        AVAudioPCMBuffer *output = [[AVAudioPCMBuffer alloc] initWithPCMFormat:format frameCapacity:4096];
        __block AVAudioPCMBuffer *input = nil;
        __block bool inputFailed = false;
        __block bool inputEnded = false;
        Cancellation *cancellation = &cancel;
        unsigned emptyReads = 0;
        for (;;) {
            cancel.check(); error = nil; output.frameLength = 0;
            auto result = [converter convertToBuffer:output error:&error
                withInputFromBlock:^AVAudioBuffer *(AVAudioPacketCount requested, AVAudioConverterInputStatus *state) {
                    if (Cancellation::aborted(cancellation)) { *state = AVAudioConverterInputStatus_EndOfStream; return nil; }
                    const AVAudioFramePosition remaining = file.length - file.framePosition;
                    if (remaining <= 0) { inputEnded = true; *state = AVAudioConverterInputStatus_EndOfStream; return nil; }
                    if (requested == 0) { *state = AVAudioConverterInputStatus_NoDataNow; return nil; }
                    const auto count = static_cast<AVAudioFrameCount>(std::min<AVAudioFramePosition>(requested, remaining));
                    input = [[AVAudioPCMBuffer alloc] initWithPCMFormat:file.processingFormat frameCapacity:count];
                    NSError *readError = nil;
                    if (![file readIntoBuffer:input frameCount:count error:&readError] || readError || !input.frameLength) {
                        inputFailed = true; *state = AVAudioConverterInputStatus_EndOfStream; return nil;
                    }
                    *state = AVAudioConverterInputStatus_HaveData;
                    return input;
                }];
            cancel.check();
            if (inputFailed) throw std::runtime_error("audioReadFailed");
            if (error || result == AVAudioConverterOutputStatus_Error) throw std::runtime_error("audioConversionFailed");
            append(output);
            if (result == AVAudioConverterOutputStatus_EndOfStream) break;
            if (!output.frameLength) {
                if (inputEnded) break;
                if (++emptyReads > 4) throw std::runtime_error("audioConversionStalled");
            } else emptyReads = 0;
        }
    }
    if (samples.empty()) throw std::runtime_error("audioReadFailed");
    return samples;
}

extern "C" __attribute__((visibility("default"))) char *kasha_whisper_run(
    const char *model, const char *source, const char *language, int threads,
    Cancel callback, void *data, int *status) {
    @autoreleasepool {
        try {
            *status = 1;
            Cancellation cancel{callback, data}; cancel.check();
            if (!model || !source || !language || whisper_lang_id(language) < 0)
                throw std::runtime_error("languageUnsupported");
            static std::once_flag initialized;
            std::call_once(initialized, [] { whisper_log_set([](ggml_log_level, const char *, void *) {}, nullptr); });
            auto samples = decode(source, cancel);
            auto cp = whisper_context_default_params(); cp.use_gpu = false;
            std::unique_ptr<whisper_context, decltype(&whisper_free)> context(
                whisper_init_from_file_with_params(model, cp), whisper_free);
            cancel.check();
            if (!context) throw std::runtime_error("aiUnavailable");
            auto p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
            p.n_threads = std::clamp(threads, 1, 4); p.language = language;
            p.translate = false; p.no_context = true;
            p.print_realtime = p.print_progress = p.print_timestamps = p.print_special = false;
            p.abort_callback = Cancellation::aborted; p.abort_callback_user_data = &cancel;
            const int code = whisper_full(context.get(), p, samples.data(), static_cast<int>(samples.size()));
            cancel.check();
            if (code) throw std::runtime_error("speechRecognitionFailed");
            std::string result;
            for (int i = 0; i < whisper_full_n_segments(context.get()); ++i)
                result += whisper_full_get_segment_text(context.get(), i);
            if (result.empty()) throw std::runtime_error("emptyTranscription");
            char *text = strdup(result.c_str());
            if (!text) throw std::bad_alloc();
            *status = 0; return text;
        } catch (const std::exception &error) { return strdup(error.what()); }
    }
}
