const { test } = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const source = fs.readFileSync(__dirname + '/web_fetch_lifecycle_probe.js', 'utf8');

function fixture(url = 'http://127.0.0.1:8787/api/captures/test/tidy') {
    let reads = 0, cancels = 0, aborts = 0, reason;
    class Reader {
        read() { reads++; return this.result; }
        cancel(value) { cancels++; reason = value; return this.cancelResult; }
    }
    class Stream { getReader() { return reader; } }
    class Controller {
        constructor() { this.signal = {}; }
        abort(value) { aborts++; reason = value; }
    }
    const reader = new Reader();
    reader.result = Promise.resolve({ done: false, value: Uint8Array.of(1, 2, 3) });
    reader.cancelResult = Promise.resolve();
    const response = { status: 200, body: new Stream() };
    const promise = Promise.resolve(response);
    const sandbox = { fetch: () => promise, AbortController: Controller,
        ReadableStream: Stream, ReadableStreamDefaultReader: Reader,
        performance: { now: () => 1 } };
    vm.runInNewContext(source, sandbox);
    const controller = new Controller();
    return { sandbox, promise, reader, response, controller, url,
        counts: () => ({ reads, cancels, aborts, reason }) };
}

test('Наблюдение сохраняет Promise, Response, байты и EOF без дополнительных чтений', async () => {
    const f = fixture();
    const value = f.sandbox.fetch(f.url, { signal: f.controller.signal });
    assert.strictEqual(value, f.promise);
    assert.strictEqual(await value, f.response);
    const r = f.response.body.getReader();
    assert.strictEqual(r, f.reader);
    assert.strictEqual(r.read(), f.reader.result);
    assert.deepEqual((await f.reader.result).value, Uint8Array.of(1, 2, 3));
    f.reader.result = Promise.resolve({ done: true });
    await r.read();
    const error = new Error('test cancellation');
    f.controller.abort(error);
    assert.equal(f.counts().reads, 2);
    assert.equal(f.counts().cancels, 0);
    assert.equal(f.counts().aborts, 1);
    assert.strictEqual(f.counts().reason, error);
    const events = JSON.parse(JSON.stringify(f.sandbox.kashaFetchLifecycle));
    assert.deepEqual(events.map(x => x.event), ['fetch', 'headers', 'chunk', 'eof', 'abort']);
    assert.equal(events.at(-1).eof, true);
    assert.equal(events.at(-1).bytes, 3);
});

test('Отмена до EOF остаётся отменой с тем же аргументом и Promise', async () => {
    const f = fixture();
    await f.sandbox.fetch(f.url, { signal: f.controller.signal });
    const reader = f.response.body.getReader();
    const reason = new Error('cancel before EOF');
    assert.strictEqual(reader.cancel(reason), reader.cancelResult);
    assert.strictEqual(f.counts().reason, reason);
    assert.equal(f.counts().cancels, 1);
    assert.equal(f.counts().reads, 0);
    const row = f.sandbox.kashaFetchLifecycle.at(-1);
    assert.equal(row.event, 'reader-cancel');
    assert.equal(row.eof, false);
});

test('Посторонние запросы не записываются и проходят без вмешательства', async () => {
    const f = fixture('http://127.0.0.1:8787/api/snapshot');
    await f.sandbox.fetch(f.url, { signal: f.controller.signal });
    await f.response.body.getReader().read();
    f.controller.abort();
    assert.equal(f.sandbox.kashaFetchLifecycle.length, 0);
    assert.equal(f.counts().reads, 1);
    assert.equal(f.counts().aborts, 1);
});
