#!/usr/bin/env bash
# Сборка закреплённых локальных движков и проверенная загрузка весов. Без облачного inference.
set -euo pipefail
umask 077
ROOT="${KASHA_TOOLS_HOME:-$HOME/.kasha-tools}"
mkdir -p "$ROOT"
ROOT=$(cd "$ROOT" && pwd)
LOCK="$ROOT/.setup-lock"
mkdir "$LOCK" 2>/dev/null || { echo "Настройка уже запущена. После аварийного завершения удалите $LOCK" >&2; exit 1; }
trap 'rmdir "$LOCK" 2>/dev/null || true' EXIT
for tool in git cmake curl ffmpeg; do
  command -v "$tool" >/dev/null || { echo "Не найден $tool. На Mac: brew install cmake ffmpeg. Затем повторите запуск." >&2; exit 1; }
done
JOBS="${KASHA_BUILD_JOBS:-4}"
WHISPER_REV=306c88f4d1286aec1bf96e544632897886af5501
LLAMA_REV=5266f24da75dc449bd56cbed7addb9c8e4a6a73e
build_tool() {
  local repo="$1" rev="$2" target="$3"
  local dir="$ROOT/$repo-$rev"
  if [ ! -x "$dir/build/bin/$target" ]; then
    mkdir -p "$dir"
    if [ ! -d "$dir/.git" ]; then git -C "$dir" init -q; git -C "$dir" remote add origin "https://github.com/ggml-org/$repo.git"; fi
    git -C "$dir" fetch --depth 1 origin "$rev"
    git -C "$dir" checkout --detach FETCH_HEAD
    [ "$(git -C "$dir" rev-parse HEAD)" = "$rev" ] || exit 1
    cmake -S "$dir" -B "$dir/build" -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=OFF -DGGML_NATIVE=OFF -DGGML_CUDA=OFF -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_SERVER=OFF -DWHISPER_BUILD_TESTS=OFF
    cmake --build "$dir/build" --config Release -j "$JOBS" --target "$target"
  fi
}
sha256() {
  if command -v sha256sum >/dev/null; then sha256sum "$1" | cut -d ' ' -f1
  else shasum -a 256 "$1" | cut -d ' ' -f1; fi
}
fetch_model() {
  local name="$1" url="$2" expected="$3" file="$ROOT/models/$1"
  if [ -f "$file" ] && [ "$(sha256 "$file")" = "$expected" ]; then return; fi
  echo "Загрузка модели: $name"
  curl --fail --location --retry 3 --connect-timeout 30 --max-time 1800 "$url" -o "$file.part"
  [ "$(sha256 "$file.part")" = "$expected" ] || { echo "Неверная контрольная сумма: $name" >&2; exit 1; }
  mv "$file.part" "$file"
}
build_tool whisper.cpp "$WHISPER_REV" whisper-cli
build_tool llama.cpp "$LLAMA_REV" llama-completion
build_tool llama.cpp "$LLAMA_REV" llama-embedding
mkdir -p "$ROOT/models"
fetch_model ggml-large-v3-turbo-q5_0.bin \
  https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo-q5_0.bin \
  394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2
fetch_model Kasha-Cleanup-0.6B-Q4_K_M.gguf \
  https://github.com/vvverman/Kasha/releases/download/kasha-cleanup-0.6b-v1/Kasha-Cleanup-0.6B-Q4_K_M.gguf \
  8c405ca3d29af67b21d2147e195b62ea63c84b84be1794b8aa453f31115b4f69
fetch_model F2LLM-v2-80M.Q8_0.gguf \
  https://huggingface.co/mradermacher/F2LLM-v2-80M-GGUF/resolve/main/F2LLM-v2-80M.Q8_0.gguf \
  fb2a92e51dba7120d5369704502520814775b27c116d27ed039690106bf224ee
# Пути экранированы для bash, файл доступен только владельцу. Чужие модели не удаляем.
{
  printf 'export KASHA_WHISPER_CLI=%q\n' "$ROOT/whisper.cpp-$WHISPER_REV/build/bin/whisper-cli"
  printf 'export KASHA_WHISPER_MODEL=%q\n' "$ROOT/models/ggml-large-v3-turbo-q5_0.bin"
  printf 'export KASHA_LLAMA_CLI=%q\n' "$ROOT/llama.cpp-$LLAMA_REV/build/bin/llama-completion"
  printf 'export KASHA_LLAMA_MODEL=%q\n' "$ROOT/models/Kasha-Cleanup-0.6B-Q4_K_M.gguf"
  printf 'export KASHA_EMBEDDING_CLI=%q\n' "$ROOT/llama.cpp-$LLAMA_REV/build/bin/llama-embedding"
  printf 'export KASHA_ROUTING_MODEL=%q\n' "$ROOT/models/F2LLM-v2-80M.Q8_0.gguf"
  printf 'export KASHA_FFMPEG=%q\n' "$(command -v ffmpeg)"
} > "$ROOT/models.env.tmp"
mv "$ROOT/models.env.tmp" "$ROOT/models.env"
echo "Модели готовы. Настройки: $ROOT/models.env"
