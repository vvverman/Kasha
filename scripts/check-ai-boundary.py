#!/usr/bin/env python3
"""Проверяет AI boundary: Core знает роли/приватность, но не конкретные модели, providers и секреты."""
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
STUDIO = ROOT / 'modules/core/src/commonMain/kotlin/brain/studio/Studio.kt'
AI = ROOT / 'modules/core/src/commonMain/kotlin/brain/studio/AiEngines.kt'
REQUESTS = ROOT / 'modules/core/src/commonMain/kotlin/brain/studio/AiRequests.kt'
SETTINGS = ROOT / 'modules/ui/src/commonMain/kotlin/brain/studio/AiSettings.kt'
CATALOG = ROOT / 'modules/ai/catalog/src/commonMain/kotlin/brain/ai/KashaAiCatalog.kt'
CONNECTORS = ROOT / 'modules/ai/connectors/src/commonMain/kotlin/brain/ai/connectors/ExternalAiProtocol.kt'

errors = []

def data_class_header(text: str, name: str) -> str:
    match = re.search(rf'data class {re.escape(name)}\((.*?)\)\s*(?:\{{|$)', text, re.S)
    return match.group(1) if match else ''

for required in (STUDIO, AI, REQUESTS, SETTINGS, CATALOG, CONNECTORS):
    if not required.is_file():
        errors.append(f'Missing AI architecture file: {required.relative_to(ROOT)}')

if errors:
    print('AI boundary violations:', file=sys.stderr)
    print('\n'.join(f'- {e}' for e in errors), file=sys.stderr)
    sys.exit(1)

studio = STUDIO.read_text(encoding='utf-8')
ai = AI.read_text(encoding='utf-8')
requests = REQUESTS.read_text(encoding='utf-8')
settings = SETTINGS.read_text(encoding='utf-8')
catalog = CATALOG.read_text(encoding='utf-8')
connectors = CONNECTORS.read_text(encoding='utf-8')

for class_name, body in (
    ('Preferences', data_class_header(studio, 'Preferences')),
    ('CloudAiConnection', data_class_header(ai, 'CloudAiConnection')),
):
    if not body:
        errors.append(f'Missing serializable state class: {class_name}')
        continue
    for token in ('apiKey', 'api_key', 'secret', 'accessToken', 'bearerToken'):
        if re.search(re.escape(token), body, re.IGNORECASE):
            errors.append(f'{class_name} must not persist secret field: {token}')

for fragment in (
    'enum class AiRole { SPEECH_TO_TEXT, TEXT, ROUTING }',
    'const val CONSENT_VERSION',
    'privacyConsentVersion',
    'interface SpeechToTextEngine',
    'interface TextProcessingEngine',
    'interface RoutingEngine',
    'interface CloudAiGateway',
):
    if fragment not in ai:
        errors.append(f'Missing AI boundary contract: {fragment}')

if 'val apiKey: String? = null' not in requests:
    errors.append('API key may only cross Core as an explicit transient request payload')
if 'privacyConsentVersion = AiPrivacy.CONSENT_VERSION' not in settings:
    errors.append('Cloud connection UI must stamp current privacy consent version')
if not re.search(r'val\s+canSave\s*=\s*cloud\.available\s*&&\s*consent', settings):
    errors.append('Cloud connection actions must be gated by platform availability and explicit consent')
if settings.count('enabled = canSave') < 2:
    errors.append('Both cloud test and save actions must use the consent gate')

forbidden_core_fragments = (
    'object AiCatalog', 'local.whisper.', 'local.qwen', 'local.gemma',
    'CloudProviderDescriptor("openai"', 'CloudProviderDescriptor("anthropic"',
    'CloudProviderDescriptor("gemini"', 'CloudProviderDescriptor("openrouter"',
    'api.openai.com', 'api.anthropic.com', 'generativelanguage.googleapis.com', 'openrouter.ai',
)
for path in (ROOT / 'modules/core').rglob('*.kt'):
    text = path.read_text(encoding='utf-8')
    rel = path.relative_to(ROOT).as_posix()
    for fragment in forbidden_core_fragments:
        if fragment in text:
            errors.append(f'Concrete AI catalog leaked into Core: {rel}: {fragment}')
    if 'apiKey' in text and rel not in {
        'modules/core/src/commonMain/kotlin/brain/studio/AiEngines.kt',
        'modules/core/src/commonMain/kotlin/brain/studio/AiRequests.kt',
    }:
        errors.append(f'API key leaked into unrelated Core file: {rel}')

for fragment in (
    'Whisper Small', 'Qwen 4B', 'Gemma 4B',
    'CloudProviderDescriptor("openai"', 'CloudProviderDescriptor("anthropic"',
    'CloudProviderDescriptor("gemini"', 'CloudProviderDescriptor("openrouter"',
):
    if fragment not in catalog:
        errors.append(f'Concrete AI catalog is incomplete outside Core: {fragment}')

for fragment in (
    'class ExternalAiProtocol', 'interface AiHttpTransport',
    'api.openai.com', 'api.anthropic.com', 'generativelanguage.googleapis.com', 'openrouter.ai',
):
    if fragment not in connectors:
        errors.append(f'Shared AI connector protocol is incomplete: {fragment}')

if errors:
    print('AI boundary violations:', file=sys.stderr)
    print('\n'.join(f'- {e}' for e in errors), file=sys.stderr)
    sys.exit(1)
print('AI boundary: OK (one Core, three role ports, catalog + connectors outside Core, explicit consent, no persisted secrets)')
