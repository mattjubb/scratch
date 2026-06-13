// In-memory wire log of backend traffic (REST round-trips + SSE events).
// The bottom debug panel renders from here. Newest entry first; capped so a
// long-running session can't grow unbounded.

const MAX_ENTRIES = 500;
const entries = [];
const listeners = new Set();
let nextId = 1;

const encoder = new TextEncoder();

// Byte size of a value as it would travel on the wire (UTF-8).
export function byteSize(value) {
  if (value == null) return 0;
  const str = typeof value === 'string' ? value : JSON.stringify(value);
  return encoder.encode(str).length;
}

// Record one entry. Returns the stored entry (with id/ts assigned).
//   direction: 'out' | 'in'
//   kind:      'rest' | 'sse'
//   label:     short human label, e.g. "GET /api/trades" or "tradeEvent"
//   status:    HTTP status or 'ok' | 'error'
//   bytes:     payload size in bytes
//   ms:        round-trip duration (rest only); undefined for sse
//   payload:   parsed body / event data for the inspector
export function logEntry(entry) {
  const stored = { id: nextId++, ts: Date.now(), ...entry };
  entries.unshift(stored);
  if (entries.length > MAX_ENTRIES) entries.length = MAX_ENTRIES;
  for (const fn of listeners) fn(stored, entries);
  return stored;
}

export function getEntries() {
  return entries;
}

export function clearEntries() {
  entries.length = 0;
  for (const fn of listeners) fn(null, entries);
}

export function subscribe(fn) {
  listeners.add(fn);
  return () => listeners.delete(fn);
}
