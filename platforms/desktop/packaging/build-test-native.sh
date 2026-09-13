#!/usr/bin/env bash
# Только сборочный Mac: ни веса, ни Whisper/LLM-движки сюда не попадают.
set -euo pipefail
cd "$(dirname "$0")/../.."
[ "$(uname -m)" = arm64 ]
export MACOSX_DEPLOYMENT_TARGET=13.3
RES="$PWD/desktopApp/bundle-test/common"
SRC="$PWD/desktopApp/build/test-native/ffmpeg"
mkdir -p "$RES/bin" "$RES/licenses" "$SRC"
git -C "$SRC" init -q
git -C "$SRC" remote add origin https://github.com/FFmpeg/FFmpeg.git 2>/dev/null || true
git -C "$SRC" fetch --depth 1 origin 894da5ca7d742e4429ffb2af534fcda0103ef593
git -C "$SRC" checkout --detach FETCH_HEAD
[ "$(git -C "$SRC" rev-parse HEAD)" = 894da5ca7d742e4429ffb2af534fcda0103ef593 ]
(
cd "$SRC"
./configure --cc=clang --arch=arm64 --target-os=darwin --disable-autodetect --disable-everything \
 --disable-shared --enable-static --disable-network --disable-doc --disable-debug --disable-ffplay --disable-ffprobe \
 --enable-ffmpeg --enable-protocol=file,pipe --enable-demuxer=mov,matroska,ogg,wav,caf --enable-muxer=wav,ipod \
 --enable-decoder=aac,pcm_s16le,pcm_s24le,pcm_s32le,pcm_f32le,opus,vorbis,alac --enable-encoder=aac,pcm_s16le \
 --enable-parser=aac,opus,vorbis --enable-filter=aresample,aformat,anull,atempo,atrim,asetpts --enable-swresample \
 --extra-cflags=-mmacosx-version-min=13.3 --extra-ldflags=-mmacosx-version-min=13.3
make -j 3 ffmpeg
cp ffmpeg "$RES/bin/ffmpeg"
cp COPYING.LGPLv2.1 "$RES/licenses/FFmpeg-LGPL-2.1.txt"
cp ffbuild/config.log "$RES/licenses/FFmpeg-build-config.txt"
git archive --format=tar --prefix=FFmpeg-8.0.1/ HEAD | gzip -1 > "$RES/licenses/FFmpeg-8.0.1-source.tar.gz"
)
chmod 755 "$RES/bin/ffmpeg"
otool -L "$RES/bin/ffmpeg"
if otool -L "$RES/bin/ffmpeg" | tail -n +2 | grep -vE '^[[:space:]]*(/System/Library/|/usr/lib/)' | grep -q .; then exit 1; fi
printf 'TEST BUILD: AI outputs are deterministic examples, not speech recognition. No model downloads.\n' > "$RES/demo-mode.txt"
