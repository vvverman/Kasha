/* ТЗ 5.3: проверяется настоящий JS-адаптер с управляемыми событиями HTMLAudio. */
const {test} = require('node:test');
const assert = require('node:assert/strict');
const {readFileSync} = require('node:fs');
const {resolve} = require('node:path');
const vm = require('node:vm');
const source = readFileSync(process.env.KASHA_ADAPTER_UNDER_TEST ||
  resolve(__dirname, '../composeApp/src/wasmJsMain/resources/browser-platform.js'), 'utf8');

function harness() {
  const players = [], timers = new Set();
  class Audio {
    constructor() {
      this.paused = true; this.ended = false; this.duration = 60; this.currentTime = 0;
      this.playCalls = 0; this.resets = 0; this.defaultPlaybackRate = 1; this.src = ''; players.push(this);
    }
    // HTML media load resets the effective rate to defaultPlaybackRate.
    set src(value) { this.url = value; this.playbackRate = this.defaultPlaybackRate; }
    get src() { return this.url; }
    pause() { this.paused = true; }
    removeAttribute(name) { if (name === 'src') this.src = ''; }
    load() { this.resets++; }
    play() {
      this.playCalls++;
      if (this.delayed) return new Promise((resolve, reject) => {
        this.finishPlay = () => { this.paused = false; resolve(); };
        this.failPlay = () => reject(Error('Play failed'));
      });
      this.paused = false; return Promise.resolve();
    }
  }
  const context = vm.createContext({Audio, addEventListener() {},
    setTimeout(callback) { timers.add(callback); return callback; },
    clearTimeout(callback) { timers.delete(callback); }});
  vm.runInContext(source, context);
  return {api: context.kashaPlatform, players, timers};
}
const tick = async () => { await Promise.resolve(); await Promise.resolve(); };
async function ready(h) {
  const operation = h.api.play('source.wav', 2, 1.5);
  h.players.at(-1).onloadedmetadata();
  assert.equal(await operation, 'ok');
  return h.players.at(-1);
}
function released(h, own) {
  assert.equal(h.api.audioState().phase, 'idle');
  assert.equal(own.paused, true); assert.equal(own.src, ''); assert.ok(own.resets > 0);
  assert.equal(h.timers.size, 0);
}

test('Ошибка метаданных освобождает источник и состояние', async () => {
  const h = harness(), result = h.api.play('broken.wav', 0, 1), own = h.players[0];
  own.onerror(); assert.match(await result, /^ERROR:/); released(h, own);
});
test('Таймаут загрузки освобождает источник', async () => {
  const h = harness(), result = h.api.play('timeout.wav', 0, 1), own = h.players[0];
  [...h.timers][0](); assert.match(await result, /^ERROR:/); released(h, own);
});
test('Ошибка play после метаданных не оставляет ложную паузу', async () => {
  const h = harness(), result = h.api.play('source.wav', 0, 1), own = h.players[0];
  own.delayed = true; own.onloadedmetadata(); await tick(); own.failPlay();
  assert.match(await result, /^ERROR:/); released(h, own);
});
test('Stop завершает ожидание загрузки без позднего запуска', async () => {
  const h = harness(), result = h.api.play('source.wav', 0, 1), own = h.players[0];
  const lateMetadata = own.onloadedmetadata;
  h.api.stopAudio(); assert.equal(h.timers.size, 0);
  assert.match(await result, /^ERROR:/); lateMetadata(); await tick();
  assert.equal(own.playCalls, 0); released(h, own);
});
test('Отменённая загрузка не сбрасывает loading нового источника', async () => {
  const h = harness(), old = h.api.play('old.wav', 0, 1), first = h.players[0];
  const lateFailure = first.onerror;
  const current = h.api.play('new.wav', 0, 1), second = h.players[1];
  lateFailure(); assert.match(await old, /^ERROR:/);
  assert.equal(h.api.audioState().phase, 'loading'); assert.equal(second.src, 'new.wav');
  second.onloadedmetadata(); assert.equal(await current, 'ok');
  assert.equal(h.api.audioState().phase, 'playing'); h.api.stopAudio();
});
test('Stop во время play не позволяет позднему Promise включить звук', async () => {
  const h = harness(), result = h.api.play('source.wav', 0, 1), own = h.players[0];
  own.delayed = true; own.onloadedmetadata(); await tick(); h.api.stopAudio(); own.finishPlay();
  assert.match(await result, /^ERROR:/); released(h, own);
});
test('Поздний play старого источника не меняет новый источник', async () => {
  const h = harness(), old = h.api.play('old.wav', 0, 1), first = h.players[0];
  first.delayed = true; first.onloadedmetadata(); await tick();
  const current = h.api.play('new.wav', 0, 1), second = h.players[1];
  first.finishPlay(); assert.match(await old, /^ERROR:/);
  assert.equal(first.paused, true); assert.equal(h.api.audioState().phase, 'loading');
  second.onloadedmetadata(); assert.equal(await current, 'ok'); h.api.stopAudio();
});
test('Ошибка возобновления освобождает источник', async () => {
  const h = harness(), own = await ready(h);
  h.api.pauseAudio(); own.delayed = true; const result = h.api.resumeAudio(); own.failPlay();
  assert.match(await result, /^ERROR:/); released(h, own);
});
test('Stop во время возобновления не позволяет поздний звук', async () => {
  const h = harness(), own = await ready(h);
  h.api.pauseAudio(); own.delayed = true; const result = h.api.resumeAudio();
  h.api.stopAudio(); own.finishPlay(); assert.match(await result, /^ERROR:/); released(h, own);
});
test('Перемотка сохраняет источник, скорость и playing/paused', async () => {
  const h = harness(), own = await ready(h);
  assert.equal(h.api.seekAudio(12), 'ok'); assert.equal(h.api.audioState().phase, 'playing');
  assert.equal(own.currentTime, 12); h.api.pauseAudio();
  assert.equal(h.api.seekAudio(30), 'ok'); assert.equal(h.api.audioState().phase, 'paused');
  assert.equal(own.currentTime, 30); assert.equal(own.src, 'source.wav');
  assert.equal(own.playbackRate, 1.5); assert.equal(h.players.length, 1); h.api.stopAudio();
});
test('Ошибка во время проигрывания освобождает аудио', async () => {
  const h = harness(), own = await ready(h); own.onerror(); released(h, own);
});
test('Resume без источника не сообщает ложный успех, Stop идемпотентен', async () => {
  const h = harness(); h.api.stopAudio(); h.api.stopAudio();
  assert.match(await h.api.resumeAudio(), /^ERROR:/); assert.equal(h.api.audioState().phase, 'idle');
});

for (const rate of [1, 1.5, 2]) test(`Загрузка файла сохраняет выбранную скорость ${rate}×`, async () => {
  const h = harness(), result = h.api.play('speed.wav', 0, rate), own = h.players[0];
  own.onloadedmetadata(); assert.equal(await result, 'ok');
  assert.equal(own.playbackRate, rate);
  h.api.pauseAudio(); assert.equal(await h.api.resumeAudio(), 'ok');
  assert.equal(own.playbackRate, rate); h.api.stopAudio();
});
