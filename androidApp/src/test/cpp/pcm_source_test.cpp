#include "pcm_source.h"
#include <cassert>
#include <cstdio>
#include <iostream>
static bool never(void *) { return false; }
static bool always(void *) { return true; }
static void write(const char *file, const std::vector<int16_t> &values) {
    std::ofstream out(file, std::ios::binary);
    for (auto value: values) {
        out.put(static_cast<char>(value & 255)); out.put(static_cast<char>((static_cast<uint16_t>(value) >> 8) & 255));
    }
}
int main() {
    const char *file="test.pcm";
    write(file, {0, 16384, -32768, 32767});
    const auto mono=kasha_pcm(file,16000,1,never,nullptr);
    assert(mono.size()==4 && mono[0]==0 && mono[1]==.5f && mono[2]==-1.f);
    auto stereo=kasha_pcm(file,16000,2,never,nullptr);
    assert(stereo.size()==2 && stereo[0]==.25f && std::abs(stereo[1])<.0001f);
    auto up=kasha_pcm(file,8000,1,never,nullptr);
    assert(up.size()==8 && up[1]==.25f && up[7]==mono[3]);
    std::vector<int16_t> constant(44100,16384); write(file,constant);
    auto down=kasha_pcm(file,44100,1,never,nullptr);
    assert(down.size()==16000);
    for(auto sample:down) assert(sample==.5f);
    bool cancelled=false;try{kasha_pcm(file,16000,1,always,nullptr);}catch(const std::runtime_error&){cancelled=true;}assert(cancelled);
    bool invalid=false;try{kasha_pcm(file,0,1,never,nullptr);}catch(const std::runtime_error&){invalid=true;}assert(invalid);
    write(file,{1});invalid=false;try{kasha_pcm(file,16000,2,never,nullptr);}catch(const std::runtime_error&){invalid=true;}assert(invalid);
    std::ifstream in(file,std::ios::binary|std::ios::ate);assert(in.tellg()==2);in.close();
    std::remove(file);std::cout<<"8 PCM normalization checks passed\n";
}
