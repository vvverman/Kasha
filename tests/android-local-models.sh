#!/usr/bin/env bash
set -euo pipefail
API=${1:?Укажите API проверяемого эмулятора}
OUT="$PWD/test-output/android-ai/$API"
mkdir -p "$OUT"
exec > >(tee "$OUT/execution.log") 2>&1
APP=$(find test-apks -type f -name '*.apk' ! -name '*androidTest*' | head -n 1)
TEST=$(find test-apks -type f -name '*androidTest*.apk' | head -n 1)
test -n "$APP" && test -n "$TEST"
adb root
adb wait-for-device
trap 'adb logcat -d > "$OUT/logcat.txt" 2>&1 || true' EXIT
adb install -r -t "$APP"
adb install -r -t "$TEST"
PACKAGE=ru.vrmn.kasha
DEST="/data/user/0/$PACKAGE/files/ai-fixtures"
# Данные fixture размещаются в app-private storage, без scoped-storage разрешений.
adb shell run-as "$PACKAGE" mkdir -p files/ai-fixtures
APP_UID=$(adb shell run-as "$PACKAGE" id -u | tr -d '\r')
[[ "$APP_UID" =~ ^[0-9]+$ ]]
adb push desktopApp/bundle/common/models/ggml-small.bin "$DEST/ggml-small.bin"
adb push desktopApp/bundle/common/models/Qwen3-4B-Q4_K_M.gguf "$DEST/Qwen3-4B-Q4_K_M.gguf"
adb push test-output/real-models/russian-with-pauses.wav "$DEST/russian-with-pauses.wav"
adb shell chown -R "$APP_UID:$APP_UID" "$DEST"
adb shell restorecon -RF "$DEST"
for file in ggml-small.bin Qwen3-4B-Q4_K_M.gguf russian-with-pauses.wav; do
    adb shell run-as "$PACKAGE" test -r "files/ai-fixtures/$file"
done
adb shell run-as "$PACKAGE" ls -ln files/ai-fixtures
adb shell rm -f "$DEST/android-local-models.json"
# Запрет исходящего IP-трафика только эмулятора; localhost оставлен для системных IPC.
adb shell 'iptables -I OUTPUT 1 ! -o lo -j REJECT'
adb shell 'ip6tables -I OUTPUT 1 ! -o lo -j REJECT'
adb shell iptables -S OUTPUT > "$OUT/ipv4-policy.txt"
adb shell ip6tables -S OUTPUT > "$OUT/ipv6-policy.txt"
timeout 1500 adb shell am instrument -w -r \
  -e class ru.vrmn.kasha.android.AndroidLocalModelsExecutionTest \
  ru.vrmn.kasha.test/androidx.test.runner.AndroidJUnitRunner | tee "$OUT/instrumentation.txt"
grep -q 'OK (1 test)' "$OUT/instrumentation.txt"
adb pull "$DEST/android-local-models.json" "$OUT/android-local-models.json"
python - "$OUT/android-local-models.json" "$API" <<'PY'
import json, pathlib, sys
path = pathlib.Path(sys.argv[1])
evidence = json.loads(path.read_text())
for flag in ('passed', 'nativeWhisper', 'nativeQwen', 'voiceSavedAsNote', 'restartPreserved', 'sourceUnchanged'):
    assert evidence.get(flag) is True, flag
assert evidence['externalNetworkReachable'] is False
assert evidence['routerRoles'] == 3
evidence['androidApi'] = int(sys.argv[2])
path.write_text(json.dumps(evidence, ensure_ascii=False, indent=2))
print('Реальный локальный AI и сохранение заметки: проверено, Android API', sys.argv[2])
PY
