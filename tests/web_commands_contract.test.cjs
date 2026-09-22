const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync('composeApp/src/wasmJsMain/resources/browser-commands.js', 'utf8');
const deferred = () => { let resolve, reject; const promise = new Promise((a,b) => { resolve=a; reject=b; }); return { promise, resolve, reject }; };
const tick = () => new Promise(resolve => setImmediate(resolve));
function setup(fetch, href = 'http://127.0.0.1:8787/') {
  const calls = [], timers = new Map(); let sequence = 0;
  const context = vm.createContext({ URL, DOMException, AbortController,
    location: { href }, fetch: (...args) => { calls.push(args); return fetch(...args); },
    setTimeout: callback => { timers.set(++sequence, callback); return sequence; },
    clearTimeout: id => timers.delete(id),
  });
  vm.runInContext(source, context);
  return { start: (stage='tidy', base='http://127.0.0.1:8787', id='capture-1', timeout=960000) =>
    context.kashaCommands.start(base, id, stage, timeout), calls, timers,
    expire: () => [...timers.values()].forEach(callback => callback()), };
}
const response = text => ({ ok: true, status: 200, text: async () => text });

test('Полный текст, прежний контракт HTTP и отсутствие abort после успеха', async () => {
  const body = deferred();
  const fixture = setup(async () => ({ ok:true, status:200, text:() => body.promise }));
  const request=fixture.start(); let done=false; request.promise.then(() => { done=true; });
  await tick(); assert.equal(done, false);
  const [url, options] = fixture.calls[0];
  assert.equal(url,'http://127.0.0.1:8787/api/captures/capture-1/tidy');
  assert.equal(options.method,'POST'); assert.equal(options.headers['X-Kasha-Client'],'web');
  assert.equal(options.redirect,'error'); assert.equal(options.mode,'same-origin');
  body.resolve('{"transcript":"Ирина не удаляла 1200 строк"}');
  assert.equal(await request.promise,'{"transcript":"Ирина не удаляла 1200 строк"}');
  assert.equal(options.signal.aborted,false); assert.equal(fixture.timers.size,0);
  request.cancel(); fixture.expire();
  assert.equal(options.signal.aborted,false);
});

test('Отмена до headers отклоняет Promise и прерывает fetch', async () => {
  const headers=deferred(), fixture=setup(() => headers.promise), request=fixture.start();
  const rejection=assert.rejects(request.promise, {name:'AbortError'});
  request.cancel(); await rejection;
  assert.equal(fixture.calls[0][1].signal.aborted,true); assert.equal(fixture.timers.size,0);
  headers.resolve(response('late')); await tick();
});

test('Отмена во время чтения тела не принимает поздний ответ', async () => {
  const body=deferred(), fixture=setup(async () => ({ok:true,status:200,text:()=>body.promise}));
  const request=fixture.start(); await tick();
  const rejection=assert.rejects(request.promise, {name:'AbortError'});
  request.cancel(); body.resolve('late'); await rejection;
  assert.equal(fixture.calls[0][1].signal.aborted,true); assert.equal(fixture.timers.size,0);
});

test('Таймаут действует и после headers, очистка таймера обязательна', async () => {
  const body=deferred(), fixture=setup(async () => ({ok:true,status:200,text:()=>body.promise}));
  const request=fixture.start('retranscribe'); await tick();
  const rejection=assert.rejects(request.promise, {name:'TimeoutError'});
  fixture.expire(); await rejection;
  assert.equal(fixture.calls[0][1].signal.aborted,true); assert.equal(fixture.timers.size,0);
  body.reject(new Error('transport cancelled')); await tick();
});

test('HTTP 500, сетевой отказ и оборванное тело не превращаются в успех', async () => {
  for (const fetch of [async()=>({ok:false,status:500,text:()=>Promise.resolve('private data')}),
      async()=>{throw Error('network failed');},
      async()=>({ok:true,status:200,text:async()=>{throw Error('truncated body');}})]) {
    const fixture=setup(fetch), request=fixture.start();
    await assert.rejects(request.promise); assert.equal(fixture.timers.size,0);
    assert.equal(fixture.calls[0][1].signal.aborted,true);
  }
});

test('Внешний runtime, credentials, произвольный путь и некорректная команда запрещены до сети', () => {
  const fixture=setup(async()=>response('ok'));
  for (const base of ['https://evil.invalid','http://127.0.0.1:8888', 'http://name:secret@127.0.0.1:8787',
      'http://127.0.0.1:8787/path','http://127.0.0.1:8787/?x=1']) {
    assert.throws(()=>fixture.start('tidy',base));
  }
  for (const stage of ['delete','tidy?x=1','../notes']) assert.throws(()=>fixture.start(stage));
  for (const id of ['../notes', 'x/y','', 'x?y', 'x'.repeat(129)]) assert.throws(()=>fixture.start('tidy',undefined,id));
  for (const timeout of [0,-1,NaN,Infinity,4000001]) assert.throws(()=>fixture.start('tidy',undefined,undefined,timeout));
  assert.equal(fixture.calls.length,0);
});

test('Прежний dev-сервер 8080 может обратиться только к закреплённому loopback runtime', async () => {
  const fixture=setup(async()=>response('{}'),'http://localhost:8080/');
  await fixture.start().promise; assert.equal(fixture.calls[0][1].mode,'cors');
  assert.throws(()=>fixture.start('tidy','http://127.0.0.1:8788'));
  const remote=setup(async()=>response('{}'),'https://external.invalid:8080');
  assert.throws(()=>remote.start());
});

test('Независимые команды не отменяют друг друга', async () => {
  const replies=[deferred(),deferred()]; let index=0;
  const fixture=setup(()=>replies[index++].promise);
  const first=fixture.start(), second=fixture.start('rank');
  const rejection=assert.rejects(first.promise,{name:'AbortError'});
  first.cancel(); await rejection;
  replies[1].resolve(response('{}')); await second.promise;
  assert.equal(fixture.calls[0][1].signal.aborted,true);
  assert.equal(fixture.calls[1][1].signal.aborted,false); assert.equal(fixture.timers.size,0);
  replies[0].resolve(response('late')); await tick();
});
