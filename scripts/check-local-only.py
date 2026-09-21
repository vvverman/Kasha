#!/usr/bin/env python3
"""Граница local-first: запрещает cloud sync/backend/telemetry; внешний AI разрешён только через изолированный consented adapter."""
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
SCOPES = [ROOT / 'kashaCore', ROOT / 'aiCatalog', ROOT / 'composeApp', ROOT / 'runtime', ROOT / 'desktopApp', ROOT / 'androidApp']
SKIP = {'build', 'test', 'commonTest', 'node_modules'}

# Эти сервисы нарушают продуктовую границу независимо от AI-настроек.
FORBIDDEN_GLOBAL = re.compile(
    r'(firebase|firestore|supabase|sentry|segment\.io|(?:com[.:/]amplitude|amplitude(?:\.com|[-:]android|[-:]kotlin)|AmplitudeClient)|mixpanel|appcenter|'
    r'\bicloud\b|\bcloudkit\b)',
    re.IGNORECASE,
)

# Внешний inference допустим, но реальные сетевые endpoint-ы должны жить только
# внутри специально выделенных adapter-ов. Каталог/общий UI могут знать имена
# провайдеров, но не URL и не сетевую реализацию.
EXTERNAL_AI_ENDPOINT = re.compile(
    r'(api\.openai\.com|api\.anthropic\.com|generativelanguage\.googleapis\.com|openrouter\.ai)',
    re.IGNORECASE,
)
ALLOWED_EXTERNAL_AI_PREFIXES = (
    'aiCatalog/src/commonMain/kotlin/brain/ai/external/',
    'androidApp/src/main/kotlin/brain/android/external/',
    'runtime/src/main/kotlin/brain/runtime/ai/external/',
    'desktopApp/src/main/kotlin/brain/desktop/ai/external/',
    'composeApp/src/iosMain/kotlin/brain/ios/external/',
    'apps/',
)

violations = []
for scope in SCOPES:
    if not scope.exists():
        continue
    for path in scope.rglob('*'):
        if not path.is_file() or any(part in SKIP for part in path.relative_to(scope).parts):
            continue
        try:
            text = path.read_text(encoding='utf-8')
        except (UnicodeDecodeError, OSError):
            continue
        rel = path.relative_to(ROOT).as_posix()
        for number, line in enumerate(text.splitlines(), 1):
            if FORBIDDEN_GLOBAL.search(line):
                violations.append(f'{rel}:{number}: forbidden cloud/sync/telemetry: {line.strip()}')
            if EXTERNAL_AI_ENDPOINT.search(line) and not rel.startswith(ALLOWED_EXTERNAL_AI_PREFIXES):
                violations.append(f'{rel}:{number}: external AI endpoint outside isolated adapter: {line.strip()}')

if violations:
    print('Нарушена local-first граница Kasha:', file=sys.stderr)
    print('\n'.join(violations), file=sys.stderr)
    sys.exit(1)
print('Local-first boundary: OK (external AI only through isolated adapters)')
