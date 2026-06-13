import {
  fetchDesks,
  fetchSnapshot,
  fetchTransfers,
  fetchSupplemental,
  bookTradeEvent,
  openStream,
  closeStream,
} from './api.js';
import { createGrids, positionId, upsert, setTradeFilter, clearTradeFilter } from './grids.js';
import { initTheme } from './theme.js';
import { initWireLog } from './wirelog.js';
import { initInstrumentDialog } from './instrument.js';
import { defaultAsOf, asOfParam, toLocalInput } from './dates.js';

const state = {
  scope: { books: [] }, // selected book ids (possibly across desks)
  mode: 'close',        // 'close' | 'live'
  asof: defaultAsOf(),  // datetime-local value for the close-mode picker
  tab: 'trades',        // 'trades' | 'events' | 'positions'
  tradeFilter: null,    // trade rows the events/positions view is filtered by
};

let desks = [];         // [{ deskId, name, books: [{ bookId, name }] }]

const els = {
  scopeButton: document.getElementById('scope-button'),
  scopeMenu: document.getElementById('scope-menu'),
  scopeScrim: document.getElementById('scope-scrim'),
  scopeTree: document.getElementById('scope-tree'),
  scopeConfirm: document.getElementById('scope-confirm'),
  scopeCancel: document.getElementById('scope-cancel'),
  scopeCount: document.getElementById('scope-count'),
  modeClose: document.getElementById('mode-close'),
  modeLive: document.getElementById('mode-live'),
  dateField: document.getElementById('date-field'),
  asofDate: document.getElementById('asof-date'),
  liveStatus: document.getElementById('live-status'),
  statusDot: document.getElementById('status-dot'),
  statusText: document.getElementById('status-text'),
  drawer: document.getElementById('drawer'),
  drawerOverlay: document.getElementById('drawer-overlay'),
  drawerRef: document.getElementById('drawer-ref'),
  drawerClose: document.getElementById('drawer-close'),
  drawerResizer: document.getElementById('drawer-resizer'),
  bookBtn: document.getElementById('book-event-btn'),
  bookDialog: document.getElementById('book-dialog'),
  bookForm: document.getElementById('book-form'),
  bookClose: document.getElementById('book-close'),
  bookCancel: document.getElementById('book-cancel'),
  bookError: document.getElementById('book-error'),
  evtType: document.getElementById('evt-type'),
  evtBook: document.getElementById('evt-book'),
  evtTradeRef: document.getElementById('evt-traderef'),
  evtInstrument: document.getElementById('evt-instrument'),
  evtQuantity: document.getElementById('evt-quantity'),
  evtUser: document.getElementById('evt-user'),
  evtAsof: document.getElementById('evt-asof'),
  fieldTradeRef: document.getElementById('field-traderef'),
  fieldInstrument: document.getElementById('field-instrument'),
  fieldQuantity: document.getElementById('field-quantity'),
  contextMenu: document.getElementById('trade-context-menu'),
  filterBar: document.getElementById('filter-bar'),
  filterText: document.getElementById('filter-text'),
  filterClear: document.getElementById('filter-clear'),
};

const grids = createGrids({
  onTradeClicked: (trade) => openDrawer(trade.tradeRef),
  onEventClicked: (event) => openDrawer(event.eventRef),
});

// ---------- data loading ----------

async function refresh() {
  clearActiveFilter(); // a new snapshot invalidates any trade-derived filter
  const asof = state.mode === 'close' ? asOfParam(state.asof) : undefined;
  const { trades, events, positions } = await fetchSnapshot(state.scope, asof);
  grids.trades.setGridOption('rowData', trades);
  grids.events.setGridOption('rowData', events);
  grids.positions.setGridOption('rowData', positions);
}

function setStatus(status) {
  els.statusDot.className = `status-dot ${status === 'connecting' ? '' : status}`;
  els.statusText.textContent =
    status === 'open' ? 'live' : status === 'error' ? 'reconnecting…' : 'connecting…';
}

function applyMode() {
  const live = state.mode === 'live';
  els.modeClose.classList.toggle('active', !live);
  els.modeLive.classList.toggle('active', live);
  els.dateField.classList.toggle('hidden', live);
  els.liveStatus.classList.toggle('hidden', !live);

  closeStream();
  refresh();
  if (live) {
    openStream(state.scope, {
      status: setStatus,
      trade: (t) => upsert(grids.trades, [t], (r) => r.tradeRef),
      tradeEvent: (e) => upsert(grids.events, [e], (r) => r.eventRef),
      positions: (rows) => upsert(grids.positions, rows, positionId),
    });
  }
}

