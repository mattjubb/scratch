// Bottom debug panel: renders the wire log (REST + SSE traffic) with
// per-message size/timing metrics and a click-to-inspect payload view.

import { subscribe, getEntries, clearEntries } from './log.js';

function fmtTime(ts) {
  const d = new Date(ts);
  const p = (n, len = 2) => String(n).padStart(len, '0');
  return `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}.${p(d.getMilliseconds(), 3)}`;
}

function fmtSize(bytes) {
  if (bytes == null) return '';
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(2)} MB`;
}

function fmtMs(ms) {
  if (ms == null) return '';
  if (ms < 1) return '<1 ms';
  return `${ms.toFixed(ms < 10 ? 1 : 0)} ms`;
}

export function initWireLog() {
  const panel = document.getElementById('wirelog');
  const toggle = document.getElementById('wirelog-toggle');
  const caret = toggle.querySelector('.wirelog-caret');
  const stats = document.getElementById('wirelog-stats');
  const list = document.getElementById('wirelog-list');
  const detail = document.getElementById('wirelog-detail');
  const clearBtn = document.getElementById('wirelog-clear');

  let selectedId = null;

  function showDetail(entry) {
    selectedId = entry.id;
    for (const el of list.children) el.classList.toggle('selected', Number(el.dataset.id) === entry.id);
    const meta = [
      ['Time', fmtTime(entry.ts)],
      ['Direction', entry.direction === 'out' ? 'outgoing →' : '← incoming'],
      ['Transport', entry.kind.toUpperCase()],
      ['Label', entry.label],
      ['Status', String(entry.status)],
      ['Size', fmtSize(entry.bytes)],
      ['Duration', entry.ms == null ? '—' : fmtMs(entry.ms)],
    ];
    const metaHtml = meta
      .map(([k, v]) => `<div class="kv"><span>${k}</span><b>${escapeHtml(v)}</b></div>`)
      .join('');
    const body =
      entry.payload === undefined
        ? '<div class="wirelog-empty">No payload.</div>'
        : `<pre class="wirelog-pre">${escapeHtml(JSON.stringify(entry.payload, null, 2))}</pre>`;
    detail.innerHTML = `<div class="wirelog-meta">${metaHtml}</div>${body}`;
  }

  function makeRow(entry) {
    const row = document.createElement('div');
    row.className = `wirelog-row dir-${entry.direction}`;
    if (entry.status === 'error' || (typeof entry.status === 'number' && entry.status >= 400)) {
      row.classList.add('is-error');
    }
    row.dataset.id = entry.id;
    row.innerHTML =
      `<span class="col-time">${fmtTime(entry.ts)}</span>` +
      `<span class="col-dir">${entry.direction === 'out' ? '↑' : '↓'}</span>` +
      `<span class="col-label" title="${escapeHtml(entry.label)}">${escapeHtml(entry.label)}</span>` +
      `<span class="col-status">${escapeHtml(String(entry.status))}</span>` +
      `<span class="col-size">${fmtSize(entry.bytes)}</span>` +
      `<span class="col-ms">${fmtMs(entry.ms)}</span>`;
    row.addEventListener('click', () => showDetail(entry));
    return row;
  }

  function updateStats(entries) {
    const total = entries.reduce((sum, e) => sum + (e.bytes || 0), 0);
    stats.textContent = `${entries.length} message${entries.length === 1 ? '' : 's'} · ${fmtSize(total)}`;
  }

  // Incremental render: prepend new rows, rebuild on clear.
  subscribe((entry, entries) => {
    if (entry === null) {
      list.innerHTML = '';
      detail.innerHTML = '<div class="wirelog-empty">Select a message to inspect its payload.</div>';
      selectedId = null;
    } else {
      const atTop = list.scrollTop <= 4;
      list.prepend(makeRow(entry));
      while (list.children.length > entries.length) list.lastChild.remove();
      if (atTop) list.scrollTop = 0;
    }
    updateStats(entries);
  });

  // Render anything logged before init (e.g. the initial snapshot fetch).
  for (const entry of [...getEntries()].reverse()) list.prepend(makeRow(entry));
  updateStats(getEntries());

  toggle.addEventListener('click', () => {
    const collapsed = panel.classList.toggle('collapsed');
    toggle.setAttribute('aria-expanded', String(!collapsed));
    caret.textContent = collapsed ? '▸' : '▾';
  });

  clearBtn.addEventListener('click', clearEntries);
}

function escapeHtml(s) {
  return String(s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
}
