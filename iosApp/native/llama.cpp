#include "llama.h"
#include <algorithm>
#include <memory>
#include <mutex>
#include <string>
#include <vector>
#include <stdexcept>
#include <cstring>

namespace {
using Cancel = int (*)(void *);
struct Cancellation {
    Cancel callback; void *data;
    static bool aborted(void *value) {
        auto *s = static_cast<Cancellation *>(value);
        return s->callback && s->callback(s->data) != 0;
    }
    void check() { if (aborted(this)) throw std::runtime_error("operationCancelled"); }
};
// Формат ответа тот же, что у Android; разбор, prompts и проверки остаются в LocalTextRoles/Core.
constexpr const char *grammar = R"GBNF(root ::= object
value ::= object | array | string | number | ("true" | "false" | "null") ws
object ::= "{" ws (string ":" ws value ("," ws string ":" ws value)*)? "}" ws
array ::= "[" ws (value ("," ws value)*)? "]" ws
string ::= "\"" ([^"\\\x7F\x00-\x1F] | "\\" (["\\bfnrt] | "u" [0-9a-fA-F]{4}))* "\"" ws
number ::= ("-"? ([0-9] | [1-9] [0-9]{0,15})) ("." [0-9]+)? ([eE] [-+]? [0-9] [1-9]{0,15})? ws
ws ::= | " " | "\n" [ \t]{0,20}
)GBNF";
void append(std::vector<llama_token> &target, const llama_vocab *vocab, const std::string &text, bool special) {
    const int count = -llama_tokenize(vocab, text.data(), static_cast<int>(text.size()), nullptr, 0, false, special);
    if (count <= 0 || count > 8192) throw std::runtime_error("aiUnavailable");
    auto offset = target.size(); target.resize(offset + count);
    if (llama_tokenize(vocab, text.data(), static_cast<int>(text.size()), target.data() + offset, count, false, special) != count)
        throw std::runtime_error("aiUnavailable");
}
}
extern "C" __attribute__((visibility("default"))) char *kasha_llama_run(
    const char *path, const char *prompt, int limit, int threads, Cancel callback, void *data, int *status) {
    *status = 1;
    try {
        if (!path || !prompt || limit <= 0 || limit > 2200 || strlen(prompt) > 1024 * 1024)
            throw std::runtime_error("aiUnavailable");
        Cancellation cancel{callback, data}; cancel.check();
        static std::once_flag initialized;
        std::call_once(initialized, [] {
            llama_log_set([](ggml_log_level, const char *, void *) {}, nullptr);
            llama_backend_init();
        });
        auto mp = llama_model_default_params(); mp.n_gpu_layers = 0;
        mp.progress_callback = [](float, void *value) { return !Cancellation::aborted(value); };
        mp.progress_callback_user_data = &cancel;
        std::unique_ptr<llama_model, decltype(&llama_model_free)> model(llama_model_load_from_file(path, mp), llama_model_free);
        cancel.check();
        if (!model) throw std::runtime_error("aiUnavailable");
        auto *vocab = llama_model_get_vocab(model.get());
        std::vector<llama_token> tokens;
        append(tokens, vocab, "<|im_start|>user\n", true);
        append(tokens, vocab, prompt, false);
        append(tokens, vocab, "\n/no_think<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n", true);
        if (tokens.size() + limit > 8192) throw std::runtime_error("aiUnavailable");
        auto cp = llama_context_default_params();
        cp.n_ctx = static_cast<uint32_t>(tokens.size() + limit); cp.n_batch = cp.n_ubatch = 256;
        cp.n_threads = cp.n_threads_batch = std::clamp(threads, 1, 4);
        cp.abort_callback = Cancellation::aborted; cp.abort_callback_data = &cancel;
        std::unique_ptr<llama_context, decltype(&llama_free)> context(llama_init_from_model(model.get(), cp), llama_free);
        if (!context) throw std::runtime_error("aiUnavailable");
        std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> sampler(
            llama_sampler_chain_init(llama_sampler_chain_default_params()), llama_sampler_free);
        auto *g = llama_sampler_init_grammar(vocab, grammar, "root");
        if (!sampler || !g) throw std::runtime_error("aiUnavailable");
        llama_sampler_chain_add(sampler.get(), g);
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_greedy());
        for (size_t offset = 0; offset < tokens.size(); offset += 256) {
            cancel.check();
            const int count = static_cast<int>(std::min<size_t>(256, tokens.size() - offset));
            if (llama_decode(context.get(), llama_batch_get_one(tokens.data() + offset, count)))
                throw std::runtime_error("aiUnavailable");
        }
        std::string result; bool complete = false;
        for (int i = 0; i < limit; ++i) {
            cancel.check();
            auto token = llama_sampler_sample(sampler.get(), context.get(), -1);
            if (llama_vocab_is_eog(vocab, token)) { complete = true; break; }
            std::vector<char> piece(128);
            int size = llama_token_to_piece(vocab, token, piece.data(), static_cast<int>(piece.size()), 0, false);
            if (size < 0) {
                piece.resize(static_cast<size_t>(-size));
                size = llama_token_to_piece(vocab, token, piece.data(), static_cast<int>(piece.size()), 0, false);
            }
            if (size < 0 || result.size() + size > 1024 * 1024) throw std::runtime_error("aiUnavailable");
            result.append(piece.data(), static_cast<size_t>(size));
            if (llama_decode(context.get(), llama_batch_get_one(&token, 1))) throw std::runtime_error("aiUnavailable");
        }
        cancel.check();
        if (!complete || result.empty()) throw std::runtime_error("aiUnavailable");
        auto *text = strdup(result.c_str());
        if (!text) throw std::bad_alloc();
        *status = 0; return text;
    } catch (const std::exception &error) { return strdup(error.what()); }
}
