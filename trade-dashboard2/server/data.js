'use strict';

// In-memory mock data store: desks, books, trades, trade events,
// position transfers and derived positions. Deterministic-ish but
// randomised per process start.

const DESKS = [
  { deskId: 'FX', name: 'FX Desk' },
  { deskId: 'RATES', name: 'Rates Desk' },
  { deskId: 'CREDIT', name: 'Credit Desk' },
];

const BOOKS = [
  { bookId: 'FX-SPOT', name: 'FX Spot', deskId: 'FX' },
  { bookId: 'FX-FWD', name: 'FX Forwards', deskId: 'FX' },
  { bookId: 'FX-OPT', name: 'FX Options', deskId: 'FX' },
  { bookId: 'RATES-GOV', name: 'Govt Bonds', deskId: 'RATES' },
  { bookId: 'RATES-SWAP', name: 'IR Swaps', deskId: 'RATES' },
  { bookId: 'CREDIT-IG', name: 'Investment Grade', deskId: 'CREDIT' },
  { bookId: 'CREDIT-HY', name: 'High Yield', deskId: 'CREDIT' },
];

// External book used as counterparty side for some transfers.
const EXTERNAL_BOOK = 'EXT-SETTLE';

const INSTRUMENTS = {
  'FX-SPOT': ['EURUSD', 'GBPUSD', 'USDJPY', 'USDCHF'],
  'FX-FWD': ['EURUSD-3M', 'GBPUSD-6M', 'USDJPY-1Y'],
  'FX-OPT': ['EURUSD-C-1.10', 'GBPUSD-P-1.25'],
  'RATES-GOV': ['UST-10Y', 'UST-2Y', 'BUND-10Y', 'GILT-10Y'],
  'RATES-SWAP': ['USD-IRS-5Y', 'EUR-IRS-10Y', 'GBP-OIS-2Y'],
  'CREDIT-IG': ['AAPL-28', 'MSFT-31', 'JPM-27'],
  'CREDIT-HY': ['TSLA-29', 'CCL-27', 'AMC-26'],
};

const USERS = ['amartin', 'bchen', 'cdavies', 'dpatel', 'efischer', 'system'];
const POSITION_TAGS = ['HOUSE', 'CLIENT', 'HEDGE', 'RISK'];
const EVENT_TYPES = ['NEW', 'AMEND', 'CANCEL'];

// Instrument creation metadata.
const INSTRUMENT_TYPES = [
  'FX_SPOT',
  'FX_FORWARD',
  'FX_OPTION',
  'IR_SWAP',
  'BOND',
  'CDS',
  'EQUITY',
];
const INSTRUMENT_FORMATS = ['symbol', 'voice', 'fpml', 'cdm'];
// Selectable terms for the "voice" key/value format.
const VOICE_TERMS = [
  'Currency',
  'Currency Pair',
  'Notional',
  'Start Date',
  'End Date',
  'Maturity',
  'Fixed Rate',
  'Floating Index',
  'Day Count',
  'Strike',
  'Option Type',
  'Coupon',
  'Issuer',
  'Reference Entity',
];

const trades = [];          // newest last
const events = [];
const transfers = [];       // keyed lookup via transfersByParent
const transfersByParent = new Map();
const instruments = [];     // created via the UI; keyed lookup via instrumentsByRef
const instrumentsByRef = new Map();

let tradeSeq = 1000;
let eventSeq = 5000;
let transferSeq = 9000;
let instrumentSeq = 7000;

function rand(arr) {
  return arr[Math.floor(Math.random() * arr.length)];
}

function randInt(min, max) {
  return min + Math.floor(Math.random() * (max - min + 1));
}

function toDateStr(d) {
  return d.toISOString().slice(0, 10);
}

function isBusinessDay(d) {
  const dow = d.getUTCDay();
  return dow !== 0 && dow !== 6;
}

// Last `n` business days strictly before today, oldest first — today's
// activity only ever comes from the live mutation stream, so live rows
// always carry the latest asAt.
function recentBusinessDays(n) {
  const days = [];
  const d = new Date();
  d.setUTCHours(0, 0, 0, 0);
  d.setUTCDate(d.getUTCDate() - 1);
  while (days.length < n) {
    if (isBusinessDay(d)) days.unshift(toDateStr(new Date(d)));
    d.setUTCDate(d.getUTCDate() - 1);
  }
  return days;
}