// ---------- drawer ----------

async function openDrawer(parentRef) {
  els.drawerRef.textContent = parentRef;
  els.drawerOverlay.hidden = false;
  els.drawer.classList.add('open');
  els.drawer.setAttribute('aria-hidden', 'false');
  const [transfers, supplemental] = await Promise.all([
    fetchTransfers(parentRef),
    fetchSupplemental(parentRef),
  ]);
  grids.transfers.setGridOption('rowData', transfers);
  grids.supplemental.setGridOption('rowData', supplemental);
}

function closeDrawer() {
  els.drawer.classList.remove('open');
  els.drawer.setAttribute('aria-hidden', 'true');
  els.drawerOverlay.hidden = true;
}

els.drawerClose.addEventListener('click', closeDrawer);
els.drawerOverlay.addEventListener('click', closeDrawer);
document.addEventListener('keydown', (e) => {
  if (e.key === 'Escape') {
    closeDrawer();
    closeScopeMenu();
  }
});

// Drag the left edge to resize the drawer (width clamped via CSS min/max).
els.drawerResizer.addEventListener('pointerdown', (e) => {
  e.preventDefault();
  els.drawer.classList.add('resizing');
  els.drawerResizer.setPointerCapture(e.pointerId);

  const onMove = (ev) => {
    const width = window.innerWidth - ev.clientX;
    els.drawer.style.setProperty('--drawer-width', `${width}px`);
  };
  const onUp = () => {
    els.drawer.classList.remove('resizing');
    els.drawerResizer.removeEventListener('pointermove', onMove);
    els.drawerResizer.removeEventListener('pointerup', onUp);
  };
  els.drawerResizer.addEventListener('pointermove', onMove);
  els.drawerResizer.addEventListener('pointerup', onUp);
});

// ---------- scope menu (desk -> book tree) ----------

async function populateScopes() {
  desks = await fetchDesks();
  for (const desk of desks) {
    const section = document.createElement('div');
    section.className = 'tree-desk';
    section.innerHTML = `
      <div class="tree-row">
        <button class="tree-toggle" type="button" aria-label="Expand/collapse ${desk.name}">▾</button>
        <label><input type="checkbox" data-desk="${desk.deskId}" />
          <span class="tree-desk-name">${desk.name}</span></label>
      </div>
      <div class="tree-books">
        ${desk.books
          .map(
            (b) => `
          <div class="tree-row">
            <label><input type="checkbox" data-book="${b.bookId}" data-desk="${desk.deskId}" />
              <span>${b.name} <span class="book-code">${b.bookId}</span></span></label>
          </div>`
          )
          .join('')}
      </div>`;
    els.scopeTree.appendChild(section);
  }
  // default: all books of the first desk
  state.scope.books = desks[0].books.map((b) => b.bookId);
  updateScopeButton();
}

function bookCheckboxes() {
  return [...els.scopeTree.querySelectorAll('input[data-book]')];
}

function pendingBooks() {
  return bookCheckboxes().filter((cb) => cb.checked).map((cb) => cb.dataset.book);
}

// Desk checkboxes reflect their books: checked / indeterminate / unchecked.
function syncDeskCheckboxes() {
  for (const deskCb of els.scopeTree.querySelectorAll('input[data-desk]:not([data-book])')) {
    const books = bookCheckboxes().filter((cb) => cb.dataset.desk === deskCb.dataset.desk);
    const checked = books.filter((cb) => cb.checked).length;
    deskCb.checked = checked === books.length;
    deskCb.indeterminate = checked > 0 && checked < books.length;
  }
  const n = pendingBooks().length;
  els.scopeConfirm.disabled = n === 0;
  els.scopeCount.textContent =
    n === 0 ? 'No books selected' : `${n} book${n === 1 ? '' : 's'} selected`;
}

function updateScopeButton() {
  const set = new Set(state.scope.books);
  const fullDesk = desks.find(
    (d) => d.books.length === set.size && d.books.every((b) => set.has(b.bookId))
  );
  if (fullDesk) {
    els.scopeButton.textContent = `${fullDesk.name} — all books`;
  } else if (set.size === 1) {
    const id = [...set][0];
    const book = desks.flatMap((d) => d.books).find((b) => b.bookId === id);
    els.scopeButton.textContent = book ? book.name : id;
  } else {
    els.scopeButton.textContent = `${set.size} books`;
  }
}

