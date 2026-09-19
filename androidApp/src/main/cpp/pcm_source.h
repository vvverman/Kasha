#pragma once
#include <cmath>
#include <cstdint>
#include <fstream>
#include <limits>
#include <stdexcept>
#include <vector>

// PCM16 decoder output -> mono Float32/16kHz. Never modifies the source file.
inline std::vector<float> kasha_pcm(const char *path, int rate, int channels, bool (*cancelled)(void *), void *state) {
    if (rate < 8000 || rate > 192000 || channels < 1 || channels > 32) throw std::runtime_error("audioFailed");
    std::ifstream input(path, std::ios::binary | std::ios::ate);
    const auto bytes = input.tellg();
    if (!input || bytes <= 0 || bytes % (channels * 2) != 0) throw std::runtime_error("audioFailed");
    const int64_t frames = static_cast<int64_t>(bytes) / (channels * 2);
    const int64_t count = (frames * 16000 + rate - 1) / rate;
    if (count <= 0 || count > std::numeric_limits<int>::max()) throw std::runtime_error("audioFailed");
    input.seekg(0);
    std::vector<float> output(static_cast<size_t>(count));
    auto frame = [&]() {
        float sum = 0;
        for (int c = 0; c < channels; ++c) {
            unsigned char value[2];
            if (!input.read(reinterpret_cast<char *>(value), 2)) throw std::runtime_error("audioFailed");
            const auto sample = static_cast<int16_t>(static_cast<uint16_t>(value[0]) | (static_cast<uint16_t>(value[1]) << 8));
            sum += static_cast<float>(sample) / 32768.f;
        }
        return sum / channels;
    };
    int64_t cursor = 0;
    float left = frame(), right = frames > 1 ? frame() : left;
    for (int64_t i = 0; i < count; ++i) {
        if ((i & 4095) == 0 && cancelled(state)) throw std::runtime_error("operationCancelled");
        const double position = static_cast<double>(i) * rate / 16000;
        const int64_t index = static_cast<int64_t>(position);
        while (cursor < index) {
            left = right;
            ++cursor;
            right = cursor + 1 < frames ? frame() : left;
        }
        output[static_cast<size_t>(i)] = left + (right - left) * static_cast<float>(position - index);
    }
    return output;
}
