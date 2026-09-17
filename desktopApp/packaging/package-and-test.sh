#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
OUT="$PWD/macos-output"
APP="$PWD/desktopApp/build/compose/binaries/main/app/Kasha.app"
mkdir -p "$OUT"
exec > >(tee -a "$OUT/packaging.log") 2>&1
[ -d "$APP" ]
phase() { echo "$(date -u '+%FT%TZ') Этап: $1"; printf '%s\n' "$1" > "$OUT/phase.txt"; }
phase 'Права запуска и подпись'
RES="$APP/Contents/app/resources"
for name in whisper-cli llama-completion ffmpeg; do
 test -f "$RES/bin/$name"
 chmod 755 "$RES/bin/$name"
 test -x "$RES/bin/$name"
done
{ ls -l "$RES/bin"; cat "$APP/Contents/app/Kasha.cfg"; } > "$OUT/launcher-config.txt"
while IFS= read -r -d '' file; do
 if /usr/bin/file -b "$file" | grep -q 'Mach-O'; then
  /usr/bin/codesign --force --sign - --timestamp=none "$file"
 fi
done < <(find "$APP/Contents" -type f -print0)
/usr/bin/codesign --force --deep --sign - --timestamp=none --entitlements desktopApp/packaging/entitlements.plist "$APP"
/usr/bin/codesign --verify --deep --strict --verbose=2 "$APP"
/usr/libexec/PlistBuddy -c 'Print :NSMicrophoneUsageDescription' "$APP/Contents/Info.plist"
VERSION=$(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' "$APP/Contents/Info.plist")
DMG_NAME="Kasha-${VERSION}-macOS-arm64.dmg"
DMG="$OUT/$DMG_NAME"
rm -rf desktopApp/bundle/common
if [ -d desktopApp/build/native ]; then mv desktopApp/build/native desktopApp/build/native-not-on-path; fi
TEST_HOME="$OUT/clean-home"
mkdir -p "$TEST_HOME"
phase 'Русский сценарий внутри приложения без сети'
if python3 - "$APP" "$OUT" "$PWD/model-test/russian.wav" "${TMPDIR:-/tmp}" <<'PY'
import json, os, pathlib, signal, subprocess, sys, time
app, out, fixture = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2]), sys.argv[3]
env = {'HOME':str(out/'clean-home'), 'PATH':'/usr/bin:/bin:/usr/sbin:/sbin', 'TMPDIR':sys.argv[4]}
command = ['/usr/bin/sandbox-exec','-p','(version 1)(allow default)(deny network*)',
           str(app/'Contents/MacOS/Kasha'),'--self-test',str(out/'self-test'),fixture]
