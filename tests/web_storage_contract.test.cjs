/* ТЗ 4.1: настоящий Web-адаптер, управляемые сбои IndexedDB; не browser acceptance. */
const {test} = require('node:test');
const assert = require('node:assert/strict');
const {readFileSync} = require('node:fs');
const {resolve} = require('node:path');
const vm = require('node:vm');
const source = readFileSync(process.env.KASHA_ADAPTER_UNDER_TEST ||
  resolve(__dirname, '../composeApp/src/wasmJsMain/resources/browser-platform.js'), 'utf8');

function harness(plans = [], persistedRows = null) {
  const rows = persistedRows || {sessions: [{id: 'kept', created: 7, mime: 'audio/webm'}],
    chunks: [{id: 'kept', index: 0, blob: new Blob(['original audio'])}]};
  const opens = [], connections = [], deletions = [], uploads = [], uploadReplies = [];
  let readFailure = null, transactionFailure = false, emptyChunkRead = null, chunkReads = 0;
  function connection() {
    const database = {closed: false, close() { this.closed = true; },
      createObjectStore() {},
      transaction(stores, mode) {
        if (transactionFailure) { transactionFailure = false; throw Error('Temporary transaction failure'); }
        if (this.closed) throw Error('Database is closed');
        const mutations = [], tx = {error: null};
        tx.objectStore = store => ({
          getAll() {
            const request = {};
            const empty = store === 'chunks' && ++chunkReads === emptyChunkRead;
            const failure = readFailure?.store === store ? readFailure : null;
            if (failure) readFailure = null;
            setImmediate(() => {
              if (failure?.kind === 'request') {
                request.error = Error('Read failed'); tx.error = request.error;
                request.onerror?.(); tx.onerror?.(); return;
              }
              request.result = failure || empty ? [] : rows[store].slice();
              request.onsuccess?.();
              setImmediate(() => {
                if (failure) { tx.error = Error('Read aborted after request success'); tx.onabort?.(); }
                else tx.oncomplete?.();
              });
            });
            return request;
          },
          delete(key) { mutations.push(() => {
            deletions.push({store, key});
            rows[store] = rows[store].filter(row => store === 'sessions' ? row.id !== key :
              !(row.id === key[0] && row.index === key[1]));
          }); },
        });
        if (mode === 'readwrite') setImmediate(() => { mutations.forEach(f => f()); tx.oncomplete?.(); });
        return tx;
      }};
    connections.push(database);
    return database;
  }
  const indexedDB = {open() {
    const plan = plans.shift() || 'success', request = {};
    opens.push(request);
    if (plan === 'sync-error') throw Error('Open unavailable');
    request.succeed = () => { request.result = connection(); request.onsuccess?.(); };
    setImmediate(() => {
      if (plan === 'async-error') { request.error = Error('Open unavailable'); request.onerror?.(); }
      else if (plan === 'blocked') request.onblocked?.();
      else request.succeed();
    });
    return request;
  }};
  const context = vm.createContext({indexedDB, Blob, FormData, addEventListener() {},
    async fetch(url, options) {
      const audio = options.body.get('audio');
      uploads.push({url, id: options.headers['X-Capture-Id'],
        bytes: Array.from(new Uint8Array(await audio.arrayBuffer()))});
      const reply = uploadReplies.shift();
      if (reply instanceof Error) throw reply;
      return {ok: reply?.ok ?? true,
        async text() { return reply?.text ?? JSON.stringify({id: options.headers['X-Capture-Id']}); }};
    }});
  vm.runInContext(source, context);
  return {api: context.kashaPlatform, opens, connections, deletions, rows, uploads, uploadReplies,
    failRead(store, kind = 'abort') { readFailure = {store, kind}; },
    failTransaction() { transactionFailure = true; },
    emptyChunksOnRead(number) { emptyChunkRead = number; }};
}
async function pending(h) { return JSON.parse(await h.api.pendingRecordings()); }
function retained(h) {
  assert.equal(h.rows.sessions.length, 1); assert.equal(h.rows.chunks.length, 1);
  assert.equal(h.rows.chunks[0].blob.size, 14); assert.deepEqual(h.deletions, []);
}
for (const kind of ['async-error', 'sync-error']) test(`${kind}: Retry открывает базу заново и сохраняет аудио`, async () => {
  const h = harness([kind]);
  assert.match(await h.api.pendingRecordings(), /^ERROR:/);
  assert.deepEqual(await pending(h), [{id: 'kept', createdAt: 7}]);
  assert.equal(h.opens.length, 2); retained(h);
});
test('Два читателя делят одну ошибку открытия, следующий Retry не кеширует её', async () => {
  const h = harness(['async-error']);
  const failed = await Promise.all([h.api.pendingRecordings(), h.api.pendingRecordings()]);
  failed.forEach(result => assert.match(result, /^ERROR:/));
  assert.equal(h.opens.length, 1);
  assert.equal((await pending(h))[0].id, 'kept'); assert.equal(h.opens.length, 2); retained(h);
});
test('Повторная ошибка не запускает скрытый цикл и не блокирует третий Retry', async () => {
  const h = harness(['async-error', 'async-error']);
  for (let attempt = 1; attempt <= 2; attempt++) {
    assert.match(await h.api.pendingRecordings(), /^ERROR:/); assert.equal(h.opens.length, attempt);
  }
  assert.equal((await pending(h))[0].id, 'kept'); assert.equal(h.opens.length, 3); retained(h);
});
for (const store of ['sessions', 'chunks']) test(`Abort чтения ${store} после getAll success не означает пустую базу`, async () => {
  const h = harness(); h.failRead(store);
  assert.match(await h.api.pendingRecordings(), /^ERROR:/); retained(h);
  assert.equal((await pending(h))[0].id, 'kept'); retained(h);
});
test('Ошибка запроса чтения сохраняет журнал и разрешает Retry', async () => {
  const h = harness(); h.failRead('chunks', 'request');
  assert.match(await h.api.pendingRecordings(), /^ERROR:/); retained(h);
  assert.equal((await pending(h))[0].id, 'kept'); retained(h);
});
test('Временная ошибка создания транзакции не превращается в пустой журнал', async () => {
  const h = harness(); h.failTransaction();
  assert.match(await h.api.pendingRecordings(), /^ERROR:/); retained(h);
  assert.equal((await pending(h))[0].id, 'kept'); retained(h);
});
test('Закрытое браузером соединение не кешируется навечно', async () => {
  const h = harness(); await pending(h);
  h.connections[0].close(); h.connections[0].onclose?.();
  assert.equal((await pending(h))[0].id, 'kept'); assert.equal(h.opens.length, 2); retained(h);
});
test('Versionchange освобождает соединение и следующий вызов открывает базу заново', async () => {
  const h = harness(); await pending(h);
  h.connections[0].onversionchange?.();
  assert.equal(h.connections[0].closed, true);
  assert.equal((await pending(h))[0].id, 'kept'); assert.equal(h.opens.length, 2); retained(h);
});
test('Blocked даёт ошибку; поздний success не подменяет новый connection', {timeout: 1000}, async () => {
  const h = harness(['blocked']);
  assert.match(await h.api.pendingRecordings(), /^ERROR:/);
  assert.equal((await pending(h))[0].id, 'kept');
  h.opens[0].succeed();
  assert.equal(h.connections.at(-1).closed, true);
  assert.equal((await pending(h))[0].id, 'kept'); assert.equal(h.opens.length, 2); retained(h);
});
test('Успешное соединение переиспользуется конкурентными читателями', async () => {
  const h = harness();
  const results = await Promise.all([pending(h), pending(h), pending(h)]);
  results.forEach(result => assert.equal(result[0].id, 'kept'));
  assert.equal(h.opens.length, 1); retained(h);
});
test('Чтение pending не удаляет сессию другой вкладки до первого аудиофрагмента', async () => {
  const h = harness(); const chunk = h.rows.chunks.pop();
  assert.deepEqual(await pending(h), []);
  assert.equal(h.rows.sessions.length, 1); assert.deepEqual(h.deletions, []);
  h.rows.chunks.push(chunk);
  assert.equal((await pending(h))[0].id, 'kept'); retained(h);
});
test('Повторное чтение пустого журнала не является разрешением на очистку', async () => {
  const h = harness(); h.rows.chunks = [];
  for (let attempt = 0; attempt < 3; attempt++) assert.deepEqual(await pending(h), []);
  assert.equal(h.rows.sessions.length, 1); assert.deepEqual(h.deletions, []);
});
test('Потеря видимости chunks между discovery и upload не удаляет исходное аудио', async () => {
  const h = harness(); h.emptyChunksOnRead(2);
  assert.match(await h.api.recover('http://runtime.invalid', 'kept'), /^ERROR:No audio samples/);
  retained(h);
  assert.equal((await pending(h))[0].id, 'kept'); retained(h);
});


