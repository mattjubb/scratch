// REST fetch wrappers and SSE subscription lifecycle.

import { logEntry, byteSize } from './log.js';

function scopeParams(scope) {
  const params = new URLSearchParams();
  if (scope.books?.length) params.set('books', scope.books.join(','));
  else if (scope.book) params.set('book', scope.book);
  else if (scope.desk) params.set('desk', scope.desk);
  return params;
}

async function get(path, params) {
  const qs = params && [...params].length ? `?${params}` : '';
  const url = `${path}${qs}`;
  const t0 = performance.now();
  const res = await fetch(url);
  const text = await res.text();
  const ms = performance.now() - t0;
  let payload;
  try {
    payload = JSON.parse(text);
  } catch {
    payload = text;
  }
  logEntry({
    direction: 'in',
    kind: 'rest',
    label: `GET ${url}`,
    status: res.status,
    bytes: byteSize(text),
    ms,
    payload,
  });
  if (!res.ok) throw new Error(`${path} failed: ${res.status}`);
  return payload;
}

export const fetchDesks = () => get('/api/desks');

export function fetchSnapshot(scope, asof) {
  const params = scopeParams(scope);
  if (asof) params.set('asof', asof);
  return Promise.all([
    get('/api/trades', new URLSearchParams(params)),
    get('/api/events', new URLSearchParams(params)),
    get('/api/positions', new URLSearchParams(params)),
  ]).then(([trades, events, positions]) => ({ trades, events, positions }));
}

export const fetchTransfers = (parentRef) =>
  get('/api/transfers', new URLSearchParams({ parentRef }));

export const fetchSupplemental = (parentRef) =>
  get('/api/supplemental', new URLSearchParams({ parentRef }));

export async function bookTradeEvent(payload) {
  const body = JSON.stringify(payload);
  logEntry({
    direction: 'out',
    kind: 'rest',
    label: 'POST /api/events',
    status: 'sent',
    bytes: byteSize(body),
    payload,
  });
  const t0 = performance.now();
  const res = await fetch('/api/events', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body,
  });
  const text = await res.text();
  const ms = performance.now() - t0;
  let result;
  try {
    result = JSON.parse(text);
  } catch {
    result = text;
  }
  logEntry({
    direction: 'in',
    kind: 'rest',
    label: `POST /api/events → ${res.status}`,
    status: res.status,
    bytes: byteSize(text),
    ms,
    payload: result,
  });
  if (!res.ok) {
    throw new Error((result && result.error) || `book failed: ${res.status}`);
  }
  return result;
}

export const fetchInstrumentMeta = () => get('/api/instrument-meta');

export async function createInstrument(payload) {
  const body = JSON.stringify(payload);
  logEntry({
    direction: 'out',
    kind: 'rest',
    label: 'POST /api/instruments',
    status: 'sent',
    bytes: byteSize(body),
    payload,
  });
  const t0 = performance.now();
  const res = await fetch('/api/instruments', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body,
  });
  const text = await res.text();
  const ms = performance.now() - t0;
  let result;
  try {
    result = JSON.parse(text);
  } catch {
    result = text;
  }
  logEntry({
    direction: 'in',
    kind: 'rest',
    label: `POST /api/instruments → ${res.status}`,
    status: res.status,
    bytes: byteSize(text),
    ms,
    payload: result,
  });
  if (!res.ok) {
    throw new Error((result && result.error) || `create failed: ${res.status}`);
  }
  return result;
}

let eventSource = null;

// Opens the SSE stream for the scope. handlers: { trade, tradeEvent,
// positions, status }. Closes any previous stream first.
export function openStream(scope, handlers) {
  closeStream();
  handlers.status?.('connecting');
  const url = `/api/stream?${scopeParams(scope)}`;
  eventSource = new EventSource(url);
  eventSource.onopen = () => {
    handlers.status?.('open');
    logEntry({ direction: 'in', kind: 'sse', label: `SSE open ${url}`, status: 'open', bytes: 0 });
  };
  eventSource.onerror = () => {
    handlers.status?.('error');
    logEntry({ direction: 'in', kind: 'sse', label: 'SSE error', status: 'error', bytes: 0 });
  };
  for (const name of ['trade', 'tradeEvent', 'positions']) {
    eventSource.addEventListener(name, (e) => {
      const payload = JSON.parse(e.data);
      logEntry({
        direction: 'in',
        kind: 'sse',
        label: name,
        status: 'event',
        bytes: byteSize(e.data),
        payload,
      });
      handlers[name]?.(payload);
    });
  }
  return eventSource;
}

export function closeStream() {
  if (eventSource) {
    eventSource.close();
    eventSource = null;
  }
}
