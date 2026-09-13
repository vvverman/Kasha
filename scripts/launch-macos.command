#!/usr/bin/env bash
# Полный первый запуск web-оболочки Kasha на Mac. Данные и модели лежат вне репозитория.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
export PATH="/opt/homebrew/bin:/usr/local/bin:$PATH"
failed() { echo; echo "Запуск остановлен. Ошибка указана выше; заметки не удалялись."; read -r -p "Нажмите Enter, чтобы закрыть окно." _ || true; }
trap failed ERR

if [ "$(uname -s)" != Darwin ]; then
  echo "Для Linux используйте bash scripts/setup-models.sh и bash scripts/run-web.sh."
  exit 1
fi

PACKAGES=""
command -v cmake >/dev/null || PACKAGES="$PACKAGES cmake"
command -v ffmpeg >/dev/null || PACKAGES="$PACKAGES ffmpeg"
JAVA_HOME=$(/usr/libexec/java_home -v 21 2>/dev/null || true)
if [ -z "$JAVA_HOME" ] && command -v brew >/dev/null; then
  PREFIX=$(brew --prefix openjdk@21 2>/dev/null || true)
  [ ! -x "$PREFIX/libexec/openjdk.jdk/Contents/Home/bin/java" ] || JAVA_HOME="$PREFIX/libexec/openjdk.jdk/Contents/Home"
fi
[ -n "$JAVA_HOME" ] || PACKAGES="$PACKAGES openjdk@21"

if [ -n "$PACKAGES" ]; then
  command -v brew >/dev/null || { echo "Нужен Homebrew для установки:$PACKAGES. Установите Homebrew с brew.sh и повторите запуск."; false; }
  echo "Устанавливаются недостающие инструменты:$PACKAGES"
  brew install $PACKAGES
  [ -n "$JAVA_HOME" ] || JAVA_HOME="$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home"
fi

export JAVA_HOME
bash scripts/setup-models.sh
bash scripts/run-web.sh --open
