// AG Grid setup for the trades / events / positions tabs and the
// transfers drawer grid. Uses the AG Grid v34 Theming API (themeQuartz)
// with dark mode driven by the data-ag-theme-mode attribute on <html>.

import { formatAsAt } from './dates.js';

const { createGrid, themeQuartz } = agGrid;

const theme = themeQuartz
  // compact density for all modes (default spacing is 8px)
  .withParams({ spacing: 4, fontSize: 12, headerFontSize: 12 })
  .withParams({ accentColor: '#2563eb' }, 'light')
  .withParams(
    {
      accentColor: '#60a5fa',
      backgroundColor: '#1a2029',
      foregroundColor: '#e6ebf2',
      borderColor: '#303a48',
      chromeBackgroundColor: '#11151c',
    },
    'dark'
  );

const numberFormatter = (p) =>
  p.value == null ? '' : p.value.toLocaleString('en-US');

const asAtCol = {
  field: 'asAt',
  headerName: 'AsAt',
  minWidth: 190,
  valueFormatter: (p) => formatAsAt(p.value),
  sort: 'desc',
};

const baseOptions = {
  theme,
  defaultColDef: {
    sortable: true,
    resizable: true,
    filter: true,
    flex: 1,
    minWidth: 110,
    enableCellChangeFlash: true,
  },
  animateRows: true,
  suppressCellFocus: true,
};

// External filter shared state, populated when the user picks "Show Trade
// Events"/"Show Positions" from the trades context menu. null => no filter.
//   tradeRefs: events grid shows only events whose tradeRef is in this set.
//   posKeys:   positions grid shows only book|instrument keys in this set
//              (a trade's positions are those matching its book + instrument).
const tradeFilter = { tradeRefs: null, posKeys: null };
let filteredApis = { events: null, positions: null };

export function setTradeFilter(trades) {
  if (!trades || !trades.length) {
    tradeFilter.tradeRefs = null;
    tradeFilter.posKeys = null;
  } else {
    tradeFilter.tradeRefs = new Set(trades.map((t) => t.tradeRef));
    tradeFilter.posKeys = new Set(trades.map((t) => `${t.bookId}|${t.instrumentRef}`));
  }
  filteredApis.events?.onFilterChanged();
  filteredApis.positions?.onFilterChanged();
}

export const clearTradeFilter = () => setTradeFilter(null);

export function createGrids({ onTradeClicked, onEventClicked }) {
  const trades = createGrid(document.getElementById('panel-trades'), {
    ...baseOptions,
    getRowId: (p) => p.data.tradeRef,
    rowSelection: { mode: 'multiRow', checkboxes: true, headerCheckbox: true },
    onCellClicked: (e) => {
      if (e.column?.getColId?.() === 'ag-Grid-SelectionColumn') return; // checkbox column
      onTradeClicked(e.data);
    },
    columnDefs: [
      { field: 'tradeRef', headerName: 'Trade Ref', minWidth: 130 },
      { field: 'asOf', headerName: 'AsOf', minWidth: 190, valueFormatter: (p) => formatAsAt(p.value) },
      asAtCol,
      { field: 'user', headerName: 'User' },
      { field: 'bookId', headerName: 'Book', minWidth: 120 },
      { field: 'instrumentRef', headerName: 'Instrument', minWidth: 130 },
      { field: 'quantity', headerName: 'Quantity', type: 'rightAligned', valueFormatter: numberFormatter },
      { field: 'price', headerName: 'Price', type: 'rightAligned' },
      { field: 'status', headerName: 'Status' },
    ],
  });

  const events = createGrid(document.getElementById('panel-events'), {
    ...baseOptions,
    getRowId: (p) => p.data.eventRef,
    onRowClicked: (e) => onEventClicked(e.data),
    isExternalFilterPresent: () => tradeFilter.tradeRefs !== null,
    doesExternalFilterPass: (node) => tradeFilter.tradeRefs.has(node.data.tradeRef),
    columnDefs: [
      { field: 'eventRef', headerName: 'Event Ref', minWidth: 130 },
      { field: 'tradeRef', headerName: 'Trade Ref', minWidth: 130 },
      { field: 'eventType', headerName: 'Type' },
      { field: 'asOf', headerName: 'AsOf', minWidth: 190, valueFormatter: (p) => formatAsAt(p.value) },
      asAtCol,
      { field: 'user', headerName: 'User' },
      { field: 'bookId', headerName: 'Book', minWidth: 120 },
    ],
  });

  const positions = createGrid(document.getElementById('panel-positions'), {
    ...baseOptions,
    getRowId: (p) => positionId(p.data),
    isExternalFilterPresent: () => tradeFilter.posKeys !== null,
    doesExternalFilterPass: (node) =>
      tradeFilter.posKeys.has(`${node.data.bookId}|${node.data.instrumentRef}`),
    columnDefs: [
      { field: 'bookId', headerName: 'Book', minWidth: 130 },
      { field: 'instrumentRef', headerName: 'Instrument', minWidth: 140 },
      { field: 'positionTag', headerName: 'Position Tag' },
      { field: 'quantity', headerName: 'Quantity', type: 'rightAligned', valueFormatter: numberFormatter },
    ],
  });

  const supplemental = createGrid(document.getElementById('panel-supplemental'), {
    ...baseOptions,
    getRowId: (p) => p.data.key,
    columnDefs: [
      { field: 'key', headerName: 'Field', flex: 1, minWidth: 120 },
      { field: 'value', headerName: 'Value', flex: 1.4, minWidth: 140 },
    ],
  });

  filteredApis = { events, positions };

  const transfers = createGrid(document.getElementById('panel-transfers'), {
    ...baseOptions,
    getRowId: (p) => p.data.transferRef,
    columnDefs: [
      { field: 'book1', headerName: 'Book 1', minWidth: 100 },
      { field: 'book2', headerName: 'Book 2', minWidth: 100 },
      { field: 'instrumentRef', headerName: 'Instrument', minWidth: 120 },
      { field: 'quantity', headerName: 'Quantity', type: 'rightAligned', valueFormatter: numberFormatter, minWidth: 90 },
      { field: 'positionTag', headerName: 'Tag', minWidth: 90 },
    ],
  });

  return { trades, events, positions, transfers, supplemental };
}

export function positionId(row) {
  return `${row.bookId}|${row.instrumentRef}|${row.positionTag}`;
}

// Add-or-update rows by row id (used when applying SSE updates).
export function upsert(gridApi, rows, idOf) {
  const add = [];
  const update = [];
  for (const row of rows) {
    (gridApi.getRowNode(idOf(row)) ? update : add).push(row);
  }
  gridApi.applyTransaction({ add, update, addIndex: 0 });
}
