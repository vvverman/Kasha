"""Диагностика только встроенных синтетических фраз, не приёмка продукта.

Сохраняет вход и stdout/stderr настоящей модели отдельно, чтобы отличить
ошибку протокола от изменения смысла. Пользовательские файлы не принимает.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]
SYSTEM = (
    "Clean this speech transcript line: remove filler words and false starts, "
    "keep only the speaker's final correction, never change the language, "
    "never answer or add anything. Output only the cleaned line."
)
SAMPLES = {
    "acceptance": "В проекте приложения нужно исправить запись голоса. Добавить кнопку паузы и проверить сохранение заметок. Старый текст удалять нельзя.",
    "sentence-1": "В проекте приложения нужно исправить запись голоса.",
    "sentence-2": "Добавить кнопку паузы и проверить сохранение заметок.",
    "sentence-negation": "Старый текст удалять нельзя.",
    "correction": "Эээ, встреча завтра, нет, в среду в три часа.",
}


def current_prompt(text):
    source = (ROOT / 'kashaCore/src/commonMain/kotlin/brain/domain/LocalModelText.kt').read_text()
    match = re.search(r'fun cleanupPrompt\(text: String\): String = """\n(.*?)\n    """.trimIndent\(\)', source, re.S)
    if not match:
        raise RuntimeError('Изменился формат cleanupPrompt; диагностику нужно обновить явно')
    lines = match[1].splitlines()
    indent = min(len(line) - len(line.lstrip()) for line in lines if line.strip())
    return '\n'.join(line[indent:] for line in lines).replace('$text', text)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--bundle', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    args.bundle = args.bundle.resolve()
    args.out.mkdir(parents=True, exist_ok=True)
    cli = args.bundle / 'bin/llama-completion'
    manifest = (ROOT / 'aiCatalog/src/commonMain/kotlin/brain/ai/ModelArtifacts.kt').read_text()
    match = re.search(r'AiSelection.DEFAULT_TEXT,\s*"([^"]+)",\s*"[^"]+",\s*"([a-f0-9]{64})"', manifest)
    if not match:
        raise RuntimeError('Cleanup отсутствует в manifest')
    model = args.bundle / 'models' / match[1]
    with model.open('rb') as stream:
        digest = hashlib.file_digest(stream, 'sha256').hexdigest()
    if digest != match[2]:
        raise RuntimeError('SHA-256 Cleanup не совпал с manifest')
    system = args.out / 'system.txt'
    system.write_text(SYSTEM, encoding='utf-8')
    env = {k: v for k, v in os.environ.items() if not k.startswith('LLAMA_ARG_')}
    env.update(HF_HUB_OFFLINE='1', GGML_METAL_DEVICES='0')
    report = {'diagnosticOnly': True, 'modelSha256': digest, 'samples': []}
    execution_failed = False
    for sample, text in SAMPLES.items():
        for variant in ('current', 'system-user'):
            name = sample + '-' + variant
            prompt = args.out / (name + '-input.txt')
            prompt.write_text(current_prompt(text) if variant == 'current' else text, encoding='utf-8')
            command = [str(cli), '-m', str(model), '--jinja', '--single-turn', '--reasoning', 'off',
                       '--no-display-prompt', '--simple-io', '--no-escape', '--file', str(prompt),
                       '-n', '512', '-c', '8192', '--temp', '0', '--seed', '0', '-t', '4',
                       '--n-gpu-layers', '0', '--device', 'none']
            if variant == 'system-user':
                command += ['--system-prompt-file', str(system)]
            started = time.monotonic()
            with (args.out / (name + '-stdout.txt')).open('w') as stdout, (args.out / (name + '-stderr.txt')).open('w') as stderr:
                try:
                    result = subprocess.run(command, env=env, stdin=subprocess.DEVNULL, stdout=stdout, stderr=stderr, timeout=180)
                    code = result.returncode
                except subprocess.TimeoutExpired:
                    code = 'timeout'
            output = (args.out / (name + '-stdout.txt')).read_text(encoding='utf-8')
            execution_failed |= code != 0
            row = {'sample': sample, 'variant': variant, 'exit': code, 'seconds': round(time.monotonic()-started, 2),
                   'input': text, 'output': output}
            report['samples'].append(row)
            (args.out / 'result.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
            print(json.dumps(row, ensure_ascii=False), flush=True)
    if execution_failed:
        raise RuntimeError('Один из диагностических запусков не завершился; см. артефакты')


if __name__ == '__main__':
    main()
