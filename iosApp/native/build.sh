#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SDK="${1:-iphoneos}"
case "$SDK" in iphoneos|iphonesimulator) ;; *) echo 'Unsupported Apple SDK' >&2; exit 1;; esac
OUT="$ROOT/iosApp/build/native/$SDK"
for engine in whisper llama; do
    BUILD="$ROOT/iosApp/build/native-build/$SDK/$engine"
    REV=306c88f4d1286aec1bf96e544632897886af5501
    REPO=whisper.cpp
    if [ "$engine" = llama ]; then REV=5266f24da75dc449bd56cbed7addb9c8e4a6a73e; REPO=llama.cpp; fi
    SOURCE="$ROOT/iosApp/build/native-sources/$engine"
    if [ ! -d "$SOURCE/.git" ]; then
        mkdir -p "$SOURCE"; git -C "$SOURCE" init -q
        git -C "$SOURCE" remote add origin "https://github.com/ggml-org/$REPO.git"
    fi
    if [ "$(git -C "$SOURCE" rev-parse HEAD 2>/dev/null || true)" != "$REV" ]; then
        git -C "$SOURCE" fetch --depth 1 origin "$REV"
        git -C "$SOURCE" checkout --detach FETCH_HEAD
    fi
    test "$(git -C "$SOURCE" rev-parse HEAD)" = "$REV"
    cmake -S "$ROOT/iosApp/native" -B "$BUILD" -G Xcode \
        -DCMAKE_SYSTEM_NAME=iOS -DCMAKE_OSX_SYSROOT="$SDK" \
        -DCMAKE_OSX_ARCHITECTURES=arm64 -DCMAKE_OSX_DEPLOYMENT_TARGET=16.0 \
        -DKASHA_ENGINE="$engine" -DKASHA_OUTPUT="$OUT" -DFETCHCONTENT_SOURCE_DIR_ENGINE="$SOURCE" \
        -DCMAKE_XCODE_ATTRIBUTE_CODE_SIGNING_ALLOWED=NO
    cmake --build "$BUILD" --config Release --parallel 3
    NAME=KashaWhisper; [ "$engine" = whisper ] || NAME=KashaLlama
    BINARY="$OUT/$NAME.framework/$NAME"
    test -f "$BINARY"
    lipo -verify_arch arm64 "$BINARY"
    nm -gU "$BINARY" | grep -q "_kasha_${engine}_run$"
    if nm -gU "$BINARY" | grep -qE ' _ggml_| _llama_| _whisper_'; then
        echo 'Internal engine symbols escaped the framework' >&2; exit 1
    fi
    cp "$SOURCE/LICENSE" "$OUT/$NAME.framework/LICENSE.txt"
    codesign --force --sign - --timestamp=none "$OUT/$NAME.framework"
done