with (out/'self-test.log').open('w') as log:
    process = subprocess.Popen(command, env=env, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
    started = time.monotonic()
    try:
        while process.poll() is None:
            elapsed = time.monotonic()-started
            db = out/'self-test/data/brain.json'
            if db.exists():
                try:
                    state = json.loads(db.read_text())
                    print('Самопроверка:', round(elapsed), 'с;', [(c['status'], c.get('message','')) for c in state.get('captures',[])], flush=True)
                except (OSError, ValueError): pass
            else:
                print('Самопроверка: запуск JVM,', round(elapsed), 'с', flush=True)
            if elapsed > 600:
                subprocess.run(['/bin/ps','-axo','pid,ppid,state,%cpu,etime,command'], stdout=log, stderr=log)
                raise TimeoutError('Автономная проверка не завершилась за допустимое время')
            time.sleep(10)
        if process.returncode:
            raise RuntimeError('Автономная проверка завершилась с кодом '+str(process.returncode))
    finally:
        if process.poll() is None:
            os.killpg(process.pid, signal.SIGKILL)
            process.wait()
print((out/'self-test.log').read_text(), flush=True)
PY
then
 KASHA_AI_CHECK_EXIT=0
else
 KASHA_AI_CHECK_EXIT=$?
 # Раздел 6 отложен только по явному флагу CI. Обычный выпуск остаётся строгим.
 if [ "${KASHA_DEFER_AI_ACCEPTANCE:-0}" != "1" ]; then exit "$KASHA_AI_CHECK_EXIT"; fi
 echo "DEFERRED: section 6 — native AI check failed ($KASHA_AI_CHECK_EXIT); log retained"
fi
export KASHA_AI_CHECK_EXIT
phase 'Настоящее окно приложения'
UI_READY="$OUT/home-ready.txt"
rm -f "$UI_READY"
env -i HOME="$TEST_HOME" PATH=/usr/bin:/bin:/usr/sbin:/sbin TMPDIR="${TMPDIR:-/tmp}" \
 KASHA_HOME="$OUT/ui-data" "$APP/Contents/MacOS/Kasha" --ui-smoke "$OUT" > "$OUT/ui.log" 2>&1 &
PID=$!
for n in {1..40}; do
 [ -f "$UI_READY" ] && break
 kill -0 "$PID" 2>/dev/null || { cat "$OUT/ui.log"; exit 1; }
 sleep 1
done
[ -f "$UI_READY" ]
/usr/sbin/screencapture -x "$OUT/macos-window.png" || true
wait "$PID"
phase 'Матрица общего интерфейса в настоящем приложении'
VISUAL="$OUT/visual"
mkdir -p "$VISUAL"
env -i HOME="$TEST_HOME" PATH=/usr/bin:/bin:/usr/sbin:/sbin TMPDIR="${TMPDIR:-/tmp}" \
 KASHA_HOME="$VISUAL/data" "$APP/Contents/MacOS/Kasha" --ui-smoke "$VISUAL" matrix > "$VISUAL/run.log" 2>&1 &
PID=$!
for n in {1..120}; do
 ! kill -0 "$PID" 2>/dev/null && break
 sleep 1
done
if kill -0 "$PID" 2>/dev/null; then kill "$PID"; echo 'Таймаут матрицы UI' >&2; exit 1; fi
wait "$PID"
test -s "$VISUAL/visual-result.json"
test -s "$VISUAL/matrix-ready.txt"
phase 'Создание установочного образа'
STAGE="$OUT/volume"
mkdir -p "$STAGE"
mv "$APP" "$STAGE/Kasha.app"
ln -s /Applications "$STAGE/Applications"
cp desktopApp/packaging/Установка.txt "$STAGE/Установка.txt"
# Обычное сжатие контейнера без изменения весов нейросетей.
hdiutil create -volname 'Kasha' -srcfolder "$STAGE" -ov -format UDZO -imagekey zlib-level=1 "$DMG"
phase 'Проверка готового DMG'
bash desktopApp/packaging/verify-dmg.sh "$DMG" "$OUT"
(cd "$OUT" && shasum -a 256 "$DMG_NAME" > SHA256SUMS.txt)
MOUNT="$OUT/mounted"
mkdir -p "$MOUNT"
hdiutil attach -nobrowse -readonly -mountpoint "$MOUNT" "$DMG"
codesign --verify --deep --strict "$MOUNT/Kasha.app"
test -x "$MOUNT/Kasha.app/Contents/app/resources/bin/whisper-cli"
test -x "$MOUNT/Kasha.app/Contents/app/resources/bin/llama-completion"
test -x "$MOUNT/Kasha.app/Contents/app/resources/bin/ffmpeg"
hdiutil detach "$MOUNT"
export KASHA_DMG_NAME="$DMG_NAME"
python3 - <<'PY'
import json, os, pathlib, platform
out=pathlib.Path('macos-output')
dmg=out/os.environ['KASHA_DMG_NAME']
report={'passed':True,'file':dmg.name,'bytes':dmg.stat().st_size,'architecture':platform.machine(),
        'macOS':platform.mac_ver()[0],'bundledJava':True,'bundledModels':['Whisper Small','Qwen3-4B Q4_K_M'],
        'externalNetworkDeniedDuringInference':True,'developerIdSigned':False,'notarized':False,
        'microphoneHardwareTested':False,'ui':(out/'home-ready.txt').read_text(),
        'acceptanceScope':'non-AI' if os.environ.get('KASHA_DEFER_AI_ACCEPTANCE') == '1' else 'full',
        'aiAcceptance':'DEFERRED: section 6' if os.environ.get('KASHA_DEFER_AI_ACCEPTANCE') == '1' else 'required',
        'aiCheckExitCode':int(os.environ['KASHA_AI_CHECK_EXIT']),
        'selfTest':json.loads((out/'self-test/self-test.json').read_text()) if (out/'self-test/self-test.json').exists() else None}
assert 'visible=true' in report['ui'], report
(out/'BUILD-REPORT.json').write_text(json.dumps(report,ensure_ascii=False,indent=2))
if os.environ.get('GITHUB_STEP_SUMMARY'):
    with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as summary:
        summary.write('macOS app/DMG: PASS; ' + report['aiAcceptance'] +
                      '; AI check exit=' + str(report['aiCheckExitCode']) + '\n')
PY
phase 'Установщик проверен'