function openScopeMenu() {
  // load the working copy from the applied selection
  const set = new Set(state.scope.books);
  for (const cb of bookCheckboxes()) cb.checked = set.has(cb.dataset.book);
  syncDeskCheckboxes();
  els.scopeScrim.hidden = false;
  els.scopeMenu.classList.add('open');
  els.scopeMenu.setAttribute('aria-hidden', 'false');
}

function closeScopeMenu() {
  els.scopeMenu.classList.remove('open');
  els.scopeMenu.setAttribute('aria-hidden', 'true');
  els.scopeScrim.hidden = true;
}

els.scopeButton.addEventListener('click', openScopeMenu);
els.scopeCancel.addEventListener('click', closeScopeMenu);
els.scopeScrim.addEventListener('click', closeScopeMenu);

els.scopeTree.addEventListener('change', (e) => {
  const cb = e.target;
  if (!cb.dataset.book && cb.dataset.desk) {
    // desk toggled — apply to all its books
    for (const bookCb of bookCheckboxes()) {
      if (bookCb.dataset.desk === cb.dataset.desk) bookCb.checked = cb.checked;
    }
  }
  syncDeskCheckboxes();
});

els.scopeTree.addEventListener('click', (e) => {
  const toggle = e.target.closest('.tree-toggle');
  if (!toggle) return;
  toggle.classList.toggle('collapsed');
  toggle.closest('.tree-desk').querySelector('.tree-books').classList.toggle('collapsed');
});

els.scopeConfirm.addEventListener('click', () => {
  state.scope.books = pendingBooks();
  updateScopeButton();
  closeScopeMenu();
  closeDrawer();
  applyMode(); // refetch and, in live mode, resubscribe to the new scope
});

els.modeClose.addEventListener('click', () => {
  if (state.mode === 'close') return;
  state.mode = 'close';
  applyMode();
});

els.modeLive.addEventListener('click', () => {
  if (state.mode === 'live') return;
  state.mode = 'live';
  applyMode();
});

els.asofDate.addEventListener('change', () => {
  if (!els.asofDate.value) {
    els.asofDate.value = defaultAsOf(); // close mode requires an as-of timestamp
  }
  state.asof = els.asofDate.value;
  refresh();
});

// ---------- book trade event dialog ----------

// Show only the fields relevant to the chosen event type.
//   NEW    -> book, instrument, quantity   AMEND -> trade ref, quantity   CANCEL -> trade ref
function updateBookFields() {
  const type = els.evtType.value;
  els.fieldTradeRef.hidden = type === 'NEW';
  els.fieldInstrument.hidden = type !== 'NEW';
  els.fieldQuantity.hidden = type === 'CANCEL';
}

function openBookDialog({ instrumentRef } = {}) {
  els.evtBook.innerHTML = desks
    .flatMap((d) => d.books)
    .map((b) => `<option value="${b.bookId}">${b.name} (${b.bookId})</option>`)
    .join('');
  // sensible default book: first currently-selected book, else first overall
  if (state.scope.books[0]) els.evtBook.value = state.scope.books[0];
  els.evtType.value = 'NEW';
  els.evtTradeRef.value = '';
  els.evtInstrument.value = instrumentRef || '';
  els.evtQuantity.value = '';
  els.evtUser.value = '';
  els.evtAsof.value = state.mode === 'close' ? state.asof : toLocalInput(new Date());
  els.bookError.hidden = true;
  updateBookFields();
  els.bookDialog.showModal();
}

els.bookBtn.addEventListener('click', openBookDialog);
els.evtType.addEventListener('change', updateBookFields);
els.bookClose.addEventListener('click', () => els.bookDialog.close());
els.bookCancel.addEventListener('click', () => els.bookDialog.close());

els.bookForm.addEventListener('submit', async (e) => {
  e.preventDefault();
  const type = els.evtType.value;
  const payload = {
    eventType: type,
    bookId: els.evtBook.value,
    user: els.evtUser.value,
    asOf: asOfParam(els.evtAsof.value),
  };
  if (type === 'NEW') {
    payload.instrumentRef = els.evtInstrument.value;
    payload.quantity = els.evtQuantity.value;
  } else {
    payload.tradeRef = els.evtTradeRef.value;
    if (type === 'AMEND') payload.quantity = els.evtQuantity.value;
  }

  els.bookError.hidden = true;
  try {
    await bookTradeEvent(payload);
    els.bookDialog.close();
    refresh(); // pull the snapshot; live streams also receive the change
  } catch (err) {
    els.bookError.textContent = err.message;
    els.bookError.hidden = false;
  }
});

