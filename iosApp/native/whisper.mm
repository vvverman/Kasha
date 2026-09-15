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
    AVAudioFile *file = [[AVAudioFile alloc] initForReading:[NSURL fileURLWithPath:@(path)] error:&error];
    if (!file || error) throw std::runtime_error("audioFailed");
    AVAudioFormat *format = [[AVAudioFormat alloc] initWithCommonFormat:AVAudioPCMFormatFloat32
        sampleRate:16000 channels:1 interleaved:NO];
    AVAudioConverter *converter = [[AVAudioConverter alloc] initFromFormat:file.processingFormat toFormat:format];
    if (!converter) throw std::runtime_error("audioFailed");
    AVAudioPCMBuffer *output = [[AVAudioPCMBuffer alloc] initWithPCMFormat:format frameCapacity:4096];
    __block NSError *inputError = nil;
    __block bool inputFailed = false;
    std::vector<float> samples;
    Cancellation *cancellation = &cancel;
    for (;;) {
        cancel.check();
        auto status = [converter convertToBuffer:output error:&error
            withInputFromBlock:^AVAudioBuffer *(AVAudioPacketCount count, AVAudioConverterInputStatus *state) {
                if (Cancellation::aborted(cancellation)) { inputFailed = true; *state = AVAudioConverterInputStatus_EndOfStream; return nil; }
                AVAudioPCMBuffer *input = [[AVAudioPCMBuffer alloc] initWithPCMFormat:file.processingFormat frameCapacity:count];
                if (![file readIntoBuffer:input frameCount:count error:&inputError]) {
                    inputFailed = true; *state = AVAudioConverterInputStatus_EndOfStream; return nil;
                }
                *state = input.frameLength ? AVAudioConverterInputStatus_HaveData : AVAudioConverterInputStatus_EndOfStream;
                return input.frameLength ? input : nil;
            }];
        cancel.check();
        if (inputFailed || inputError || error || status == AVAudioConverterOutputStatus_Error)
            throw std::runtime_error("audioFailed");
        if (samples.size() + output.frameLength > static_cast<size_t>(std::numeric_limits<int>::max()))
            throw std::runtime_error("audioFailed");
        if (output.frameLength) samples.insert(samples.end(), output.floatChannelData[0], output.floatChannelData[0] + output.frameLength);
        if (status == AVAudioConverterOutputStatus_EndOfStream) break;
        if (status == AVAudioConverterOutputStatus_InputRanDry && output.frameLength == 0)
            throw std::runtime_error("audioFailed");
    }
    if (samples.empty()) throw std::runtime_error("audioFailed");
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
