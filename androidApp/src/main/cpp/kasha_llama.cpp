#include <jni.h>
#include <algorithm>
#include <cmath>
#include <limits>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>
#include "llama.h"

namespace {
struct Signal {
    JavaVM *vm; jobject object; jmethodID method;
    static bool cancelled(void *data) {
        auto *self = static_cast<Signal *>(data);
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
    void check() { if (cancelled(this)) throw std::runtime_error("operationCancelled"); }
};

std::string bytes(JNIEnv *env, jbyteArray input) {
    if (!input) throw std::runtime_error("aiUnavailable");
    const auto size = env->GetArrayLength(input);
    if (size <= 0 || size > 1024 * 1024) throw std::runtime_error("aiUnavailable");
    std::string result(static_cast<size_t>(size), '\0');
    env->GetByteArrayRegion(input, 0, size, reinterpret_cast<jbyte *>(&result[0]));
    if (env->ExceptionCheck()) throw std::runtime_error("aiUnavailable");
    return result;
}

void appendTokens(std::vector<llama_token> &target, const llama_vocab *vocab,
                  const std::string &text, bool special) {
    const int count = -llama_tokenize(vocab, text.data(), static_cast<int>(text.size()), nullptr, 0, false, special);
    if (count <= 0 || count > 8192) throw std::runtime_error("aiUnavailable");
    const auto offset = target.size();
    target.resize(offset + count);
    const int actual = llama_tokenize(vocab, text.data(), static_cast<int>(text.size()),
        target.data() + offset, count, false, special);
    if (actual != count) throw std::runtime_error("aiUnavailable");
}
} // namespace

extern "C" JNIEXPORT jbyteArray JNICALL
Java_ru_vrmn_kasha_android_AndroidLlamaNative_generate(JNIEnv *env, jobject,
    jstring modelPath, jbyteArray promptBytes,
    jint limit, jint threads, jobject signal) {
    jobject global = nullptr;
    try {
        if (!modelPath || !signal || limit <= 0 || limit > 2200) throw std::runtime_error("aiUnavailable");
        const char *pathBytes = env->GetStringUTFChars(modelPath, nullptr);
        if (!pathBytes) throw std::bad_alloc();
        const std::string path(pathBytes);
        env->ReleaseStringUTFChars(modelPath, pathBytes);
        const auto prompt = bytes(env, promptBytes);
        global = env->NewGlobalRef(signal);
        if (!global) throw std::bad_alloc();
        const auto type = env->GetObjectClass(signal);
        const auto method = env->GetMethodID(type, "isCancelled", "()Z");
        env->DeleteLocalRef(type);
        if (!method) throw std::runtime_error("aiUnavailable");
        JavaVM *vm = nullptr;
        env->GetJavaVM(&vm);
        Signal cancellation{vm, global, method};
        cancellation.check();
        static std::once_flag initialized;
        std::call_once(initialized, [] {
            llama_log_set([](ggml_log_level, const char *, void *) {}, nullptr);
            llama_backend_init();
        });
        auto modelParams = llama_model_default_params();
        modelParams.n_gpu_layers = 0;
        modelParams.progress_callback = [](float, void *data) { return !Signal::cancelled(data); };
        modelParams.progress_callback_user_data = &cancellation;
        std::unique_ptr<llama_model, decltype(&llama_model_free)> model(
            llama_model_load_from_file(path.c_str(), modelParams), llama_model_free);
        cancellation.check();
        if (!model) throw std::runtime_error("androidAiNotConfigured");
        const auto *vocab = llama_model_get_vocab(model.get());
        std::vector<llama_token> tokens;
        // Только закреплённые Qwen3 из общего манифеста. Данные не могут вставить ChatML-токены.
        appendTokens(tokens, vocab, "<|im_start|>user\n", true);
        appendTokens(tokens, vocab, prompt, false);
        appendTokens(tokens, vocab, "\n/no_think<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n", true);
        if (tokens.size() + limit > 8192) throw std::runtime_error("aiUnavailable");
        auto contextParams = llama_context_default_params();
        contextParams.n_ctx = static_cast<uint32_t>(tokens.size() + limit);
        contextParams.n_batch = 256;
        contextParams.n_ubatch = 256;
        contextParams.n_threads = contextParams.n_threads_batch = std::clamp(static_cast<int>(threads), 1, 4);
        contextParams.abort_callback = Signal::cancelled;
        contextParams.abort_callback_data = &cancellation;
        std::unique_ptr<llama_context, decltype(&llama_free)> context(
            llama_init_from_model(model.get(), contextParams), llama_free);
        if (!context) throw std::runtime_error("aiUnavailable");
        std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> sampler(
            llama_sampler_chain_init(llama_sampler_chain_default_params()), llama_sampler_free);
        if (!sampler) throw std::bad_alloc();
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_greedy());
        for (size_t offset = 0; offset < tokens.size(); offset += 256) {
            cancellation.check();
            const int count = static_cast<int>(std::min<size_t>(256, tokens.size() - offset));
            if (llama_decode(context.get(), llama_batch_get_one(tokens.data() + offset, count)))
                throw std::runtime_error("aiUnavailable");
        }
        std::string output;
        bool complete = false;
        for (int i = 0; i < limit; ++i) {
            cancellation.check();
            auto token = llama_sampler_sample(sampler.get(), context.get(), -1);
            if (llama_vocab_is_eog(vocab, token)) { complete = true; break; }
            std::vector<char> piece(128);
            int size = llama_token_to_piece(vocab, token, piece.data(), static_cast<int>(piece.size()), 0, false);
            if (size < 0) {
                piece.resize(static_cast<size_t>(-size));
                size = llama_token_to_piece(vocab, token, piece.data(), static_cast<int>(piece.size()), 0, false);
            }
            if (size < 0 || output.size() + size > 1024 * 1024) throw std::runtime_error("aiUnavailable");
            output.append(piece.data(), static_cast<size_t>(size));
            if (llama_decode(context.get(), llama_batch_get_one(&token, 1))) throw std::runtime_error("aiUnavailable");
        }
        cancellation.check();
        if (!complete || output.empty()) throw std::runtime_error("aiUnavailable");
        const auto result = env->NewByteArray(static_cast<jsize>(output.size()));
        if (result) env->SetByteArrayRegion(result, 0, static_cast<jsize>(output.size()),
            reinterpret_cast<const jbyte *>(output.data()));
        env->DeleteGlobalRef(global);
        return result;
    } catch (const std::exception &error) {
        if (global) env->DeleteGlobalRef(global);
        if (!env->ExceptionCheck()) {
            const auto type = env->FindClass("java/lang/IllegalStateException");
            if (type) env->ThrowNew(type, error.what());
        }
        return nullptr;
    }
}


