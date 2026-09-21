#!/usr/bin/env bash
# Выполняется только на сборочном Mac. На компьютере пользователя эти инструменты не нужны.
set -euo pipefail
cd "$(dirname "$0")/../.."
[ "$(uname -m)" = arm64 ] || { echo 'Нужен сборочный Mac arm64'; exit 1; }
export MACOSX_DEPLOYMENT_TARGET=13.3
ROOT="$PWD/desktopApp/build/native"
RES="$PWD/desktopApp/bundle/common"
mkdir -p "$ROOT" "$RES/bin" "$RES/models" "$RES/licenses"
checkout() {
  local repo="$1" rev="$2" dir="$3"
  if [ ! -d "$dir/.git" ]; then mkdir -p "$dir"; git -C "$dir" init -q; git -C "$dir" remote add origin "https://github.com/$repo.git"; fi
  git -C "$dir" fetch --depth 1 origin "$rev"
  git -C "$dir" checkout --detach FETCH_HEAD
  test "$(git -C "$dir" rev-parse HEAD)" = "$rev"
}
WHISPER=306c88f4d1286aec1bf96e544632897886af5501
LLAMA=5266f24da75dc449bd56cbed7addb9c8e4a6a73e
FFMPEG=894da5ca7d742e4429ffb2af534fcda0103ef593
build_engine() {
  local repo="$1" rev="$2" target="$3" dir="$ROOT/$1"
  checkout "ggml-org/$repo" "$rev" "$dir"
  cmake -S "$dir" -B "$dir/build" -DCMAKE_BUILD_TYPE=Release -DCMAKE_OSX_DEPLOYMENT_TARGET=13.3 \
    -DBUILD_SHARED_LIBS=OFF -DGGML_NATIVE=OFF -DGGML_OPENMP=OFF -DGGML_CUDA=OFF \
    -DGGML_METAL_EMBED_LIBRARY=ON -DLLAMA_CURL=OFF -DLLAMA_OPENSSL=OFF \
    -DLLAMA_BUILD_BORINGSSL=OFF -DLLAMA_BUILD_LIBRESSL=OFF -DLLAMA_BUILD_TESTS=OFF \
    -DLLAMA_BUILD_SERVER=OFF -DWHISPER_BUILD_TESTS=OFF
  cmake --build "$dir/build" -j 3 --target "$target"
  cp "$dir/build/bin/$target" "$RES/bin/"
  cp "$dir/LICENSE" "$RES/licenses/$repo.txt"
}
build_engine whisper.cpp "$WHISPER" whisper-cli
build_engine llama.cpp "$LLAMA" llama-embedding
checkout FFmpeg/FFmpeg "$FFMPEG" "$ROOT/ffmpeg"
(
 cd "$ROOT/ffmpeg"
 ./configure --cc=clang --arch=arm64 --target-os=darwin --disable-autodetect --disable-everything \
   --disable-shared --enable-static --disable-network --disable-doc --disable-debug --disable-ffplay --disable-ffprobe \
   --enable-ffmpeg --enable-protocol=file,pipe --enable-demuxer=mov,matroska,ogg,wav,caf \
   --enable-muxer=wav,ipod --enable-decoder=aac,pcm_s16le,pcm_s24le,pcm_s32le,pcm_f32le,opus,vorbis,alac \
   --enable-encoder=aac,pcm_s16le --enable-parser=aac,opus,vorbis \
   --enable-filter=aresample,aformat,anull,atempo,atrim,asetpts --enable-swresample \
   --extra-cflags=-mmacosx-version-min=13.3 --extra-ldflags=-mmacosx-version-min=13.3
 make -j 3 ffmpeg
 cp ffmpeg "$RES/bin/ffmpeg"
 cp COPYING.LGPLv2.1 "$RES/licenses/FFmpeg-LGPL-2.1.txt"
 cp ffbuild/config.log "$RES/licenses/FFmpeg-build-config.txt"
 git archive --format=tar --prefix=FFmpeg-8.0.1/ HEAD | gzip -1 > "$RES/licenses/FFmpeg-8.0.1-source.tar.gz"
)
for file in "$RES/bin/"*; do
 chmod 755 "$file"
 file "$file"
 otool -L "$file"
 if otool -L "$file" | tail -n +2 | grep -vE '^[[:space:]]*(/System/Library/|/usr/lib/)' | grep -q .; then
   echo "Внешняя зависимость в $file, выпуск запрещён"; exit 1
 fi
done
fetch_model() {
 local name="$1" url="$2" expected="$3" file="$RES/models/$1"
 if [ -f "$file" ] && [ "$(shasum -a 256 "$file" | cut -d ' ' -f1)" = "$expected" ]; then return; fi
 curl -fL --retry 3 --connect-timeout 30 --max-time 1800 "$url" -o "$file.part"
 test "$(shasum -a 256 "$file.part" | cut -d ' ' -f1)" = "$expected"
 mv "$file.part" "$file"
}
fetch_model ggml-small.bin https://huggingface.co/ggerganov/whisper.cpp/resolve/90a64d80ea254cf67575b41a5971f972c79f7b45/ggml-small.bin 1be3a9b2063867b937e64e2ec7483364a79917e157fa98c5d94b5c1fffea987b
fetch_model Qwen3-4B-Q4_K_M.gguf https://huggingface.co/Qwen/Qwen3-4B-GGUF/resolve/a9a60d009fa7ff9606305047c2bf77ac25dbec49/Qwen3-4B-Q4_K_M.gguf 7485fe6f11af29433bc51cab58009521f205840f5b4ae3a32fa7f92e8534fdf5
# Лицензия добавлена upstream позднее закреплённого коммита весов; веса не меняем.
curl -fL --retry 3 https://huggingface.co/Qwen/Qwen3-4B-GGUF/raw/bc640142c66e1fdd12af0bd68f40445458f3869b/LICENSE -o "$RES/licenses/Qwen3-Apache-2.0.txt"
grep -q 'Apache License' "$RES/licenses/Qwen3-Apache-2.0.txt"
cp "$RES/licenses/whisper.cpp.txt" "$RES/licenses/Whisper-model-MIT.txt"
printf 'Kasha bundles Whisper Large-v3 Turbo Q5 and F2LLM-v2-80M Q8_0.\nFFmpeg is a separate LGPL executable; its source and build configuration are included.\nJava: Eclipse Temurin 21, GPLv2 with Classpath Exception, licenses in runtime/legal.\nCompose/Kotlin/Ktor: Apache-2.0; library notices retained inside their JARs.\n' > "$RES/licenses/NOTICE.txt"
cp desktopApp/packaging/Установка.txt "$RES/Установка.txt"
