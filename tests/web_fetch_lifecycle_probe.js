// Только тестовый наблюдатель. Не читает текст, не подменяет Response и не отменяет запросы.
(() => {
    if (globalThis.kashaFetchLifecycle) return;
    const rows = globalThis.kashaFetchLifecycle = [];
    const signals = new WeakMap(), streams = new WeakMap(), readers = new WeakMap();
    let sequence = 0;
    const record = (state, event, extra = {}) => {
        rows.push({id: state.id, url: state.url, event, ms: performance.now(),
            eof: state.eof, bytes: state.bytes, ...extra});
        if (rows.length > 500) rows.shift();
    };
    const nativeFetch = globalThis.fetch;
    globalThis.fetch = function(input, init) {
        const url = typeof input === 'string' ? input : input?.url;
        const result = Reflect.apply(nativeFetch, this, arguments);
        if (typeof url !== 'string' || !/\/api\/captures\/[^/]+\/(tidy|rank|retranscribe)$/.test(url)) return result;
        const state = {id: ++sequence, url, eof: false, bytes: 0};
        const signal = init?.signal ?? input?.signal;
        if (signal) signals.set(signal, state);
        record(state, 'fetch');
        // Возвращаем оригинальный Promise. Наблюдатель не создаёт второй читатель stream.
        result.then(response => {
            if (response.body) streams.set(response.body, state);
            record(state, 'headers', {status: response.status});
        }, error => record(state, 'fetch-error', {name: error?.name}));
        return result;
    };
    const abort = AbortController.prototype.abort;
    AbortController.prototype.abort = function(reason) {
        const state = signals.get(this.signal);
        if (state) record(state, 'abort', {name: reason?.name, stack: new Error().stack});
        return Reflect.apply(abort, this, arguments);
    };
    const getReader = ReadableStream.prototype.getReader;
    ReadableStream.prototype.getReader = function() {
        const reader = Reflect.apply(getReader, this, arguments);
        const state = streams.get(this);
        if (state) readers.set(reader, state);
        return reader;
    };
    const read = ReadableStreamDefaultReader.prototype.read;
    ReadableStreamDefaultReader.prototype.read = function() {
        const result = Reflect.apply(read, this, arguments);
        const state = readers.get(this);
        if (state) result.then(chunk => {
            state.eof = chunk.done;
            state.bytes += chunk.value?.byteLength ?? 0;
            record(state, chunk.done ? 'eof' : 'chunk');
        }, error => record(state, 'read-error', {name: error?.name}));
        return result;
    };
    const cancel = ReadableStreamDefaultReader.prototype.cancel;
    ReadableStreamDefaultReader.prototype.cancel = function(reason) {
        const state = readers.get(this);
        if (state) record(state, 'reader-cancel', {name: reason?.name, stack: new Error().stack});
        return Reflect.apply(cancel, this, arguments);
    };
})();
