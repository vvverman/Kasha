#!/usr/bin/env bash
set -euo pipefail
REPO="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$REPO"
[ "$(uname -s)" = Linux ] || { echo 'Нужен Linux build host'; exit 1; }
ROOT="$REPO/desktopApp/build/native-linux"
COMMON="$REPO/desktopApp/bundle/common"
RES="$REPO/desktopApp/bundle/linux"
mkdir -p "$ROOT" "$RES/bin" "$COMMON/licenses"

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
  cmake -S "$dir" -B "$dir/build" -DCMAKE_BUILD_TYPE=Release \
    -DBUILD_SHARED_LIBS=OFF -DGGML_NATIVE=OFF -DGGML_OPENMP=OFF -DGGML_CUDA=OFF -DGGML_BLAS=OFF \
    -DLLAMA_CURL=OFF -DLLAMA_OPENSSL=OFF -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_SERVER=OFF \
    -DWHISPER_BUILD_TESTS=OFF -DCMAKE_EXE_LINKER_FLAGS='-static-libgcc -static-libstdc++'
  cmake --build "$dir/build" -j 3 --target "$target"
  cp "$dir/build/bin/$target" "$RES/bin/$target"
  cp "$dir/LICENSE" "$COMMON/licenses/$repo.txt"
}

build_engine whisper.cpp "$WHISPER" whisper-cli
build_engine llama.cpp "$LLAMA" llama-embedding
build_engine llama.cpp "$LLAMA" llama-completion

checkout FFmpeg/FFmpeg "$FFMPEG" "$ROOT/ffmpeg"
(
  cd "$ROOT/ffmpeg"
  ./configure --cc=gcc --arch=x86_64 --target-os=linux --disable-x86asm --disable-autodetect --disable-everything \
    --disable-shared --enable-static --disable-network --disable-doc --disable-debug --disable-ffplay --disable-ffprobe \
    --enable-ffmpeg --enable-protocol=file,pipe --enable-demuxer=mov,matroska,ogg,wav,caf \
    --enable-muxer=wav,ipod --enable-decoder=aac,pcm_s16le,pcm_s24le,pcm_s32le,pcm_f32le,opus,vorbis,alac \
    --enable-encoder=aac,pcm_s16le --enable-parser=aac,opus,vorbis \
    --enable-filter=aresample,aformat,anull,atempo,atrim,asetpts --enable-swresample \
    --extra-ldflags='-static-libgcc'
  make -j 3 ffmpeg
  cp ffmpeg "$RES/bin/ffmpeg"
  cp COPYING.LGPLv2.1 "$COMMON/licenses/FFmpeg-LGPL-2.1.txt"
  cp ffbuild/config.log "$COMMON/licenses/FFmpeg-Linux-build-config.txt"
  test -f "$COMMON/licenses/FFmpeg-8.0.1-source.tar.gz" || git archive --format=tar --prefix=FFmpeg-8.0.1/ HEAD | gzip -1 > "$COMMON/licenses/FFmpeg-8.0.1-source.tar.gz"
)

for file in "$RES/bin/"*; do
  chmod 755 "$file"
  file "$file"
  "$file" -h >/dev/null 2>&1 || true
done
cp "$COMMON/licenses/whisper.cpp.txt" "$COMMON/licenses/Whisper-model-MIT.txt"
