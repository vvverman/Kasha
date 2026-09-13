#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
OUT="$PWD/studio-output"
APP="$PWD/desktopApp/build/compose/binaries/main/app/Kasha Test.app"
mkdir -p "$OUT"
exec > >(tee "$OUT/package.log") 2>&1
RES="$APP/Contents/app/resources"
test -f "$RES/demo-mode.txt"
test ! -d "$RES/models"
test ! -e "$RES/bin/whisper-cli"
test ! -e "$RES/bin/llama-completion"
chmod 755 "$RES/bin/ffmpeg"
while IFS= read -r -d '' file; do
 if /usr/bin/file -b "$file" | grep -q 'Mach-O'; then codesign --force --sign - --timestamp=none "$file"; fi
done < <(find "$APP/Contents" -type f -print0)
codesign --force --deep --sign - --timestamp=none --entitlements desktopApp/packaging/entitlements.plist "$APP"
codesign --verify --deep --strict "$APP"
# Не даём приложению случайно использовать сборочный каталог или Java из PATH.
mv desktopApp/bundle-test/common desktopApp/bundle-test/build-copy-not-used
mkdir -p "$OUT/clean-home"
env -i HOME="$OUT/clean-home" PATH=/usr/bin:/bin:/usr/sbin:/sbin TMPDIR="${TMPDIR:-/tmp}" \
 /usr/bin/sandbox-exec -p '(version 1)(allow default)(deny network*)' \
 "$APP/Contents/MacOS/Kasha Test" --self-test-demo "$OUT/self-test" > "$OUT/self-test.log" 2>&1
for screen in home note settings projects language-dark note-dark; do
 env -i HOME="$OUT/clean-home" PATH=/usr/bin:/bin:/usr/sbin:/sbin TMPDIR="${TMPDIR:-/tmp}" \
 KASHA_HOME="$OUT/ui-$screen" "$APP/Contents/MacOS/Kasha Test" --ui-smoke "$OUT/screens" "$screen" > "$OUT/$screen.log" 2>&1
 test -f "$OUT/screens/$screen.png"
done
STAGE="$OUT/volume"
mkdir -p "$STAGE"
mv "$APP" "$STAGE/Kasha Test.app"
ln -s /Applications "$STAGE/Applications"
cp docs/TEST_BUILD.md "$STAGE/Прочитать.md"
hdiutil create -volname 'Kasha Test' -srcfolder "$STAGE" -ov -format UDZO "$OUT/Kasha-Test-1.1.4-macOS-arm64.dmg"
hdiutil verify "$OUT/Kasha-Test-1.1.4-macOS-arm64.dmg"
mkdir -p "$OUT/mounted"
hdiutil attach -nobrowse -readonly -mountpoint "$OUT/mounted" "$OUT/Kasha-Test-1.1.4-macOS-arm64.dmg"
codesign --verify --deep --strict "$OUT/mounted/Kasha Test.app"
test -x "$OUT/mounted/Kasha Test.app/Contents/app/resources/bin/ffmpeg"
test ! -d "$OUT/mounted/Kasha Test.app/Contents/app/resources/models"
hdiutil detach "$OUT/mounted"
(cd "$OUT" && shasum -a 256 Kasha-Test-1.1.4-macOS-arm64.dmg > SHA256SUMS.txt)
python3 - <<'PY'
import pathlib,json,platform
p=pathlib.Path('studio-output');dmg=p/'Kasha-Test-1.1.4-macOS-arm64.dmg'
r={'passed':True,'file':dmg.name,'bytes':dmg.stat().st_size,'arch':platform.machine(),'macOS':platform.mac_ver()[0],
 'simulatedAI':True,'modelFiles':0,'bundledJava':True,'notarized':False,'physicalMicrophoneTested':False,
 'selfTest':json.loads((p/'self-test/self-test.json').read_text())}
assert dmg.stat().st_size < 500*1024*1024
(p/'BUILD-REPORT.json').write_text(json.dumps(r,ensure_ascii=False,indent=2))
PY
