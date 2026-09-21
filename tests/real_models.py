"""Настоящие веса + русская синтезированная речь + настоящий HTTP/диск. Не тест живого микрофона."""
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import wave
import time
import urllib.request
import uuid

BASE = 'http://127.0.0.1:8787/api/'
OUT = Path('test-output/real-models'); OUT.mkdir(parents=True, exist_ok=True)
(OUT / 'result.json').unlink(missing_ok=True)

def api(path, data=None, method=None):
    body = None if data is None else json.dumps(data, ensure_ascii=False).encode()
    req = urllib.request.Request(BASE + path, body, method=method, headers={
        'X-Kasha-Client': 'web', 'Content-Type': 'application/json'})
    with urllib.request.urlopen(req, timeout=1200) as response: return json.load(response)

def capture(cid):
    return next(c for c in api('snapshot')['captures'] if c['id'] == cid)

def processed(cid):
    deadline = time.monotonic() + 1200
    while time.monotonic() < deadline:
        c = capture(cid)
        if c['status'] not in ('QUEUED','TRANSCRIBING','COMPACTING','POLISHING','RECORDING'):
            (OUT / (cid + '.json')).write_text(json.dumps(c, ensure_ascii=False, indent=2), encoding='utf-8')
            return c
        time.sleep(1)
    raise AssertionError('Истекло время обработки настоящими моделями')

def upload(path):
    cid = str(uuid.uuid4()); boundary = 'brain-' + uuid.uuid4().hex
    body = (f'--{boundary}\r\nContent-Disposition: form-data; name="audio"; filename="voice.wav"\r\n'
            'Content-Type: audio/wav\r\n\r\n').encode() + path.read_bytes() + f'\r\n--{boundary}--\r\n'.encode()
    req = urllib.request.Request(BASE + 'captures/audio', body, headers={
        'X-Kasha-Client':'web', 'X-Capture-Id':cid, 'Content-Type':'multipart/form-data; boundary=' + boundary})
    with urllib.request.urlopen(req, timeout=30) as response: assert json.load(response)['id'] == cid
    return cid

for _ in range(90):
    try: api('health'); break
    except Exception: time.sleep(1)
else: raise AssertionError('Сервис не запустился')

preferences = api('preferences')
selection = preferences['ai']
assert selection == {'speechToText': 'local.default.stt', 'text': 'local.default.text', 'routing': 'local.default.text'}
api('preferences', dict(preferences, language='ru', autoRecord=False), method='PUT')
capabilities = api('ai/capabilities', selection)
assert len(capabilities) == 3 and all(value['executable'] for value in capabilities), capabilities
projects = [
    api('projects', {'title':'Разработка приложения','instruction':'Идеи про интерфейс, запись голоса, кнопки и сохранение заметок.'}),
    api('projects', {'title':'Кулинария','instruction':'Только рецепты, приготовление еды и продукты. Не разработка программ.'}),
    api('projects', {'title':'Закреплённый','instruction':'Личные мысли'}),
]
api('projects/' + projects[2]['id'] + '/pin', {'pinned':True})
voice = OUT / 'russian-with-pauses.wav'
assert voice.is_file(), 'Сначала запустите tests/make-russian-fixture.py'
started = time.monotonic()
cid = upload(voice)
c = processed(cid)
print(json.dumps(c, ensure_ascii=False, indent=2), flush=True)
assert c['status'] == 'READY', c['message']
assert not c['simulated'] and not c['llmApplied']
# Применяется существующая команда UI, а не прежний автоматический LocalProcessing.
c = api('captures/' + cid + '/tidy', {})
c = api('captures/' + cid + '/rank', {})
assert c['llmApplied'], c['message']
assert c['rankingApplied'], c['message']
for stem in ('проект','приложен','запис','пауз','замет'):
    assert stem in c['transcript'].lower(), c['transcript']
    assert stem in c['preparedText'].lower(), c['preparedText']
assert 'нельзя' in c['transcript'].lower() and 'нельзя' in c['preparedText'].lower()
assert any(stem in c['title'].lower() for stem in ('приложен','запис','голос','пауз','замет')), c['title']
assert c['relevance'][projects[0]['id']] > c['relevance'][projects[1]['id']], c['relevance']
assert c['audioFinalized'] and c['durationSeconds'] > 0
with urllib.request.urlopen(BASE + 'captures/' + cid + '/audio') as response:
    saved = OUT / 'saved.m4a'; saved.write_bytes(response.read())
assert c['inputSha256'] == hashlib.sha256(voice.read_bytes()).hexdigest()
# Проверяем реальное декодирование теми кодеками, которые входят в приложение.
# Минимальный bundled FFmpeg не содержит null muxer, но содержит WAV/PCM16.
with tempfile.TemporaryDirectory(prefix='kasha-audio-verify-') as temporary:
    decoded = Path(temporary) / 'decoded.wav'
    subprocess.run(['ffmpeg', '-nostdin', '-v', 'error', '-y', '-i', str(saved),
                    '-ar', '16000', '-ac', '1', '-c:a', 'pcm_s16le', str(decoded)], check=True)
    with wave.open(str(decoded), 'rb') as audio:
        assert audio.getnchannels() == 1 and audio.getsampwidth() == 2
        assert audio.getframerate() == 16000 and audio.getnframes() > 0
assert api('preferences')['ai'] == selection
note = api('captures/' + cid + '/distribute', {'projectId':projects[0]['id']})
assert note == api('captures/' + cid + '/distribute', {'projectId':projects[0]['id']})
result = {'passed':True, 'speech':'Piper ru_RU-irina-medium, синтезированная речь, не живой микрофон',
          'whisper':'small','llm':'Qwen3-4B Q4_K_M (без режима рассуждений)',
          'elapsedSeconds':round(time.monotonic()-started,2), 'transcript':c['transcript'],
          'preparedText':c['preparedText'],'title':c['title'], 'relevance':c['relevance'],
          'projectScores':{p['title']:c['relevance'][p['id']] for p in projects if not p['id']==projects[2]['id']},
          'durationSeconds':c['durationSeconds'], 'applicationRouter': True, 'externalNetworkReachable': False,
          'originalSha256':hashlib.sha256(voice.read_bytes()).hexdigest()}
(OUT/'result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
print('REAL MODELS PASSED', flush=True)
