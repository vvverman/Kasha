cmake_minimum_required(VERSION 3.22.1)
project(kasha_whisper LANGUAGES C CXX ASM)
set(CMAKE_CXX_STANDARD 17)
# JNI остаётся отлаживаемым; математика моделей не должна исполняться с -O0.
# Обычная release-конфигурация и все ABI сохраняются.
add_compile_options("$<$<CONFIG:Debug>:-O2>")
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
include(FetchContent)
# Same pinned whisper.cpp revision as the existing desktop packages.
FetchContent_Declare(whisper
    GIT_REPOSITORY https://github.com/ggml-org/whisper.cpp.git
    GIT_TAG 306c88f4d1286aec1bf96e544632897886af5501)
FetchContent_MakeAvailable(whisper)

# Два pinned-движка имеют разные ggml: независимые CMake/ELF, без конфликта символов.
include(ExternalProject)
set(LLAMA_SO "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}/libkasha_llama.so")
ExternalProject_Add(kasha_llama_build
    SOURCE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/llama"
    BINARY_DIR "${CMAKE_CURRENT_BINARY_DIR}/llama-build"
    CMAKE_ARGS
        "-DCMAKE_TOOLCHAIN_FILE=${CMAKE_TOOLCHAIN_FILE}"
        "-DANDROID_ABI=${ANDROID_ABI}"
        "-DANDROID_PLATFORM=${ANDROID_PLATFORM}"
        "-DANDROID_STL=c++_shared"
        "-DCMAKE_BUILD_TYPE=${CMAKE_BUILD_TYPE}"
        "-DCMAKE_LIBRARY_OUTPUT_DIRECTORY=${CMAKE_LIBRARY_OUTPUT_DIRECTORY}"
    BUILD_COMMAND "${CMAKE_COMMAND}" --build <BINARY_DIR> --target kasha_llama --parallel 2
    BUILD_BYPRODUCTS "${LLAMA_SO}"
    BUILD_ALWAYS TRUE
    INSTALL_COMMAND "")
add_library(kasha_llama_imported SHARED IMPORTED GLOBAL)
set_target_properties(kasha_llama_imported PROPERTIES IMPORTED_LOCATION "${LLAMA_SO}")
add_dependencies(kasha_llama_imported kasha_llama_build)
add_library(kasha_whisper SHARED kasha_whisper.cpp)
add_dependencies(kasha_whisper kasha_llama_build)
target_link_libraries(kasha_whisper PRIVATE whisper kasha_llama_imported)
target_link_options(kasha_whisper PRIVATE "-Wl,--exclude-libs,ALL" "-Wl,-z,max-page-size=16384")
