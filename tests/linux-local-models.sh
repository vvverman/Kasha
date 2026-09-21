#!/usr/bin/env bash
# Запускается в отдельном network namespace; сеть сборочного runner не меняется.
set -euo pipefail
cd "$(dirname "$0")/.."
OUT="$PWD/test-output/desktop-ai/linux"
mkdir -p "$OUT"
exec > >(tee "$OUT/execution.log") 2>&1
trap 'echo "Сбой Linux AI: строка $LINENO, команда $BASH_COMMAND" >&2' ERR
ip link set lo up
APP="$PWD/desktopApp/build/compose/binaries/main/app/Kasha"
RES="$APP/lib/app/resources"
ls -l "$APP/bin" "$RES/bin"
test -x "$APP/bin/Kasha"
test -x "$RES/bin/whisper-cli"
test -x "$RES/bin/llama-completion"
test -x "$RES/bin/ffmpeg"
export PATH="$RES/bin:$PATH"
export KASHA_WHISPER_CLI="$RES/bin/whisper-cli"
export KASHA_WHISPER_MODEL="$RES/models/ggml-small.bin"
export KASHA_LLAMA_CLI="$RES/bin/llama-completion"
export KASHA_LLAMA_MODEL="$RES/models/Qwen3-4B-Q4_K_M.gguf"
export KASHA_FFMPEG="$RES/bin/ffmpeg"
export HF_HUB_OFFLINE=1
python3 - <<'PY'
import socket
try:
    connection = socket.create_connection(('1.1.1.1', 443), timeout=2)
except OSError:
    print('Внешняя сеть namespace недоступна')
else:
    connection.close()
    raise SystemExit('Сеть не изолирована')
PY
timeout 1200 "$APP/bin/Kasha" --self-test "$OUT/application" \
    "$PWD/test-output/real-models/russian-with-pauses.wav" 2>&1 | tee "$OUT/application.log"
export KASHA_HOME="$OUT/web-data"
mkdir -p "$KASHA_HOME"
bash runtime/build/install/runtime/bin/runtime > "$OUT/web-runtime.log" 2>&1 &
PID=$!
trap 'kill "$PID" 2>/dev/null || true' EXIT
timeout 1200 python3 tests/real_models.py 2>&1 | tee "$OUT/web-execution.log"
cp test-output/real-models/result.json "$OUT/web-result.json"
python3 - "$OUT" <<'PY'
import json, pathlib, sys
root = pathlib.Path(sys.argv[1])
for path in (root/'application/self-test.json', root/'web-result.json'):
    result = json.loads(path.read_text())
    assert result['passed'] is True
    assert result['applicationRouter'] is True
    assert result['externalNetworkReachable'] is False
print('Linux/Desktop и Web runtime: настоящие выбранные модели исполнены без внешней сети')
PY
