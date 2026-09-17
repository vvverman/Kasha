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
# До instrument приложение остановлено: публикуем сразу полный набор тестовых данных.
adb shell am force-stop ru.vrmn.kasha
adb shell "run-as ru.vrmn.kasha sh -c 'test ! -e files/Kasha'"
APP_UID=$(adb shell run-as ru.vrmn.kasha id -u | tr -d '\r')
APP_GID=$(adb shell run-as ru.vrmn.kasha id -g | tr -d '\r')
TOKEN=$(python3 -c 'import uuid; print(uuid.uuid4())')
python3 - "$OUT/fixture.tar" "$TOKEN" "$APP_UID" "$APP_GID" <<'PYFIXTURE'
import io, json, math, struct, sys, tarfile, time, uuid, wave
now = int(time.time() * 1000)
id = lambda: str(uuid.uuid4())
project = dict(id=id(), title='Проект приёмки', instruction='Тестовые данные', createdAt=now, updatedAt=now)
note = dict(id=id(), projectId=project['id'], title='Сохранённая заметка',
            body='Сохранённая заметка\nИсходный текст.', createdAt=now, updatedAt=now)
stream = io.BytesIO()
with wave.open(stream, 'wb') as wav:
    wav.setparams((1, 2, 16000, 0, 'NONE', 'not compressed'))
    wav.writeframes(b''.join(struct.pack('<h', int(math.sin(i * math.tau * 440 / 16000) * 1500)) for i in range(48000)))
audio = stream.getvalue()
files, captures = {}, []
for text, note_id, corrupt in [('Первая запись', note['id'], False), ('Вторая запись', note['id'], False),
        ('Повреждённый источник', note['id'], True), ('Готовый текст\nПроверка сохранения в заметку.', None, False)]:
    capture_id = id()
    path = f'audio/{capture_id}/source.wav'
    files['Kasha/' + path] = b'not an audio file' if corrupt else audio
    captures.append(dict(id=capture_id, createdAt=now, title=text.splitlines()[0], preparedText=text,
        transcript=text, status='READY', audioFileName=path, noteId=note_id, audioFinalized=True, durationSeconds=3.0))
tasks = [dict(id=id(), text='Проверить напоминание', createdAt=now, updatedAt=now,
              dueAt=now + 86400000, reminderRepeat='DAILY'),
         dict(id=id(), text='Завершённая задача', createdAt=now, updatedAt=now,
              dueAt=now, completedAt=now, nextReminderAt=9223372036854775807)]
files['Kasha/brain.json'] = json.dumps(dict(projects=[project], notes=[note], captures=captures, tasks=tasks), ensure_ascii=False).encode()
files['Kasha/preferences.json'] = json.dumps(dict(autoRecord=False, autoRoute=False, language='ru', theme='light',
    ai=dict(speechToText='native.android.speech', text='native.kasha.rules', routing='native.kasha.rules'))).encode()
files['platform-fixture.token'] = sys.argv[2].encode()
with tarfile.open(sys.argv[1], 'w') as archive:
    for name, value in files.items():
        info=tarfile.TarInfo(name); info.size=len(value); info.mode=0o600
        # Старый Android tar восстанавливает владельца: это UID/GID приложения, не root.
        info.uid=int(sys.argv[3]); info.gid=int(sys.argv[4])
        archive.addfile(info, io.BytesIO(value))
PYFIXTURE
adb shell run-as ru.vrmn.kasha mkdir -p files
adb shell -T run-as ru.vrmn.kasha tar -xf - -C files < "$OUT/fixture.tar"
timeout 600 adb shell am instrument -w -r \
  -e class ru.vrmn.kasha.android.AndroidPlatformAcceptanceTest \
  -e kashaIsolated true -e kashaFixtureToken "$TOKEN" \
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
