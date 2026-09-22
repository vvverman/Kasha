#!/usr/bin/env bash
set -euo pipefail
API=${1:?Укажите API проверяемого эмулятора}
OUT="$PWD/test-output/android-ai/$API"
mkdir -p "$OUT"
exec > >(tee "$OUT/execution.log") 2>&1
APP=$(find test-apks -type f -name '*.apk' ! -name '*androidTest*' | head -n 1)
TEST=$(find test-apks -type f -name '*androidTest*.apk' | head -n 1)
test -n "$APP" && test -n "$TEST"
adb_root_ready() {
    local attempt uid
    for attempt in 1 2 3 4 5; do
        adb wait-for-device
        # adb root перезапускает adbd и на API 35 иногда закрывает transport раньше,
        # чем клиент получает успешный exit code. Проверяем фактический uid после reconnect.
        adb root >"$OUT/adb-root-$attempt.txt" 2>&1 || true
        sleep 1
        adb wait-for-device || true
        uid=$(adb shell id -u 2>/dev/null | tr -d '\r' || true)
        if [ "$uid" = "0" ]; then
            echo "adb root ready on attempt $attempt"
            return 0
        fi
        sleep 1
    done
    cat "$OUT"/adb-root-*.txt >&2 || true
    echo "Не удалось получить root adbd после 5 попыток" >&2
    return 1
}
adb_root_ready
collect_diagnostics() {
    adb logcat -d > "$OUT/logcat.txt" 2>&1 || true
    adb shell cat /data/user/0/ru.vrmn.kasha/files/ai-fixtures/phase.txt > "$OUT/phase.txt" 2>&1 || true
    if [ ! -f "$OUT/android-local-models.json" ]; then
        local pid
        pid=$(adb shell pidof ru.vrmn.kasha | tr -d '\r')
        if [[ "$pid" =~ ^[0-9]+$ ]]; then
            timeout 20 adb shell debuggerd -b "$pid" > "$OUT/native-stack.txt" 2>&1 || true
        fi
    fi
}
trap collect_diagnostics EXIT
adb install -r -t "$APP"
adb install -r -t "$TEST"
PACKAGE=ru.vrmn.kasha
DEST="/data/user/0/$PACKAGE/files/ai-fixtures"
adb shell run-as "$PACKAGE" mkdir -p files/ai-fixtures
APP_UID=$(adb shell run-as "$PACKAGE" id -u | tr -d '\r')
[[ "$APP_UID" =~ ^[0-9]+$ ]]
adb push desktopApp/bundle/common/models/ggml-large-v3-turbo-q5_0.bin "$DEST/ggml-large-v3-turbo-q5_0.bin"
adb push desktopApp/bundle/common/models/Kasha-Cleanup-0.6B-Q4_K_M.gguf "$DEST/Kasha-Cleanup-0.6B-Q4_K_M.gguf"
adb push desktopApp/bundle/common/models/F2LLM-v2-80M.Q8_0.gguf "$DEST/F2LLM-v2-80M.Q8_0.gguf"
adb push test-output/real-models/russian-with-pauses.wav "$DEST/russian-with-pauses.wav"
adb shell chown -R "$APP_UID:$APP_UID" "$DEST"
adb shell restorecon -RF "$DEST"
for file in ggml-large-v3-turbo-q5_0.bin Kasha-Cleanup-0.6B-Q4_K_M.gguf F2LLM-v2-80M.Q8_0.gguf russian-with-pauses.wav; do
    # test — команда shell; отдельный exec test недоступен под run-as на API26.
    adb shell "run-as $PACKAGE sh -c 'test -r files/ai-fixtures/$file'"
done
adb shell run-as "$PACKAGE" ls -ln files/ai-fixtures
adb shell rm -f "$DEST/android-local-models.json"
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
for flag in ('passed', 'nativeWhisper', 'nativeCleanup', 'nativeRouting', 'voiceSavedAsNote', 'restartPreserved', 'sourceUnchanged'):
    assert evidence.get(flag) is True, flag
assert evidence['externalNetworkReachable'] is False
assert evidence['routerRoles'] == 3
assert evidence.get('fixture') == 'synthetic-russian-speech'
evidence['androidApi'] = int(sys.argv[2])
path.write_text(json.dumps(evidence, ensure_ascii=False, indent=2))
print('Реальный локальный AI и сохранение заметки: проверено, Android API', sys.argv[2])
PY
