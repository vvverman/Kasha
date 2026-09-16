/* ТЗ 4.1: настоящий Web-адаптер, управляемые сбои IndexedDB; не browser acceptance. */
const {test} = require('node:test');
const assert = require('node:assert/strict');
const {readFileSync} = require('node:fs');
const {resolve} = require('node:path');
const vm = require('node:vm');
const source = readFileSync(process.env.KASHA_ADAPTER_UNDER_TEST ||
  resolve(__dirname, '../composeApp/src/wasmJsMain/resources/browser-platform.js'), 'utf8');

function harness(plans = []) {
  const rows = {sessions: [{id: 'kept', created: 7, mime: 'audio/webm'}],
    chunks: [{id: 'kept', index: 0, blob: new Blob(['original audio'])}]};
  const opens = [], connections = [], deletions = [];
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
  const context = vm.createContext({indexedDB, Blob, addEventListener() {}});
  vm.runInContext(source, context);
  return {api: context.kashaPlatform, opens, connections, deletions, rows,
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
