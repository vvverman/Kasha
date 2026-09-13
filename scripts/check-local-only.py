#!/usr/bin/env python3
"""Граница local-first: запрещает cloud sync/backend/telemetry; внешний AI разрешён только в AI connector/infrastructure слоях."""
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
SCOPES = [ROOT / 'modules', ROOT / 'platforms']
SKIP = {'build', 'test', 'commonTest', 'node_modules'}

FORBIDDEN_GLOBAL = re.compile(
    r'(firebase|firestore|supabase|sentry|segment\.io|amplitude|mixpanel|appcenter|'
    r'\bicloud\b|\bcloudkit\b)',
    re.IGNORECASE,
)
EXTERNAL_AI_ENDPOINT = re.compile(
    r'(api\.openai\.com|api\.anthropic\.com|generativelanguage\.googleapis\.com|openrouter\.ai)',
    re.IGNORECASE,
)
ALLOWED_EXTERNAL_AI_PREFIXES = (
    'modules/ai/connectors/',
    # Transitional JVM transport until the old Java provider client is removed.
    'modules/infrastructure/jvm/src/main/kotlin/brain/runtime/ai/external/',
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
                violations.append(f'{rel}:{number}: external AI endpoint outside isolated connector: {line.strip()}')

if violations:
    print('Нарушена local-first граница Kasha:', file=sys.stderr)
    print('\n'.join(violations), file=sys.stderr)
    sys.exit(1)
print('Local-first boundary: OK (external AI isolated from Core/product data)')
