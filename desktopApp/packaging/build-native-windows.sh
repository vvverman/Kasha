#!/usr/bin/env bash
# Запускается из MSYS2 UCRT64/MINGW64 на Windows CI.
set -euo pipefail
REPO="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$REPO"
case "${MSYSTEM:-}" in UCRT64|MINGW64) ;; *) echo 'Нужен MSYS2 UCRT64/MINGW64'; exit 1;; esac
ROOT="$REPO/desktopApp/build/native-windows"
COMMON="$REPO/desktopApp/bundle/common"
RES="$REPO/desktopApp/bundle/windows"
mkdir -p "$ROOT" "$RES/bin" "$RES/models" "$COMMON/licenses"

bash desktopApp/packaging/prepare-models.sh

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
  local repo="$1"
  local rev="$2"
  local target="$3"
  local dir="$ROOT/$repo"
  checkout "ggml-org/$repo" "$rev" "$dir"
  cmake -S "$dir" -B "$dir/build" -G Ninja -DCMAKE_BUILD_TYPE=Release \
    -DBUILD_SHARED_LIBS=OFF -DGGML_NATIVE=OFF -DGGML_OPENMP=OFF -DGGML_CUDA=OFF -DGGML_BLAS=OFF \
    -DLLAMA_CURL=OFF -DLLAMA_OPENSSL=OFF -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_SERVER=OFF \
    -DWHISPER_BUILD_TESTS=OFF -DCMAKE_EXE_LINKER_FLAGS='-static -static-libgcc -static-libstdc++'
  cmake --build "$dir/build" -j 3 --target "$target"
  cp "$dir/build/bin/$target.exe" "$RES/bin/$target.exe"
  cp "$dir/LICENSE" "$COMMON/licenses/$repo.txt"
}

build_engine whisper.cpp "$WHISPER" whisper-cli
build_engine llama.cpp "$LLAMA" llama-completion

# Windows Installer не принимает отдельные файлы >= 2 GiB. Используем штатный
# формат sharded GGUF из того же pinned llama.cpp; runtime открывает первый shard.
LLAMA_DIR="$ROOT/llama.cpp"
cmake --build "$LLAMA_DIR/build" -j 3 --target llama-gguf-split
rm -f "$RES/models"/Qwen3-4B-Q4_K_M-*-of-*.gguf
"$LLAMA_DIR/build/bin/llama-gguf-split.exe" --split-max-size 1500M \
  "$COMMON/models/Qwen3-4B-Q4_K_M.gguf" "$RES/models/Qwen3-4B-Q4_K_M"
mapfile -t QWEN_SHARDS < <(find "$RES/models" -maxdepth 1 -type f -name 'Qwen3-4B-Q4_K_M-*-of-*.gguf' -print | sort)
test "${#QWEN_SHARDS[@]}" -ge 2
for shard in "${QWEN_SHARDS[@]}"; do
  size=$(wc -c < "$shard")
  test "$size" -lt 2000000000
  echo "Qwen shard: $(basename "$shard") ($size bytes)"
done

checkout FFmpeg/FFmpeg "$FFMPEG" "$ROOT/ffmpeg"
(
  cd "$ROOT/ffmpeg"
  ./configure --cc=gcc --arch=x86_64 --target-os=mingw32 --disable-x86asm --disable-autodetect --disable-everything \
    --disable-shared --enable-static --disable-network --disable-doc --disable-debug --disable-ffplay --disable-ffprobe \
    --enable-ffmpeg --enable-protocol=file,pipe --enable-demuxer=mov,matroska,ogg,wav,caf \
    --enable-muxer=wav,ipod --enable-decoder=aac,pcm_s16le,pcm_s24le,pcm_s32le,pcm_f32le,opus,vorbis,alac \
    --enable-encoder=aac,pcm_s16le --enable-parser=aac,opus,vorbis \
    --enable-filter=aresample,aformat,anull,atempo,atrim,asetpts --enable-swresample \
    --extra-ldflags='-static -static-libgcc'
  make -j 3 ffmpeg.exe
  cp ffmpeg.exe "$RES/bin/ffmpeg.exe"
  cp COPYING.LGPLv2.1 "$COMMON/licenses/FFmpeg-LGPL-2.1.txt"
  cp ffbuild/config.log "$COMMON/licenses/FFmpeg-Windows-build-config.txt"
  test -f "$COMMON/licenses/FFmpeg-8.0.1-source.tar.gz" || git archive --format=tar --prefix=FFmpeg-8.0.1/ HEAD | gzip -1 > "$COMMON/licenses/FFmpeg-8.0.1-source.tar.gz"
)

for file in "$RES/bin/"*.exe; do
  file "$file"
  objdump -p "$file" | grep -E 'DLL Name:' || true
done
cp "$COMMON/licenses/whisper.cpp.txt" "$COMMON/licenses/Whisper-model-MIT.txt"
