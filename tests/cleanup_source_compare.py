"""Сравнение исходного MLX и поставляемого GGUF только на встроенных фразах.

Диагностика не меняет manifest, не публикует веса и не объявляет приёмку.
prepare скачивает один закреплённый snapshot; run работает без внешней сети.
Результаты каждого запуска сохраняются даже при ошибке следующего.
"""
import argparse
import faulthandler
import hashlib
import importlib.metadata
import json
import os
from pathlib import Path
from collections import Counter
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
    # Диагностируем точную фазу, а не повторяем шесть непрозрачных таймаутов.
    started = time.monotonic()
    faulthandler.enable()
    faulthandler.dump_traceback_later(30, repeat=True)
    progress_path = args.out / (args.name + '-mlx-progress.json')

    def progress(phase, **extra):
        state = dict(phase=phase, seconds=round(time.monotonic()-started, 3), **extra)
        write_json(progress_path, state)
        print(json.dumps(state, ensure_ascii=False), flush=True)

    progress('import-mlx')
    import mlx.core as mx
    mx.set_default_device(mx.cpu)
    progress('import-mlx-lm')
    from mlx_lm import load
    from mlx_lm.models.cache import make_prompt_cache
    metadata = json.loads((args.out / 'source.json').read_text())
    progress('load-model')
    model, tokenizer = load(metadata['sourcePath'], tokenizer_config={'trust_remote_code': False}, lazy=True)
    from mlx.utils import tree_flatten
    source_dtypes = dict(Counter(str(value.dtype) for _, value in tree_flatten(model.parameters())))
    progress('evaluate-weights', parameterDtypes=source_dtypes)
    mx.eval(model.parameters())
    if args.mlx_mode == 'dense-f32':
        # Отдельный диагностический CPU reference, не штатный MLX runtime:
        # распаковать опубликованные 4-bit значения в RAM и считать в float32.
        # Нет дообучения, публикации весов или повторного квантования; арифметика и
        # представление отличаются от source quantized execution и явно отмечены.
        from mlx_lm.utils import dequantize_model
        progress('dequantize-for-cpu-reference')
        model = dequantize_model(model)
        mx.eval(model.parameters())
        model.set_dtype(mx.float32)
        mx.eval(model.parameters())
    compute_dtypes = dict(Counter(str(value.dtype) for _, value in tree_flatten(model.parameters())))
    progress('weights-ready', representation=args.mlx_mode, parameterDtypes=compute_dtypes)
    prompt = (args.out / (args.name + '.txt')).read_text(encoding='utf-8')
    tokens = tokenizer.encode(prompt, add_special_tokens=False)
    if tokens != json.loads((args.out / (args.name + '-tokens.json')).read_text()):
        raise RuntimeError('Токенизация MLX не совпала с зафиксированным входом')
    # Синхронный greedy forward на публичных model/cache API. В отличие от
    # generate_step здесь нет async_eval и второго потока исполнения.
    # Токенизация, EOS и лимит не меняются; GPU и сеть не используются.
    cache = make_prompt_cache(model)
    generated, finish_reason = [], 'length'
    values = mx.array(tokens, dtype=mx.int32)[None]
    progress('prefill', promptTokens=len(tokens))
    for index in range(LIMIT):
        logits = model(values, cache=cache)[:, -1, :]
        token = int(mx.argmax(logits, axis=-1).item())
        if token in tokenizer.eos_token_ids:
            finish_reason = 'stop'
            break
        generated.append(token)
        if index == 0 or len(generated) % 8 == 0:
            progress('decode', generationTokens=len(generated), outputTokens=generated)
        values = mx.array([[token]], dtype=mx.int32)
    text = tokenizer.decode(generated, skip_special_tokens=False)
    write_json(args.out / (args.name + '-mlx.json'), {
        'output': text, 'finishReason': finish_reason, 'outputTokens': generated,
        'promptTokens': len(tokens), 'generationTokens': len(generated),
        'device': 'CPU', 'api': 'synchronous-model-forward',
        'representation': args.mlx_mode, 'sourceDtypeUnchanged': args.mlx_mode == 'source-quantized',
        'sourceParameterDtypes': source_dtypes, 'computeParameterDtypes': compute_dtypes,
        'directSourceRuntime': args.mlx_mode == 'source-quantized',
    })
    progress('finished', generationTokens=len(generated), finishReason=finish_reason)
    faulthandler.cancel_dump_traceback_later()
    if finish_reason != 'stop':
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
              'ggufSha256': spec[2], 'kashaSha': metadata['kashaSha'],
              'mlxRepresentation': args.mlx_mode, 'runs': []}
    failed = False
    mlx_blocked = None
    # Сначала короткая фраза: непройденный запуск не размножается на все входы.
    prompts = sorted(metadata['prompts'], key=lambda p: p['sample'] != 'identity')
    for prompt in prompts:
        for engine in ('gguf', 'mlx'):
            name = prompt['name']
            if engine == 'mlx' and mlx_blocked is not None:
                report['runs'].append(dict(prompt, engine=engine, exit='not_run',
                    reason='previous_mlx_execution_failed', blockedBy=mlx_blocked))
                write_json(args.out / 'comparison.json', report)
                continue
            if engine == 'gguf':
                command = [str(cli), '-m', str(model), '--no-conversation', '--no-display-prompt',
                           '--simple-io', '--no-escape', '--file', str(args.out / (name + '.txt')),
                           '-n', str(LIMIT), '-c', '8192', '--temp', '0', '--seed', '0', '-t', '4',
                           '--n-gpu-layers', '0', '--device', 'none', '--verbose-prompt']
            else:
                (args.out / (name + '-mlx.json')).unlink(missing_ok=True)
                (args.out / (name + '-mlx-progress.json')).unlink(missing_ok=True)
                command = [sys.executable, '-u', str(Path(__file__).resolve()), 'mlx-worker',
                           '--out', str(args.out), '--name', name, '--mlx-mode', args.mlx_mode]
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
            if engine == 'mlx' and code != 0:
                mlx_blocked = name
            row = dict(prompt, engine=engine, exit=code, seconds=round(time.monotonic()-start, 3))
            if engine == 'gguf':
                row['output'] = stdout_path.read_text(encoding='utf-8')
            else:
                worker = args.out / (name + '-mlx.json')
                if worker.exists():
                    row.update(json.loads(worker.read_text()))
                progress_file = args.out / (name + '-mlx-progress.json')
                if progress_file.exists():
                    row['lastProgress'] = json.loads(progress_file.read_text())
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
    parser.add_argument('--revision', default='881a17920f1a97e3adc155978188ad80c97bb0fb')
    parser.add_argument('--name', default='')
    parser.add_argument('--mlx-mode', choices=('source-quantized', 'dense-f32'), default='source-quantized')
    args = parser.parse_args()
    args.out, args.bundle = args.out.resolve(), args.bundle.resolve()
    {'prepare': prepare, 'run': run, 'mlx-worker': mlx_worker}[args.mode](args)


if __name__ == '__main__':
    main()
