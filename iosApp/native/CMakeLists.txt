cmake_minimum_required(VERSION 3.22)
project(KashaIosModels LANGUAGES C CXX OBJCXX ASM)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(CMAKE_C_VISIBILITY_PRESET hidden)
set(CMAKE_CXX_VISIBILITY_PRESET hidden)
set(CMAKE_VISIBILITY_INLINES_HIDDEN ON)
foreach(option GGML_NATIVE GGML_OPENMP GGML_METAL GGML_CUDA GGML_BLAS GGML_ACCELERATE
        GGML_CPU_KLEIDIAI GGML_LLAMAFILE WHISPER_BUILD_TESTS WHISPER_BUILD_EXAMPLES
        WHISPER_BUILD_SERVER LLAMA_CURL LLAMA_OPENSSL LLAMA_BUILD_TESTS
        LLAMA_BUILD_EXAMPLES LLAMA_BUILD_SERVER LLAMA_BUILD_TOOLS)
    set(${option} OFF CACHE BOOL "" FORCE)
endforeach()
include(FetchContent)
# Отдельные framework: закреплённые whisper/llama содержат разные версии ggml.
if(KASHA_ENGINE STREQUAL "whisper")
    FetchContent_Declare(engine GIT_REPOSITORY https://github.com/ggml-org/whisper.cpp.git
        GIT_TAG 306c88f4d1286aec1bf96e544632897886af5501)
    FetchContent_MakeAvailable(engine)
    set(name KashaWhisper)
    add_library(${name} SHARED whisper.mm)
    target_link_libraries(${name} PRIVATE whisper "-framework AVFoundation" "-framework Foundation")
    target_compile_options(${name} PRIVATE -fobjc-arc)
else()
    FetchContent_Declare(engine GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
        GIT_TAG 5266f24da75dc449bd56cbed7addb9c8e4a6a73e)
    FetchContent_MakeAvailable(engine)
    set(name KashaLlama)
    add_library(${name} SHARED llama.cpp)
    target_link_libraries(${name} PRIVATE llama)
endif()
set_target_properties(${name} PROPERTIES FRAMEWORK TRUE
    MACOSX_FRAMEWORK_IDENTIFIER "ru.vrmn.kasha.${KASHA_ENGINE}"
    MACOSX_FRAMEWORK_SHORT_VERSION_STRING "1.0" MACOSX_FRAMEWORK_BUNDLE_VERSION "1"
    LIBRARY_OUTPUT_DIRECTORY "${KASHA_OUTPUT}" LIBRARY_OUTPUT_DIRECTORY_RELEASE "${KASHA_OUTPUT}"
    INSTALL_NAME_DIR "@rpath" BUILD_WITH_INSTALL_NAME_DIR TRUE XCODE_ATTRIBUTE_CODE_SIGNING_ALLOWED NO)
# Не экспортировать ggml или C++ ABI из одного движка в другой.
# Llama-framework обслуживает две независимые роли: Cleanup и routing embeddings.
if(KASHA_ENGINE STREQUAL "llama")
    target_link_options(${name} PRIVATE
        "-Wl,-exported_symbol,_kasha_llama_run"
        "-Wl,-exported_symbol,_kasha_llama_embed")
else()
    target_link_options(${name} PRIVATE "-Wl,-exported_symbol,_kasha_whisper_run")
endif()