extern "C" JNIEXPORT jfloatArray JNICALL
Java_ru_vrmn_kasha_android_AndroidLlamaNative_embed(JNIEnv *env, jobject,
    jstring modelPath, jbyteArray textBytes, jint threads, jobject signal) {
    jobject global = nullptr;
    try {
        if (!modelPath || !signal) throw std::runtime_error("aiUnavailable");
        const char *pathBytes = env->GetStringUTFChars(modelPath, nullptr);
        if (!pathBytes) throw std::bad_alloc();
        const std::string path(pathBytes);
        env->ReleaseStringUTFChars(modelPath, pathBytes);
        const auto text = bytes(env, textBytes);
        global = env->NewGlobalRef(signal);
        if (!global) throw std::bad_alloc();
        const auto type = env->GetObjectClass(signal);
        const auto method = env->GetMethodID(type, "isCancelled", "()Z");
        env->DeleteLocalRef(type);
        if (!method) throw std::runtime_error("aiUnavailable");
        JavaVM *vm = nullptr;
        env->GetJavaVM(&vm);
        Signal cancellation{vm, global, method};
        cancellation.check();

        static std::once_flag initialized;
        std::call_once(initialized, [] {
            llama_log_set([](ggml_log_level, const char *, void *) {}, nullptr);
            llama_backend_init();
        });

        auto mp = llama_model_default_params();
        mp.n_gpu_layers = 0;
        mp.progress_callback = [](float, void *data) { return !Signal::cancelled(data); };
        mp.progress_callback_user_data = &cancellation;
        std::unique_ptr<llama_model, decltype(&llama_model_free)> model(
            llama_model_load_from_file(path.c_str(), mp), llama_model_free);
        cancellation.check();
        if (!model) throw std::runtime_error("androidAiNotConfigured");

        const auto *vocab = llama_model_get_vocab(model.get());
        std::vector<llama_token> tokens;
        appendTokens(tokens, vocab, text, false);
        if (tokens.empty() || tokens.size() > 8192) throw std::runtime_error("aiUnavailable");

        auto cp = llama_context_default_params();
        cp.n_ctx = static_cast<uint32_t>(std::max<size_t>(tokens.size(), 32));
        cp.n_batch = cp.n_ubatch = static_cast<uint32_t>(tokens.size());
        cp.n_threads = cp.n_threads_batch = std::clamp(static_cast<int>(threads), 1, 4);
        cp.embeddings = true;
        cp.pooling_type = LLAMA_POOLING_TYPE_LAST;
        cp.abort_callback = Signal::cancelled;
        cp.abort_callback_data = &cancellation;
        std::unique_ptr<llama_context, decltype(&llama_free)> context(
            llama_init_from_model(model.get(), cp), llama_free);
        if (!context) throw std::runtime_error("aiUnavailable");

        llama_batch batch = llama_batch_init(static_cast<int32_t>(tokens.size()), 0, 1);
        for (int32_t i = 0; i < static_cast<int32_t>(tokens.size()); ++i) {
            batch.token[i] = tokens[i];
            batch.pos[i] = i;
            batch.n_seq_id[i] = 1;
            batch.seq_id[i][0] = 0;
            batch.logits[i] = 1;
        }
        batch.n_tokens = static_cast<int32_t>(tokens.size());
        cancellation.check();
        if (llama_decode(context.get(), batch) < 0) {
            llama_batch_free(batch);
            throw std::runtime_error("aiUnavailable");
        }
        const float *embedding = llama_get_embeddings_seq(context.get(), 0);
        const int dimensions = llama_model_n_embd_out(model.get());
        if (!embedding || dimensions <= 0 || dimensions > 65536) {
            llama_batch_free(batch);
            throw std::runtime_error("aiUnavailable");
        }
        double norm = 0.0;
        for (int i = 0; i < dimensions; ++i) norm += embedding[i] * embedding[i];
        norm = std::sqrt(norm);
        if (!(norm > 0.0)) {
            llama_batch_free(batch);
            throw std::runtime_error("aiUnavailable");
        }
        std::vector<float> normalized(static_cast<size_t>(dimensions));
        for (int i = 0; i < dimensions; ++i) normalized[i] = static_cast<float>(embedding[i] / norm);
        llama_batch_free(batch);

        auto result = env->NewFloatArray(dimensions);
        if (!result) throw std::bad_alloc();
        env->SetFloatArrayRegion(result, 0, dimensions, normalized.data());
        env->DeleteGlobalRef(global);
        return result;
    } catch (const std::exception &error) {
        if (global) env->DeleteGlobalRef(global);
        if (!env->ExceptionCheck()) {
            const auto type = env->FindClass("java/lang/IllegalStateException");
            if (type) env->ThrowNew(type, error.what());
        }
        return nullptr;
    }
}
