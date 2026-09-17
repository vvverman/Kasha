"""ТЗ 5.1: настоящий WAV и HTMLAudio, полный playback без модели и подмены плеера."""
import io
import json
import math
import os
from pathlib import Path
import shutil
import struct
import wave
from playwright.sync_api import sync_playwright

BASE = 'http://127.0.0.1:8787'
OUT = Path('test-output/playback-file')
OUT.mkdir(parents=True, exist_ok=True)
stream = io.BytesIO()
with wave.open(stream, 'wb') as wav:
    wav.setparams((1, 2, 16000, 0, 'NONE', 'not compressed'))
    wav.writeframes(b''.join(struct.pack('<h', int(math.sin(i * math.tau * 440 / 16000) * 1500))
                             for i in range(48000)))
audio_bytes = stream.getvalue()
checks, errors = [], []
with sync_playwright() as pw:
    executable = os.getenv('CHROME_PATH') or shutil.which('google-chrome') or shutil.which('chromium')
    browser = pw.chromium.launch(executable_path=executable, headless=True, args=['--no-sandbox'])
    page = browser.new_page()
    page.on('pageerror', lambda error: errors.append(str(error)))
    page.route(BASE + '/playback-file-probe', lambda route: route.fulfill(
        content_type='text/html', body='<!doctype html><title>Kasha playback regression</title>'))
    # Only file delivery is controlled. Decoding, timing, completion, pause and seek are native.
    def serve_audio(route):
        # Native media seeking requires a real range response, not a full 200 for every request.
        requested = route.request.headers.get('range')
        headers = {'Accept-Ranges': 'bytes'}
        if requested:
            start, end = requested.removeprefix('bytes=').split('-', 1)
            first = int(start) if start else max(0, len(audio_bytes) - int(end))
            last = min(int(end), len(audio_bytes) - 1) if start and end else len(audio_bytes) - 1
            if first > last:
                route.fulfill(status=416, headers={'Content-Range': f'bytes */{len(audio_bytes)}'}, body=b'')
                return
            headers['Content-Range'] = f'bytes {first}-{last}/{len(audio_bytes)}'
            route.fulfill(status=206, headers=headers, content_type='audio/wav', body=audio_bytes[first:last + 1])
        else:
            route.fulfill(headers=headers, content_type='audio/wav', body=audio_bytes)
    page.route('**/playback-fixture-*.wav', serve_audio)
    try:
        page.goto(BASE + '/playback-file-probe')
        page.add_script_tag(url=BASE + '/browser-platform.js')
        page.mouse.click(10, 10)  # Обычное действие пользователя разрешает запуск звука.
        page.evaluate('''() => {
            const nativePlay = HTMLMediaElement.prototype.play;
            const seen = new Set();
            window.playedRates = [];
            window.playingFileCount = () => [...seen].filter(x => !x.paused && !x.ended).length;
            HTMLMediaElement.prototype.play = function(...args) {
                seen.add(this); playedRates.push(this.playbackRate);
                return nativePlay.apply(this, args);
            };
        }''')
        first, second = BASE + '/playback-fixture-first.wav', BASE + '/playback-fixture-second.wav'
        def state():
            return page.evaluate('kashaPlatform.audioState()')
        def play(url, rate=1.0):
            assert page.evaluate('([url, rate]) => kashaPlatform.play(url, 0, rate)', [url, rate]) == 'ok'
        def advancing():
            page.wait_for_function("kashaPlatform.audioState().phase === 'playing' && kashaPlatform.audioState().position > 0.1")
        for rate in [1.0, 1.5, 2.0]:
            play(first, rate)
            advancing()
            assert abs(state()['duration'] - 3.0) < 0.05
            assert page.evaluate('playedRates.at(-1)') == rate, {'requestedRate': rate, 'playedRates': page.evaluate('playedRates'), 'state': state()}
            page.wait_for_function("kashaPlatform.audioState().phase === 'idle'", timeout=7000)
            assert page.evaluate('playingFileCount()') == 0
            checks.append(f'real file finishes at speed {rate}')
        play(first)
        advancing()
        assert page.evaluate('kashaPlatform.pauseAudio()') == 'ok'
        paused = state()
        assert paused['phase'] == 'paused'
        page.wait_for_timeout(200)
        assert abs(state()['position'] - paused['position']) < 0.04
        assert page.evaluate('kashaPlatform.seekAudio(1.0)') == 'ok'
        page.wait_for_function("kashaPlatform.audioState().phase === 'paused' && Math.abs(kashaPlatform.audioState().position - 1) < 0.05", timeout=5000)
        assert state()['phase'] == 'paused' and abs(state()['position'] - 1) < 0.05
        assert page.evaluate('kashaPlatform.resumeAudio()') == 'ok'
        page.wait_for_function('kashaPlatform.audioState().position > 1.1')
        assert state()['phase'] == 'playing'
        assert page.evaluate('kashaPlatform.seekAudio(0.5)') == 'ok'
        assert state()['phase'] == 'playing'
        checks.append('pause/resume and seek preserve the actual phase')
        play(second, 2.0)
        assert page.evaluate('playingFileCount()') == 1
        page.evaluate('kashaPlatform.stopAudio()')
        assert state()['phase'] == 'idle' and page.evaluate('playingFileCount()') == 0
        checks.append('source switching and explicit stop release the previous file')
        unavailable = BASE + '/temporarily-unavailable.wav'
        page.route('**/temporarily-unavailable.wav', lambda route: route.fulfill(status=503, body='unavailable'))
        assert page.evaluate('(url) => kashaPlatform.play(url, 0, 1)', unavailable).startswith('ERROR:')
        assert state()['phase'] == 'idle' and page.evaluate('playingFileCount()') == 0
        page.unroute('**/temporarily-unavailable.wav')
        page.route('**/temporarily-unavailable.wav', serve_audio)
        play(unavailable)
        advancing()
        page.evaluate('kashaPlatform.stopAudio()')
        checks.append('the same source plays after a temporary HTTP failure')
        assert not errors, errors
        (OUT / 'result.json').write_text(json.dumps({'passed': True, 'realHtmlAudio': True,
            'checks': checks, 'playedRates': page.evaluate('playedRates'), 'pageErrors': errors},
            ensure_ascii=False, indent=2), encoding='utf-8')
        print('PLAYBACK FILE BROWSER PASSED')
    finally:
        (OUT / 'native-playback.json').write_text(json.dumps({'playedRates': page.evaluate('window.playedRates || []'), 'checks': checks}, ensure_ascii=False, indent=2), encoding='utf-8')
        (OUT / 'page-errors.json').write_text(json.dumps(errors), encoding='utf-8')
        browser.close()