function addTransfers(parentRef, bookId, instrumentRef, quantity) {
  const list = [];
  const legs = randInt(1, 3);
  for (let i = 0; i < legs; i++) {
    const internal = Math.random() < 0.5;
    const deskId = BOOKS.find((b) => b.bookId === bookId).deskId;
    const siblings = BOOKS.filter((b) => b.bookId !== bookId && b.deskId === deskId);
    const other = internal && siblings.length ? rand(siblings).bookId : EXTERNAL_BOOK;
    const incoming = i % 2 === 0;
    const t = {
      transferRef: `XFR-${transferSeq++}`,
      parentRef,
      book1: incoming ? other : bookId,
      book2: incoming ? bookId : other,
      instrumentRef,
      quantity: i === 0 ? quantity : Math.round(quantity / legs),
      positionTag: rand(POSITION_TAGS),
    };
    transfers.push(t);
    list.push(t);
  }
  transfersByParent.set(parentRef, list);
  return list;
}

function makeTrade(bookId, asOf, asAt) {
  const instrumentRef = rand(INSTRUMENTS[bookId]);
  const trade = {
    tradeRef: `TRD-${tradeSeq++}`,
    bookId,
    instrumentRef,
    quantity: randInt(1, 200) * 1000,
    price: Number((Math.random() * 150 + 0.5).toFixed(4)),
    status: 'LIVE',
    asOf,
    asAt,
    user: rand(USERS),
  };
  trades.push(trade);
  addTransfers(trade.tradeRef, bookId, instrumentRef, trade.quantity);
  addEvent(trade, 'NEW', asOf, asAt);
  return trade;
}

function addEvent(trade, eventType, asOf, asAt, user = rand(USERS)) {
  const ev = {
    eventRef: `EVT-${eventSeq++}`,
    tradeRef: trade.tradeRef,
    bookId: trade.bookId,
    eventType,
    asOf,
    asAt,
    user,
  };
  events.push(ev);
  addTransfers(ev.eventRef, trade.bookId, trade.instrumentRef, trade.quantity);
  return ev;
}

function amendTrade(trade, asOf, asAt) {
  trade.quantity = Math.max(1000, trade.quantity + randInt(-50, 50) * 1000);
  trade.price = Number((trade.price * (1 + (Math.random() - 0.5) / 50)).toFixed(4));
  trade.status = 'AMENDED';
  trade.asAt = asAt;
  return addEvent(trade, 'AMEND', asOf, asAt);
}

function cancelTrade(trade, asOf, asAt) {
  trade.status = 'CANCELLED';
  trade.asAt = asAt;
  return addEvent(trade, 'CANCEL', asOf, asAt);
}

// A random UTC timestamp during the business hours of a given day.
function stampOn(day) {
  const p = (n) => String(n).padStart(2, '0');
  return `${day}T${p(randInt(8, 17))}:${p(randInt(0, 59))}:${p(randInt(0, 59))}Z`;
}

// Seed ~10 business days of history.
function seed() {
  const days = recentBusinessDays(10);
  for (const day of days) {
    for (const book of BOOKS) {
      const n = randInt(2, 5);
      for (let i = 0; i < n; i++) {
        const asOf = stampOn(day);  // effective time (now a timestamp)
        const asAt = stampOn(day);  // recorded time
        const trade = makeTrade(book.bookId, asOf, asAt);
        const r = Math.random();
        if (r < 0.25) amendTrade(trade, asOf, asAt);
        else if (r < 0.35) cancelTrade(trade, asOf, asAt);
      }
    }
  }
  trades.sort((a, b) => a.asAt.localeCompare(b.asAt));
  events.sort((a, b) => a.asAt.localeCompare(b.asAt));
}

function booksInScope({ desk, book, books }) {
  if (books && books.length) {
    const set = new Set(books);
    return BOOKS.filter((b) => set.has(b.bookId)).map((b) => b.bookId);
  }
  if (book) return BOOKS.filter((b) => b.bookId === book).map((b) => b.bookId);
  if (desk) return BOOKS.filter((b) => b.deskId === desk).map((b) => b.bookId);
  return BOOKS.map((b) => b.bookId);
}

function getTrades(scope, asof) {
  const ids = new Set(booksInScope(scope));
  return trades.filter((t) => ids.has(t.bookId) && (!asof || t.asOf <= asof));
}

function getEvents(scope, asof) {
  const ids = new Set(booksInScope(scope));
  return events.filter((e) => ids.has(e.bookId) && (!asof || e.asOf <= asof));
}

