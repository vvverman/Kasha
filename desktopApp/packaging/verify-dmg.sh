#!/usr/bin/env bash
set -euo pipefail
IMAGE=${1:?Укажите образ}
LOG_DIR=${2:?Укажите каталог журнала}
mkdir -p "$LOG_DIR"
# hdiutil может временно отказать при занятости DiskImages. Повреждение образа
# и другие ошибки не повторяются; успешная проверка целостности обязательна.
for attempt in 1 2 3; do
    log="$LOG_DIR/dmg-verify-$attempt.log"
    if LC_ALL=C hdiutil verify "$IMAGE" > "$log" 2>&1; then
        cat "$log"
        exit 0
    else
        status=$?
    fi
    cat "$log" >&2
    if ! grep -Fq 'Resource temporarily unavailable' "$log" || [ "$attempt" -eq 3 ]; then
        exit "$status"
    fi
    sleep "$((attempt * 5))"
done
exit 1
