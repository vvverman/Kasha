#!/usr/bin/env bash
set -euo pipefail
REPO="$(cd "$(dirname "$0")/../../.." && pwd)"
cd "$REPO"
COMMON="$REPO/platforms/desktop/bundle/common"
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

fetch_model ggml-small.bin \
  https://huggingface.co/ggerganov/whisper.cpp/resolve/90a64d80ea254cf67575b41a5971f972c79f7b45/ggml-small.bin \
  1be3a9b2063867b937e64e2ec7483364a79917e157fa98c5d94b5c1fffea987b
fetch_model Qwen3-4B-Q4_K_M.gguf \
  https://huggingface.co/Qwen/Qwen3-4B-GGUF/resolve/a9a60d009fa7ff9606305047c2bf77ac25dbec49/Qwen3-4B-Q4_K_M.gguf \
  7485fe6f11af29433bc51cab58009521f205840f5b4ae3a32fa7f92e8534fdf5

curl -fL --retry 3 \
  https://huggingface.co/Qwen/Qwen3-4B-GGUF/raw/bc640142c66e1fdd12af0bd68f40445458f3869b/LICENSE \
  -o "$COMMON/licenses/Qwen3-Apache-2.0.txt"
grep -q 'Apache License' "$COMMON/licenses/Qwen3-Apache-2.0.txt"

printf '%s\n' \
  'Kasha bundles unmodified Whisper Small and Qwen3-4B Q4_K_M.' \
  'Native whisper.cpp, llama.cpp and FFmpeg executables are platform-specific resources.' \
  'FFmpeg is distributed under LGPL; source revision/build configuration are retained by each platform build.' \
  'Java: Eclipse Temurin 21, GPLv2 with Classpath Exception, licenses in runtime/legal.' \
  'Compose/Kotlin/Ktor: Apache-2.0; library notices retained inside their JARs.' \
  > "$COMMON/licenses/NOTICE.txt"
cp platforms/desktop/packaging/Установка.txt "$COMMON/Установка.txt"
