#!/usr/bin/env bash
# Только отдельный свежий эмулятор; без моделей, внешних ключей и пользовательских данных.
set -euo pipefail
API=${1:?Укажите API эмулятора}
OUT="$PWD/test-output/android-platform/$API"
mkdir -p "$OUT"
APP=$(find test-apks -type f -name '*.apk' ! -name '*androidTest*' | head -n 1)
TEST=$(find test-apks -type f -name '*androidTest*.apk' | head -n 1)
test -n "$APP" && test -n "$TEST"
collect() {
  adb logcat -d > "$OUT/logcat.txt" 2>&1 || true
  adb exec-out run-as ru.vrmn.kasha tar -cf - -C files platform-acceptance > "$OUT/evidence.tar" 2>/dev/null || true
  if test -s "$OUT/evidence.tar"; then tar -xf "$OUT/evidence.tar" -C "$OUT" || true; fi
}
trap collect EXIT
adb install -r -g -t "$APP"
adb install -r -g -t "$TEST"
adb shell input keyevent 82
# Внутри теста также проверяется, что установка не содержит прежних данных.
timeout 600 adb shell am instrument -w -r \
  -e class ru.vrmn.kasha.android.AndroidPlatformAcceptanceTest \
  -e kashaIsolated true \
  ru.vrmn.kasha.test/androidx.test.runner.AndroidJUnitRunner | tee "$OUT/instrumentation.txt"
grep -q 'OK (1 test)' "$OUT/instrumentation.txt"
collect
python3 - "$OUT/platform-acceptance/result.json" "$API" <<'PY'
import json, sys
result = json.load(open(sys.argv[1]))
assert result['passed'] is True, result
assert result['androidApi'] == int(sys.argv[2]), result
assert result['physicalDevice'] is False
assert len(result['screenshots']) >= 16, result
print('Android non-AI platform acceptance: PASS, API', result['androidApi'])
PY
