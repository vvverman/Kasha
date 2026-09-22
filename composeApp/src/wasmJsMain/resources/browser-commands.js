/* Только HTTP-транспорт AI-команд. Состояние записи и правила обработки остаются в Core. */
(() => {
  'use strict';
  const stages = new Set(['process', 'retranscribe', 'tidy', 'rank']);
  const loopback = host => ['127.0.0.1', 'localhost', '[::1]'].includes(host);

  function start(base, id, stage, timeoutMillis) {
    if (!stages.has(stage) || !/^[A-Za-z0-9_-]{1,128}$/.test(id) ||
        !Number.isSafeInteger(timeoutMillis) || timeoutMillis < 1 || timeoutMillis > 4000000) {
      throw new Error('Invalid capture command');
    }
    const origin = new URL(globalThis.location.href);
    const target = new URL(base);
    const development = loopback(origin.hostname) && origin.port === '8080' &&
      target.origin === 'http://127.0.0.1:8787';
    if (!['http:', 'https:'].includes(target.protocol) || target.username || target.password ||
        target.search || target.hash || target.pathname !== '/' ||
        (target.origin !== origin.origin && !development)) {
      throw new Error('Capture command must use the local application runtime');
    }
    const url = target.origin + '/api/captures/' + id + '/' + stage;
    const controller = new AbortController();
    let settled = false, timer, rejectPending;
    const abort = error => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      // Отмена прерывает и ожидание headers, и незавершённое чтение тела.
      controller.abort(error);
      rejectPending(error);
    };
    const promise = new Promise((resolve, reject) => {
      rejectPending = reject;
      timer = setTimeout(() => abort(new DOMException('Capture command timed out', 'TimeoutError')), timeoutMillis);
      (async () => {
        try {
          const response = await globalThis.fetch(url, {
            method: 'POST', headers: { 'X-Kasha-Client': 'web', 'Accept': 'application/json' },
            signal: controller.signal, redirect: 'error', credentials: 'same-origin',
            mode: development ? 'cors' : 'same-origin', cache: 'no-store',
          });
          if (settled) return;
          if (!response.ok) throw new Error('Capture command HTTP ' + response.status);
          // Успех — только после полного чтения тела, а не сразу после HTTP 200.
          const body = await response.text();
          if (settled) return;
          settled = true;
          clearTimeout(timer);
          // В отличие от Ktor JS 3.5.2, успешное завершение не вызывает abort после EOF.
          resolve(body);
        } catch (error) {
          if (!settled) abort(error);
        }
      })();
    });
    return { promise, cancel: () => abort(new DOMException('Capture command cancelled', 'AbortError')) };
  }
  globalThis.kashaCommands = Object.freeze({ start });
})();