// ---------- tabs ----------

function switchTab(name) {
  state.tab = name;
  document.querySelectorAll('.tab').forEach((t) => {
    const active = t.dataset.tab === name;
    t.classList.toggle('active', active);
    t.setAttribute('aria-selected', String(active));
  });
  document.querySelectorAll('.grid-panel').forEach((p) => {
    p.classList.toggle('hidden', p.id !== `panel-${name}`);
  });
  renderFilterBar();
}

document.querySelectorAll('.tab').forEach((tab) => {
  tab.addEventListener('click', () => switchTab(tab.dataset.tab));
});

// ---------- trades context menu & filtered views ----------

// Show only the events / positions belonging to a set of trades, switch to that
// tab, and surface a banner so it's clear the view is filtered.
function applyTradeFilter(trades, tab) {
  state.tradeFilter = trades;
  setTradeFilter(trades);
  switchTab(tab); // renders the banner
}

function clearActiveFilter() {
  if (!state.tradeFilter) return;
  state.tradeFilter = null;
  clearTradeFilter();
  grids.trades.deselectAll();
  renderFilterBar();
}

function renderFilterBar() {
  const trades = state.tradeFilter;
  const onFilterableTab = state.tab === 'events' || state.tab === 'positions';
  if (!trades || !onFilterableTab) {
    els.filterBar.classList.add('hidden');
    return;
  }
  const what = state.tab === 'events' ? 'trade events' : 'positions';
  const refs = trades.map((t) => t.tradeRef);
  const shown = refs.slice(0, 3).join(', ') + (refs.length > 3 ? `, +${refs.length - 3} more` : '');
  els.filterText.innerHTML =
    `Showing <b>${what}</b> for <b>${refs.length}</b> selected trade${refs.length === 1 ? '' : 's'}: ${shown}`;
  els.filterBar.classList.remove('hidden');
}

let contextTargets = [];

// Right-click on a trade row → custom context menu. Handled on the panel
// (AG Grid Community has no built-in context menu); the row is resolved from
// the event target via the grid API.
document.getElementById('panel-trades').addEventListener('contextmenu', (e) => {
  e.preventDefault();
  const rowEl = e.target.closest('.ag-row');
  const node = rowEl && grids.trades.getRowNode(rowEl.getAttribute('row-id'));
  if (!node) {
    hideContextMenu();
    return;
  }
  const selected = grids.trades.getSelectedRows();
  const clicked = node.data;
  const inSelection = selected.some((r) => r.tradeRef === clicked.tradeRef);
  contextTargets = selected.length && inSelection ? selected : [clicked];

  const m = els.contextMenu;
  m.hidden = false;
  const rect = m.getBoundingClientRect();
  m.style.left = `${Math.min(e.clientX, window.innerWidth - rect.width - 8)}px`;
  m.style.top = `${Math.min(e.clientY, window.innerHeight - rect.height - 8)}px`;
});

function hideContextMenu() {
  els.contextMenu.hidden = true;
}

els.contextMenu.querySelectorAll('.context-item').forEach((item) => {
  item.addEventListener('click', () => {
    const tab = item.dataset.action; // 'events' | 'positions'
    applyTradeFilter(contextTargets, tab);
    hideContextMenu();
  });
});

els.filterClear.addEventListener('click', clearActiveFilter);
window.addEventListener('click', (e) => {
  if (!els.contextMenu.hidden && !els.contextMenu.contains(e.target)) hideContextMenu();
});
window.addEventListener('blur', hideContextMenu);
document.addEventListener('scroll', hideContextMenu, true);
document.addEventListener('keydown', (e) => {
  if (e.key === 'Escape') hideContextMenu();
});

// ---------- boot ----------

initTheme();
initWireLog();
initInstrumentDialog({
  onInstrumentCreated: (instrument) => {
    // make created refs autocomplete in the trade-event instrument field
    const opt = document.createElement('option');
    opt.value = instrument.instrumentRef;
    opt.label = `${instrument.instrumentType} (${instrument.format})`;
    document.getElementById('instrument-options').appendChild(opt);
  },
  onUseInstrument: (instrumentRef) => openBookDialog({ instrumentRef }),
});
els.asofDate.value = state.asof;
populateScopes().then(applyMode);
