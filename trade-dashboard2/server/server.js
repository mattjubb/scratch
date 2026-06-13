'use strict';

// Zero-dependency mock server: serves the static UI, a small REST API and
// an SSE stream of simulated trade activity.
//
//   node server/server.js [port]    (default 8030)

const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const data = require('./data');

const PORT = Number(process.argv[2] || process.env.PORT || 8030);
const PUBLIC_DIR = path.join(__dirname, '..', 'public');

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.ico': 'image/x-icon',
};

function json(res, status, body) {
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8' });
  res.end(JSON.stringify(body));
}

function scopeFrom(params) {
  const books = (params.get('books') || '').split(',').filter(Boolean);
  return {
    desk: params.get('desk') || undefined,
    book: params.get('book') || undefined,
    books: books.length ? books : undefined,
  };
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    let body = '';
    req.on('data', (chunk) => {
      body += chunk;
      if (body.length > 1e6) req.destroy();
    });
    req.on('end', () => resolve(body));
    req.on('error', reject);
  });
}

async function handleApi(req, res, url) {
  const params = url.searchParams;
  const scope = scopeFrom(params);
  const asof = params.get('asof') || undefined;

  if (req.method === 'POST' && url.pathname === '/api/events') {
    try {
      const payload = JSON.parse((await readBody(req)) || '{}');
      const { event } = data.bookEvent(payload);
      return json(res, 201, event);
    } catch (e) {
      return json(res, 400, { error: e.message });
    }
  }

  if (req.method === 'POST' && url.pathname === '/api/instruments') {
    try {
      const payload = JSON.parse((await readBody(req)) || '{}');
      return json(res, 201, data.createInstrument(payload));
    } catch (e) {
      return json(res, 400, { error: e.message });
    }
  }

  switch (url.pathname) {
    case '/api/desks':
      return json(
        res,
        200,
        data.DESKS.map((d) => ({
          ...d,
          books: data.BOOKS.filter((b) => b.deskId === d.deskId),
        }))
      );
    case '/api/trades':
      return json(res, 200, data.getTrades(scope, asof));
    case '/api/events':
      return json(res, 200, data.getEvents(scope, asof));
    case '/api/positions':
      return json(res, 200, data.getPositions(scope, asof));
    case '/api/transfers': {
      const parentRef = params.get('parentRef');
      if (!parentRef) return json(res, 400, { error: 'parentRef is required' });
      return json(res, 200, data.getTransfers(parentRef));
    }
    case '/api/supplemental': {
      const parentRef = params.get('parentRef');
      if (!parentRef) return json(res, 400, { error: 'parentRef is required' });
      return json(res, 200, data.getSupplemental(parentRef));
    }
    case '/api/instrument-meta':
      return json(res, 200, { types: data.INSTRUMENT_TYPES, voiceTerms: data.VOICE_TERMS });
    case '/api/stream':
      return handleStream(req, res, scope);
    default:
      return json(res, 404, { error: 'not found' });
  }
}

function handleStream(req, res, scope) {
  res.writeHead(200, {
    'Content-Type': 'text/event-stream',
    'Cache-Control': 'no-cache',
    Connection: 'keep-alive',
  });
  res.write(': connected\n\n');

  const send = (event, payload) => {
    res.write(`event: ${event}\ndata: ${JSON.stringify(payload)}\n\n`);
  };

  const scopeBooks = new Set(data.booksInScope(scope));
  const emitChange = ({ trade, event, books }) => {
    send('trade', trade);
    send('tradeEvent', event);
    send('positions', data.getPositions({ book: books[0] }));
  };

  let mutateTimer;
  const scheduleMutation = () => {
    mutateTimer = setTimeout(() => {
      emitChange(data.mutate(scope));
      scheduleMutation();
    }, 2000 + Math.random() * 3000);
  };
  scheduleMutation();

  // Forward booked events (from POST /api/events) that fall in this scope.
  const unsubscribe = data.subscribe((change) => {
    if (scopeBooks.has(change.books[0])) emitChange(change);
  });

  const heartbeat = setInterval(() => res.write(': heartbeat\n\n'), 15000);

  req.on('close', () => {
    clearTimeout(mutateTimer);
    clearInterval(heartbeat);
    unsubscribe();
  });
}

function serveStatic(res, urlPath) {
  const rel = urlPath === '/' ? 'index.html' : urlPath.replace(/^\/+/, '');
  const file = path.normalize(path.join(PUBLIC_DIR, rel));
  if (!file.startsWith(PUBLIC_DIR)) {
    res.writeHead(403);
    return res.end('forbidden');
  }
  fs.readFile(file, (err, buf) => {
    if (err) {
      res.writeHead(404, { 'Content-Type': 'text/plain' });
      return res.end('not found');
    }
    res.writeHead(200, { 'Content-Type': MIME[path.extname(file)] || 'application/octet-stream' });
    res.end(buf);
  });
}

const server = http.createServer((req, res) => {
  const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
  if (url.pathname.startsWith('/api/')) return handleApi(req, res, url);
  return serveStatic(res, url.pathname);
});

server.listen(PORT, () => {
  console.log(`trade-dashboard listening on http://localhost:${PORT}`);
});
