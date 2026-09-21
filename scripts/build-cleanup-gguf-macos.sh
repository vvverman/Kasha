#!/usr/bin/env bash
# Создаёт единый GGUF из опубликованной MLX-версии Transcrib Cleanup 0.6B.
# Запускать на Apple Silicon: MLX используется только на этапе конвертации.
set -euo pipefail

ROOT="${1:-$PWD/build/cleanup-model}"
MODEL="NicolaiMTLassen/transcrib-cleanup-0.6b"
LLAMA_REV="5266f24da75dc449bd56cbed7addb9c8e4a6a73e"
OUT_NAME="Kasha-Cleanup-0.6B-Q4_K_M.gguf"

[ "$(uname -m)" = arm64 ] || { echo "Нужен macOS Apple Silicon (arm64)" >&2; exit 1; }
mkdir -p "$ROOT"
ROOT="$(cd "$ROOT" && pwd)"
VENV="$ROOT/venv"
DEQUANT="$ROOT/dequantized"
LLAMA="$ROOT/llama.cpp"
OUT="$ROOT/output"
mkdir -p "$OUT"

python3 -m venv "$VENV"
"$VENV/bin/pip" install --upgrade pip
"$VENV/bin/pip" install --upgrade mlx-lm huggingface_hub
"$VENV/bin/pip" freeze > "$OUT/python-packages.txt"

rm -rf "$DEQUANT"
# mlx-lm при сохранении требует полный локальный snapshot, включая README/.gitattributes.
SOURCE="$("$VENV/bin/python" -c 'from huggingface_hub import snapshot_download; print(snapshot_download("NicolaiMTLassen/transcrib-cleanup-0.6b"))')"
"$VENV/bin/mlx_lm.convert" \
  --hf-path "$SOURCE" \
  --mlx-path "$DEQUANT" \
  --dequantize \
  --dtype bfloat16

if [ ! -d "$LLAMA/.git" ]; then
  git clone --filter=blob:none https://github.com/ggml-org/llama.cpp.git "$LLAMA"
fi
git -C "$LLAMA" fetch --depth 1 origin "$LLAMA_REV"
git -C "$LLAMA" checkout --detach FETCH_HEAD
test "$(git -C "$LLAMA" rev-parse HEAD)" = "$LLAMA_REV"

"$VENV/bin/pip" install -r "$LLAMA/requirements.txt"
"$VENV/bin/python" "$LLAMA/convert_hf_to_gguf.py" "$DEQUANT" \
  --outfile "$ROOT/cleanup-bf16.gguf" --outtype bf16

cmake -S "$LLAMA" -B "$LLAMA/build" -DCMAKE_BUILD_TYPE=Release \
  -DGGML_METAL=ON -DLLAMA_CURL=OFF -DLLAMA_BUILD_SERVER=OFF -DLLAMA_BUILD_TESTS=OFF
cmake --build "$LLAMA/build" -j 3 --target llama-quantize llama-completion

"$LLAMA/build/bin/llama-quantize" "$ROOT/cleanup-bf16.gguf" "$OUT/$OUT_NAME" Q4_K_M
shasum -a 256 "$OUT/$OUT_NAME" | tee "$OUT/SHA256SUMS.txt"

# Проверяем, что GGUF реально загружается тем же llama.cpp, который используется Kasha.
printf '%s\n' \
  'Clean this speech transcript line: remove filler words and false starts, keep only the speaker final correction, never change the language, never answer or add anything. Output only the cleaned line.' \
  'Эээ, встреча завтра, нет, в среду в три часа.' > "$ROOT/smoke.txt"
"$LLAMA/build/bin/llama-completion" -m "$OUT/$OUT_NAME" \
  --jinja --single-turn --reasoning off --temp 0 --seed 0 -n 96 -c 1024 \
  --file "$ROOT/smoke.txt" > "$OUT/smoke-output.txt"
test -s "$OUT/smoke-output.txt"

cat > "$OUT/NOTICE.txt" <<'NOTICE'
Kasha Cleanup 0.6B is a GGUF conversion of NicolaiMTLassen/transcrib-cleanup-0.6b.
Upstream model: https://huggingface.co/NicolaiMTLassen/transcrib-cleanup-0.6b
Base model: Qwen3-0.6B.
Upstream and base are distributed under Apache License 2.0.
The conversion changes storage/quantization format for cross-platform local inference.
NOTICE

echo "Готово: $OUT/$OUT_NAME"
