#include <jni.h>
#include <algorithm>
#include <memory>
#include <string>
#include "whisper.h"
#include "pcm_source.h"

class Utf {
    JNIEnv *env; jstring value; const char *bytes;
public:
    Utf(JNIEnv *env, jstring value): env(env), value(value), bytes(value ? env->GetStringUTFChars(value, nullptr) : nullptr) {
        if (!bytes) throw std::runtime_error("audioFailed");
    }
    ~Utf() { env->ReleaseStringUTFChars(value, bytes); }
    const char *get() const { return bytes; }
};

struct Cancellation {
    JavaVM *vm; jobject object; jmethodID method;
    static bool check(void *data) {
        auto *self = static_cast<Cancellation *>(data);
        JNIEnv *env = nullptr;
        bool attached = false;
        if (self->vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
#ifdef __ANDROID__
            if (self->vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return true;
#else
            if (self->vm->AttachCurrentThread(reinterpret_cast<void **>(&env), nullptr) != JNI_OK) return true;
#endif
            attached = true;
        }
        const bool result = env->CallBooleanMethod(self->object, self->method) == JNI_TRUE;
        const bool failed = env->ExceptionCheck();
        if (failed) env->ExceptionClear();
        if (attached) self->vm->DetachCurrentThread();
        return result || failed;
    }
};

extern "C" JNIEXPORT jbyteArray JNICALL
Java_ru_vrmn_kasha_android_AndroidWhisperNative_transcribe(JNIEnv *env, jobject,
    jstring model, jstring source, jint rate, jint channels, jstring language, jint threads, jobject signal) {
    jobject global = nullptr;
    try {
        Utf modelPath(env, model), sourcePath(env, source), lang(env, language);
        if (whisper_lang_id(lang.get()) < 0) throw std::runtime_error("onDeviceSpeechUnavailable");
        global = env->NewGlobalRef(signal);
        if (!global) throw std::bad_alloc();
        const jclass type = env->GetObjectClass(signal);
        const jmethodID method = env->GetMethodID(type, "isCancelled", "()Z");
        env->DeleteLocalRef(type);
        if (!method) throw std::runtime_error("audioFailed");
        JavaVM *vm = nullptr; env->GetJavaVM(&vm);
        Cancellation cancellation{vm, global, method};
        auto samples = kasha_pcm(sourcePath.get(), rate, channels, Cancellation::check, &cancellation);
        auto contextParams = whisper_context_default_params();
        contextParams.use_gpu = false;
        std::unique_ptr<whisper_context, decltype(&whisper_free)> context(
            whisper_init_from_file_with_params(modelPath.get(), contextParams), whisper_free);
        if (!context) throw std::runtime_error("androidAiNotConfigured");
        if (Cancellation::check(&cancellation)) throw std::runtime_error("operationCancelled");
        auto params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
        params.n_threads = std::max(1, std::min(threads, 4));
        params.language = lang.get();
        params.translate = false;
        params.no_context = true;
        params.print_realtime = false; params.print_progress = false;
        params.print_timestamps = false; params.print_special = false;
        params.abort_callback = Cancellation::check;
        params.abort_callback_user_data = &cancellation;
        const int status = whisper_full(context.get(), params, samples.data(), static_cast<int>(samples.size()));
        if (Cancellation::check(&cancellation)) throw std::runtime_error("operationCancelled");
        if (status != 0) throw std::runtime_error("speechRecognitionFailed");
        std::string text;
        for (int i = 0; i < whisper_full_n_segments(context.get()); ++i) text += whisper_full_get_segment_text(context.get(), i);
        if (text.size() > static_cast<size_t>(std::numeric_limits<jsize>::max())) throw std::runtime_error("audioFailed");
        const auto result = env->NewByteArray(static_cast<jsize>(text.size()));
        if (result) env->SetByteArrayRegion(result, 0, static_cast<jsize>(text.size()), reinterpret_cast<const jbyte *>(text.data()));
        env->DeleteGlobalRef(global);
        return result; // UTF-8 bytes, not JNI's modified UTF-8: Cyrillic/emoji are preserved.
    } catch (const std::exception &error) {
        if (global) env->DeleteGlobalRef(global);
        if (!env->ExceptionCheck()) {
            const auto type = env->FindClass("java/lang/IllegalStateException");
            if (type) env->ThrowNew(type, error.what());
        }
        return nullptr;
    }
}