// Recovery must not turn a damaged journal into an acknowledged, truncated upload.
// These are adapter boundary tests, not a real server or browser acceptance.
const chunk = (index, text = `part-${index}`) => ({id: 'kept', index, blob: new Blob([text])});
const bytesOf = text => Array.from(new TextEncoder().encode(text));
async function stored(h) {
  return {sessions: structuredClone(h.rows.sessions), chunks: await Promise.all(h.rows.chunks.map(async c => ({
    id: c.id, index: c.index,
    blob: c.blob instanceof Blob ? {bytes: Array.from(new Uint8Array(await c.blob.arrayBuffer())), type: c.blob.type} : c.blob,
  })))};
}
for (const [name, chunks] of [
  ['потерян первый фрагмент', [chunk(1)]],
  ['пропущен средний фрагмент', [chunk(0), chunk(2)]],
  ['индекс записан строкой', [chunk('0')]],
  ['дробный индекс', [chunk(0), chunk(1.5)]],
  ['отрицательный индекс', [chunk(-1), chunk(0)]],
  ['индекс отсутствует', [chunk(undefined)]],
  ['пустой фрагмент', [chunk(0), chunk(1, '')]],
  ['единственный пустой фрагмент', [chunk(0, '')]],
  ['единственный фрагмент без Blob', [{id: 'kept', index: 0}]],
  ['blob отсутствует', [chunk(0), {id: 'kept', index: 1}]],
  ['вместо Blob записан объект', [chunk(0), {id: 'kept', index: 1, blob: {size: 7}}]],
]) test(`Повреждённый журнал: ${name} — нет upload и удаления`, async () => {
  const h = harness(); h.rows.chunks = chunks;
  const before = await stored(h);
  assert.deepEqual(await pending(h), [{id: 'kept', createdAt: 7}], 'Повреждение не означает пустую базу');
  for (let attempt = 0; attempt < 2; attempt++) {
    assert.match(await h.api.recover('http://runtime.invalid', 'kept'), /^ERROR:Audio journal is incomplete or corrupt/);
    assert.deepEqual(h.uploads, []); assert.deepEqual(h.deletions, []);
    assert.deepEqual(await stored(h), before);
  }
});
test('Полный журнал сортируется по индексу и передаётся без потери байтов', async () => {
  const h = harness(); h.rows.chunks = [chunk(2, ' конец'), chunk(0, 'начало '), chunk(1, 'середина')];
  assert.equal(JSON.parse(await h.api.recover('http://runtime.invalid', 'kept')).id, 'kept');
  assert.deepEqual(h.uploads.map(u => u.bytes), [bytesOf('начало середина конец')]);
  assert.deepEqual(h.rows, {sessions: [], chunks: []});
  assert.equal(h.deletions.length, 4);
});
test('Retry после восстановления пропущенного фрагмента перечитывает оригинал', async () => {
  const h = harness(); h.rows.chunks = [chunk(0, 'a'), chunk(2, 'c')];
  assert.match(await h.api.recover('http://runtime.invalid', 'kept'), /^ERROR:/);
  assert.deepEqual(h.uploads, []); assert.deepEqual(h.deletions, []);
  h.rows.chunks.push(chunk(1, 'b'));
  assert.equal(JSON.parse(await h.api.recover('http://runtime.invalid', 'kept')).id, 'kept');
  assert.deepEqual(h.uploads.map(u => u.bytes), [bytesOf('abc')]);
});
test('Новый экземпляр адаптера не очищает повреждённый журнал после recovery', async () => {
  const first = harness(); first.rows.chunks = [chunk(0, 'a'), chunk(2, 'c')];
  const before = await stored(first);
  assert.match(await first.api.recover('http://runtime.invalid', 'kept'), /^ERROR:/);
  const restarted = harness([], first.rows);
  assert.match(await restarted.api.recover('http://runtime.invalid', 'kept'), /^ERROR:/);
  assert.deepEqual(await stored(restarted), before);
  assert.deepEqual(first.uploads, []); assert.deepEqual(restarted.uploads, []);
  restarted.rows.chunks.push(chunk(1, 'b'));
  assert.equal(JSON.parse(await restarted.api.recover('http://runtime.invalid', 'kept')).id, 'kept');
  assert.deepEqual(restarted.uploads.map(u => u.bytes), [bytesOf('abc')]);
  assert.deepEqual(await pending(harness([], restarted.rows)), []);
});
for (const [name, response] of [
  ['runtime недоступен', Error('Runtime disconnected')],
  ['runtime отказал', {ok: false, text: 'Storage unavailable'}],
  ['повреждённый JSON receipt', {text: '{broken'}],
  ['receipt от другой записи', {text: JSON.stringify({id: 'different'})}],
]) test(`${name}: исходные байты остаются, повтор отправляет тот же файл`, async () => {
  const h = harness(); h.uploadReplies.push(response);
  const before = await stored(h);
  assert.match(await h.api.recover('http://runtime.invalid', 'kept'), /^ERROR:/);
  assert.deepEqual(await stored(h), before); assert.deepEqual(h.deletions, []);
  assert.equal(JSON.parse(await h.api.recover('http://runtime.invalid', 'kept')).id, 'kept');
  assert.deepEqual(h.uploads.map(u => u.bytes), [bytesOf('original audio'), bytesOf('original audio')]);
  assert.deepEqual(h.rows, {sessions: [], chunks: []});
});
test('Восстановление одной записи не смешивает и не удаляет другую', async () => {
  const h = harness();
  h.rows.sessions.push({id: 'other', created: 8, mime: 'audio/webm'});
  h.rows.chunks.push({id: 'other', index: 0, blob: new Blob(['other audio'])});
  assert.equal(JSON.parse(await h.api.recover('http://runtime.invalid', 'kept')).id, 'kept');
  assert.deepEqual(h.uploads.map(u => u.bytes), [bytesOf('original audio')]);
  assert.deepEqual(h.rows.sessions.map(s => s.id), ['other']);
  assert.equal(h.rows.chunks.length, 1); assert.equal(await h.rows.chunks[0].blob.text(), 'other audio');
  assert.ok(h.deletions.every(d => (Array.isArray(d.key) ? d.key[0] : d.key) === 'kept'));
});
