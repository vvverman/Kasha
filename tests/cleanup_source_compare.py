"""Сравнение исходного MLX и поставляемого GGUF только на встроенных фразах.

Диагностика не меняет manifest, не публикует веса и не объявляет приёмку.
prepare скачивает один закреплённый snapshot; run работает без внешней сети.
Результаты каждого запуска сохраняются даже при ошибке следующего.
"""
import argparse
import hashlib
import importlib.metadata
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time

from cleanup_cli_probe import ROOT, SAMPLES, SYSTEM, current_prompt

SOURCE = 'NicolaiMTLassen/transcrib-cleanup-0.6b'
MLX_LM_VERSION = '0.31.3'
LIMIT = 512


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8')


def sha256(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def native_prompt(value):
    # Тот же байтовый ChatML-префикс, что использует текущий iOS-адаптер.
    source = (ROOT / 'iosApp/native/llama.cpp').read_text(encoding='utf-8')
    parts = re.findall(r'append\(tokens, vocab, ("(?:\\.|[^"\\])*"), true\);', source)
    if len(parts) != 2:
        raise RuntimeError('Изменился native chat protocol; требуется явная сверка')
    prefix, suffix = [json.loads(part) for part in parts]
    return prefix + current_prompt(value) + suffix


def upstream_prompt(tokenizer, value):
    return tokenizer.apply_chat_template(
        [{'role': 'system', 'content': SYSTEM}, {'role': 'user', 'content': value}],
        tokenize=False, add_generation_prompt=True, enable_thinking=False,
    )


def prepare(args):
    from huggingface_hub import HfApi, snapshot_download
    from transformers import AutoTokenizer
    args.out.mkdir(parents=True, exist_ok=True)
    if importlib.metadata.version('mlx-lm') != MLX_LM_VERSION:
        raise RuntimeError('Не совпала закреплённая версия mlx-lm')
    # Имя ветки разрешается только здесь; все файлы далее скачиваются по одному SHA.
    revision = HfApi().model_info(SOURCE, revision=args.revision).sha
    if not re.fullmatch('[a-f0-9]{40}', revision or ''):
        raise RuntimeError('Не удалось закрепить ревизию исходной модели')
    source = Path(snapshot_download(SOURCE, revision=revision, allow_patterns=[
        '*.safetensors', '*.json', '*.jinja', '*.model', 'merges.txt',
        'vocab.*', 'README.md', '.gitattributes',
    ])).resolve()
    files = {str(p.relative_to(source)): sha256(p) for p in sorted(source.rglob('*')) if p.is_file()}
    if not any(name.endswith('.safetensors') for name in files):
        raise RuntimeError('В snapshot отсутствуют веса')
    tokenizer = AutoTokenizer.from_pretrained(source, local_files_only=True, trust_remote_code=False)
    samples = dict(SAMPLES, identity='Ирина не меняла 1200 пунктов.')
    prompts = []
    for sample, value in samples.items():
        for protocol, prompt in (
            ('native-prefix', native_prompt(value)),
            ('upstream-template', upstream_prompt(tokenizer, value)),
        ):
            name = sample + '-' + protocol
            (args.out / (name + '.txt')).write_text(prompt, encoding='utf-8')
            tokens = tokenizer.encode(prompt, add_special_tokens=False)
            write_json(args.out / (name + '-tokens.json'), tokens)
            prompts.append({'name': name, 'sample': sample, 'protocol': protocol,
                            'source': value, 'promptSha256': sha256(args.out / (name + '.txt'))})
    metadata = {'diagnosticOnly': True, 'sourceRepository': SOURCE, 'sourceRevision': revision,
                'sourcePath': str(source), 'sourceFiles': files, 'prompts': prompts,
                'kashaSha': os.getenv('GITHUB_SHA'), 'maxTokens': LIMIT,
                'packages': {d.metadata['Name']: d.version for d in importlib.metadata.distributions()}}
    write_json(args.out / 'source.json', metadata)
    print('Закреплён исходный MLX snapshot:', revision, flush=True)


def mlx_worker(args):
    # CPU выбран до импорта mlx-lm, чтобы поток генерации также был CPU.
    import mlx.core as mx
    mx.set_default_device(mx.cpu)
    from mlx_lm import load, stream_generate
    from mlx_lm.sample_utils import make_sampler
    metadata = json.loads((args.out / 'source.json').read_text())
    model, tokenizer = load(metadata['sourcePath'], tokenizer_config={'trust_remote_code': False})
    prompt = (args.out / (args.name + '.txt')).read_text(encoding='utf-8')
    tokens = tokenizer.encode(prompt, add_special_tokens=False)
    if tokens != json.loads((args.out / (args.name + '-tokens.json')).read_text()):
        raise RuntimeError('Токенизация MLX не совпала с зафиксированным входом')
    text, last = '', None
    for last in stream_generate(model, tokenizer, tokens, max_tokens=LIMIT, sampler=make_sampler(temp=0.0)):
        text += last.text
    if last is None:
        raise RuntimeError('MLX не вернул ни одного события')
    write_json(args.out / (args.name + '-mlx.json'), {
        'output': text, 'finishReason': last.finish_reason,
        'promptTokens': last.prompt_tokens, 'generationTokens': last.generation_tokens,
        'device': 'CPU',
    })
    if last.finish_reason != 'stop':
        raise RuntimeError('MLX дошёл до лимита, результат не считается завершённым')


def run(args):
    metadata = json.loads((args.out / 'source.json').read_text())
    source = Path(metadata['sourcePath'])
    for name, expected in metadata['sourceFiles'].items():
        if sha256(source / name) != expected:
            raise RuntimeError('Изменился исходный snapshot: ' + name)
    manifest = (ROOT / 'aiCatalog/src/commonMain/kotlin/brain/ai/ModelArtifacts.kt').read_text()
    spec = re.search(r'AiSelection.DEFAULT_TEXT,\s*"([^"]+)",\s*"[^"]+",\s*"([a-f0-9]{64})"', manifest)
    if not spec:
        raise RuntimeError('Cleanup отсутствует в manifest')
    model = args.bundle / 'models' / spec[1]
    if sha256(model) != spec[2]:
        raise RuntimeError('Не совпала SHA-256 поставляемого GGUF')
    cli = args.bundle / 'bin/llama-completion'
    env = {k: v for k, v in os.environ.items() if not k.startswith('LLAMA_ARG_')}
    env.update(HF_HUB_OFFLINE='1', TRANSFORMERS_OFFLINE='1', GGML_METAL_DEVICES='0', TOKENIZERS_PARALLELISM='false')
    help_text = subprocess.check_output([str(cli), '--help'], env=env, stderr=subprocess.STDOUT, text=True, timeout=20)
    (args.out / 'cli-help.txt').write_text(help_text)
    if '--no-conversation' not in help_text:
        raise RuntimeError('CLI не поддерживает явное отключение второго chat template')
    report = {'diagnosticOnly': True, 'sourceRevision': metadata['sourceRevision'],
              'ggufSha256': spec[2], 'kashaSha': metadata['kashaSha'], 'runs': []}
    failed = False
    for prompt in metadata['prompts']:
        for engine in ('gguf', 'mlx'):
            name = prompt['name']
            if engine == 'gguf':
                command = [str(cli), '-m', str(model), '--no-conversation', '--no-display-prompt',
                           '--simple-io', '--no-escape', '--file', str(args.out / (name + '.txt')),
                           '-n', str(LIMIT), '-c', '8192', '--temp', '0', '--seed', '0', '-t', '4',
                           '--n-gpu-layers', '0', '--device', 'none', '--verbose-prompt']
            else:
                command = [sys.executable, str(Path(__file__).resolve()), 'mlx-worker',
                           '--out', str(args.out), '--name', name]
            start = time.monotonic()
            stdout_path = args.out / (name + '-' + engine + '-stdout.txt')
            stderr_path = args.out / (name + '-' + engine + '-stderr.txt')
            with stdout_path.open('w') as stdout, stderr_path.open('w') as stderr:
                try:
                    code = subprocess.run(command, env=env, stdin=subprocess.DEVNULL,
                                          stdout=stdout, stderr=stderr, timeout=240).returncode
                except subprocess.TimeoutExpired:
                    code = 'timeout'
            failed |= code != 0
            row = dict(prompt, engine=engine, exit=code, seconds=round(time.monotonic()-start, 3))
            if engine == 'gguf':
                row['output'] = stdout_path.read_text(encoding='utf-8')
            else:
                worker = args.out / (name + '-mlx.json')
                if worker.exists():
                    row.update(json.loads(worker.read_text()))
            report['runs'].append(row)
            write_json(args.out / 'comparison.json', report)
            print(json.dumps(row, ensure_ascii=False), flush=True)
    if failed:
        raise RuntimeError('Не все сравниваемые запуски завершились; см. comparison.json и stderr')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=('prepare', 'run', 'mlx-worker'))
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--bundle', type=Path, default=ROOT / 'desktopApp/bundle/common')
    parser.add_argument('--revision', default='main')
    parser.add_argument('--name', default='')
    args = parser.parse_args()
    args.out, args.bundle = args.out.resolve(), args.bundle.resolve()
    {'prepare': prepare, 'run': run, 'mlx-worker': mlx_worker}[args.mode](args)


if __name__ == '__main__':
    main()