// Positions: net transfer quantities into/out of in-scope books, by
// book/instrument/tag, considering only transfers whose parent event/trade
// is on or before asof.
function getPositions(scope, asof) {
  const ids = new Set(booksInScope(scope));
  const parentAsOf = new Map();
  for (const t of trades) parentAsOf.set(t.tradeRef, t.asOf);
  for (const e of events) parentAsOf.set(e.eventRef, e.asOf);

  const agg = new Map();
  for (const x of transfers) {
    const pAsOf = parentAsOf.get(x.parentRef);
    if (asof && pAsOf && pAsOf > asof) continue;
    for (const [bookId, sign] of [[x.book2, 1], [x.book1, -1]]) {
      if (!ids.has(bookId)) continue;
      const key = `${bookId}|${x.instrumentRef}|${x.positionTag}`;
      const cur = agg.get(key) || {
        bookId,
        instrumentRef: x.instrumentRef,
        positionTag: x.positionTag,
        quantity: 0,
      };
      cur.quantity += sign * x.quantity;
      agg.set(key, cur);
    }
  }
  return [...agg.values()].sort(
    (a, b) =>
      a.bookId.localeCompare(b.bookId) ||
      a.instrumentRef.localeCompare(b.instrumentRef) ||
      a.positionTag.localeCompare(b.positionTag)
  );
}

function getTransfers(parentRef) {
  return transfersByParent.get(parentRef) || [];
}

function getTrade(tradeRef) {
  return trades.find((t) => t.tradeRef === tradeRef);
}

// Supplemental metadata (key/value pairs) about a trade or trade event, shown
// in the drawer. Generated once per ref and cached so it's stable on reopen.
const supplementalByRef = new Map();
const CLEARING_STATUS = ['Cleared', 'Pending', 'Uncleared'];
const CLEARING_HOUSES = ['LCH', 'CME', 'ICE Clear', 'Eurex'];
const SOURCES = ['Voice', 'Electronic', 'API', 'Sales'];
const CONFIRM_STATUS = ['Confirmed', 'Affirmed', 'Unconfirmed'];
const BOOKING_SYSTEMS = ['Murex', 'Calypso', 'Summit', 'Internal'];
const COUNTERPARTIES = ['Goldman Sachs', 'JP Morgan', 'Barclays', 'Citadel', 'Deutsche Bank', 'UBS'];
const STRATEGIES = ['Macro', 'Relative Value', 'Market Making', 'Hedging'];

// `from` may be a date or an ISO timestamp; returns a date string.
function addBusinessDays(from, n) {
  const d = new Date(`${String(from).slice(0, 10)}T00:00:00Z`);
  let added = 0;
  while (added < n) {
    d.setUTCDate(d.getUTCDate() + 1);
    if (isBusinessDay(d)) added++;
  }
  return toDateStr(d);
}

function getSupplemental(parentRef) {
  if (supplementalByRef.has(parentRef)) return supplementalByRef.get(parentRef);

  let trade, event;
  if (String(parentRef).startsWith('EVT-')) {
    event = events.find((e) => e.eventRef === parentRef);
    if (event) trade = getTrade(event.tradeRef);
  } else {
    trade = getTrade(parentRef);
  }
  const book = trade ? BOOKS.find((b) => b.bookId === trade.bookId) : null;
  const desk = book ? DESKS.find((d) => d.deskId === book.deskId) : null;

  const pairs = [];
  const push = (key, value) => pairs.push({ key, value: value == null ? '—' : String(value) });

  push('Reference', parentRef);
  if (event) {
    push('Event Type', event.eventType);
    push('Trade Ref', event.tradeRef);
  }
  push('Trader', (event || trade)?.user);
  push('Desk', desk?.name);
  push('Book', trade?.bookId);
  push('Instrument', trade?.instrumentRef);
  push('Trade Status', trade?.status);
  push('Clearing Status', rand(CLEARING_STATUS));
  push('Clearing House', rand(CLEARING_HOUSES));
  push('Counterparty', rand(COUNTERPARTIES));
  push('Trade Source', rand(SOURCES));
  push('Confirmation', rand(CONFIRM_STATUS));
  push('Booking System', rand(BOOKING_SYSTEMS));
  push('Strategy', rand(STRATEGIES));
  push('As-Of', trade?.asOf);
  push('Settlement Date', trade?.asOf ? addBusinessDays(trade.asOf, 2) : null);

  supplementalByRef.set(parentRef, pairs);
  return pairs;
}

// Create an instrument from one of four spec formats and mint a reference.
//   symbol -> spec is a non-empty string
//   voice  -> spec is an array of { term, value } pairs
//   fpml   -> spec is an XML string
//   cdm    -> spec is a JSON/text string
function createInstrument(input) {
  const instrumentType = String(input.instrumentType || '');
  if (!INSTRUMENT_TYPES.includes(instrumentType)) {
    throw new Error(`instrumentType must be one of ${INSTRUMENT_TYPES.join(', ')}`);
  }
  const format = String(input.format || '').toLowerCase();
  if (!INSTRUMENT_FORMATS.includes(format)) {
    throw new Error(`format must be one of ${INSTRUMENT_FORMATS.join(', ')}`);
  }

  let spec;
  if (format === 'voice') {
    const pairs = Array.isArray(input.spec) ? input.spec : [];
    spec = pairs
      .map((p) => ({ term: String(p.term || '').trim(), value: String(p.value || '').trim() }))
      .filter((p) => p.term && p.value);
    if (!spec.length) throw new Error('voice format needs at least one term/value pair');
  } else {
    spec = String(input.spec || '').trim();
    if (!spec) throw new Error(`${format} format needs a non-empty value`);
  }

  const instrument = {
    instrumentRef: `INS-${instrumentSeq++}`,
    instrumentType,
    format,
    spec,
    createdAt: new Date().toISOString(),
  };
  instruments.push(instrument);
  instrumentsByRef.set(instrument.instrumentRef, instrument);
  return instrument;
}

