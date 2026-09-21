#!/usr/bin/env bash
set -euo pipefail
REPO="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$REPO"
COMMON="$REPO/desktopApp/bundle/common"
mkdir -p "$COMMON/models" "$COMMON/licenses"

hash256() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d ' ' -f1
  else shasum -a 256 "$1" | cut -d ' ' -f1
  fi
}

fetch_model() {
  local name="$1" url="$2" expected="$3" file="$COMMON/models/$1"
  if [ -f "$file" ] && [ "$(hash256 "$file")" = "$expected" ]; then return; fi
  curl -fL --retry 3 --connect-timeout 30 --max-time 1800 "$url" -o "$file.part"
  test "$(hash256 "$file.part")" = "$expected"
  mv "$file.part" "$file"
}

fetch_model ggml-large-v3-turbo-q5_0.bin \
  https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo-q5_0.bin \
  394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2
fetch_model F2LLM-v2-80M.Q8_0.gguf \
  https://huggingface.co/mradermacher/F2LLM-v2-80M-GGUF/resolve/main/F2LLM-v2-80M.Q8_0.gguf \
  fb2a92e51dba7120d5369704502520814775b27c116d27ed039690106bf224ee

printf '%s\n' \
  'Kasha bundles Whisper Large-v3 Turbo Q5 and F2LLM-v2-80M Q8_0 for local speech recognition and project routing.' \
  'Native whisper.cpp, llama.cpp and FFmpeg executables are platform-specific resources.' \
  'FFmpeg is distributed under LGPL; source revision/build configuration are retained by each platform build.' \
  'Java: Eclipse Temurin 21, GPLv2 with Classpath Exception, licenses in runtime/legal.' \
  'Compose/Kotlin/Ktor: Apache-2.0; library notices retained inside their JARs.' \
  > "$COMMON/licenses/NOTICE.txt"
cp desktopApp/packaging/Установка.txt "$COMMON/Установка.txt"