function getInstrument(instrumentRef) {
  return instrumentsByRef.get(instrumentRef);
}

// Listeners notified whenever the store changes (e.g. a booked event), so
// live SSE streams can forward in-scope changes. Each callback receives
// { trade, event, books }.
const listeners = new Set();

function subscribe(fn) {
  listeners.add(fn);
  return () => listeners.delete(fn);
}

function publish(change) {
  for (const fn of listeners) fn(change);
}

// Book a trade event from the UI.
//   NEW    -> creates a new trade and its NEW event (needs bookId, instrumentRef, quantity)
//   AMEND  -> updates an existing trade's quantity (needs tradeRef, quantity)
//   CANCEL -> cancels an existing trade (needs tradeRef)
// Returns { trade, event, books } and notifies live subscribers.
function bookEvent(input) {
  const eventType = String(input.eventType || '').toUpperCase();
  if (!EVENT_TYPES.includes(eventType)) {
    throw new Error(`eventType must be one of ${EVENT_TYPES.join(', ')}`);
  }
  const user = (input.user || '').trim() || 'system';
  const now = new Date();
  const asAt = now.toISOString();
  const asOf = input.asOf || now.toISOString();

  let trade, event;
  if (eventType === 'NEW') {
    const bookId = input.bookId;
    if (!BOOKS.some((b) => b.bookId === bookId)) throw new Error(`unknown book ${bookId}`);
    const instrumentRef = (input.instrumentRef || '').trim();
    if (!instrumentRef) throw new Error('instrumentRef is required for NEW');
    const quantity = Number(input.quantity);
    if (!Number.isFinite(quantity) || quantity <= 0) throw new Error('quantity must be a positive number');

    trade = {
      tradeRef: `TRD-${tradeSeq++}`,
      bookId,
      instrumentRef,
      quantity,
      price: Number((Math.random() * 150 + 0.5).toFixed(4)),
      status: 'LIVE',
      asOf,
      asAt,
      user,
    };
    trades.push(trade);
    addTransfers(trade.tradeRef, bookId, instrumentRef, quantity);
    event = addEvent(trade, 'NEW', asOf, asAt, user);
  } else {
    trade = getTrade(input.tradeRef);
    if (!trade) throw new Error(`unknown trade ${input.tradeRef}`);
    if (eventType === 'AMEND') {
      const quantity = Number(input.quantity);
      if (!Number.isFinite(quantity) || quantity <= 0) throw new Error('quantity must be a positive number');
      trade.quantity = quantity;
      trade.status = 'AMENDED';
    } else {
      trade.status = 'CANCELLED';
    }
    trade.asAt = asAt;
    event = addEvent(trade, eventType, asOf, asAt, user);
  }

  const change = { trade, event, books: [trade.bookId] };
  publish(change);
  return change;
}

// Random live mutation within scope. Returns { trade, event, books } where
// books is the set of book ids whose positions changed.
function mutate(scope) {
  const ids = booksInScope(scope);
  const bookId = rand(ids);
  const now = new Date();
  const asOf = now.toISOString();
  const asAt = now.toISOString();

  const liveTrades = trades.filter((t) => t.bookId === bookId && t.status !== 'CANCELLED');
  const r = Math.random();
  let trade, event;
  if (r < 0.5 || liveTrades.length === 0) {
    trade = makeTrade(bookId, asOf, asAt);
    event = events[events.length - 1];
  } else if (r < 0.85) {
    trade = rand(liveTrades);
    event = amendTrade(trade, asOf, asAt);
  } else {
    trade = rand(liveTrades);
    event = cancelTrade(trade, asOf, asAt);
  }
  return { trade, event, books: [bookId] };
}

seed();

module.exports = {
  DESKS,
  BOOKS,
  INSTRUMENT_TYPES,
  VOICE_TERMS,
  getTrades,
  getEvents,
  getPositions,
  getTransfers,
  getSupplemental,
  booksInScope,
  bookEvent,
  createInstrument,
  getInstrument,
  subscribe,
  mutate,
};
