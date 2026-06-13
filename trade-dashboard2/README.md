# Trade Dashboard

A plain HTML/CSS/JS (no framework, no build step) trade dashboard using
[AG Grid Community](https://www.ag-grid.com/) from CDN, backed by a
zero-dependency Node mock server.

## Run

```sh
node server/server.js          # http://localhost:8030 (or: node server/server.js 9000)
```

## Features

- **Desk / book selector** — a slide-out tree menu (desk → books) with tri-state
  checkboxes: ticking a desk selects all its books, individual books can then be
  deselected, and any cross-desk combination is allowed. Confirm applies the
  selection and refreshes the grids.
- **Three tabs** — Trades, Trade Events and Positions, each an AG Grid.
- **Trades context menu** — right-click a trade (or select several via the row
  checkboxes and right-click) for **Show Trade Events** or **Show Positions**.
  Each switches to that tab filtered to the chosen trade(s) — events by trade
  ref, positions by the trade's book + instrument — and a banner makes the
  filtered view explicit, with a Clear filter button. Changing scope/date/mode
  clears the filter.
- **Details drawer** — click any row in Trades or Trade Events to slide out a
  right-hand drawer with two stacked tables: **Position transfers** (book1 /
  book2 / instrument ref / quantity / position tag) on top, and **Supplemental
  data** — key/value metadata about the trade or event (trader, desk, clearing
  status, counterparty, confirmation, settlement date, …) — below. Close with ✕,
  the overlay, or Escape.
- **Create instrument** — a header button opens a dialog to mint an instrument
  reference. Pick an instrument type, then specify it via one of four tabs:
  **Symbol** (text), **Voice** (selectable term/value pairs), **FpML** or **CDM**
  (free text). On create you get an `INS-…` reference; "Use in trade event"
  carries it straight into the Book Trade Event dialog (created refs also
  autocomplete in the instrument field).
- **Book trade event** — a header button opens a dialog to book a NEW trade
  (book + instrument + quantity), or an AMEND/CANCEL against an existing trade
  ref. On submit it POSTs to the backend; the grids refresh and live subscribers
  in the affected book receive the new event over SSE.
- **Close mode** — requires an as-of timestamp (UTC; defaults to end of the last
  business day); grids show a REST snapshot filtered to `asOf <= timestamp`.
  `asOf` is a full timestamp on trades/events, not just a date.
- **Live mode** — opens an SSE subscription (`EventSource`) scoped to the selected
  desk/book; new trades, trade events and position updates are applied with grid
  transactions and flash as they arrive. A status dot shows the stream state.
- **Backend messages panel** — an expandable section at the bottom logs every
  REST round-trip and SSE event with direction, status, payload size and timing;
  click a row to inspect the full JSON payload. Useful for debugging the wire
  protocol. Backed by [public/js/log.js](public/js/log.js) /
  [public/js/wirelog.js](public/js/wirelog.js).
- **Light/dark mode** — toggle in the header; persisted in localStorage, defaults
  to the OS preference. App chrome uses CSS custom properties; the grids use the
  AG Grid Theming API (`themeQuartz` + `data-ag-theme-mode`).

## API (mock server)

List endpoints take a scope — `?books=FX-SPOT,FX-FWD` (any set of books; this is
what the UI sends), or `?book=FX-SPOT` / `?desk=FX` — and optionally
`&asof=YYYY-MM-DD` (close mode):

| Endpoint | Description |
| --- | --- |
| `GET /api/desks` | Desks with nested books (populates the selector) |
| `GET /api/trades` | Trades in scope |
| `GET /api/events` | Trade events in scope |
| `GET /api/positions` | Net positions (book / instrument / tag / quantity) |
| `GET /api/transfers?parentRef=REF` | Position transfers for a trade or event ref |
| `GET /api/supplemental?parentRef=REF` | Key/value supplemental metadata for a trade or event ref |
| `POST /api/events` | Book a trade event; body `{ eventType, bookId, instrumentRef?, tradeRef?, quantity?, user?, asOf? }`. Returns the created event and notifies in-scope live streams. |
| `GET /api/instrument-meta` | Instrument types and selectable voice terms for the Create Instrument dialog |
| `POST /api/instruments` | Create an instrument; body `{ instrumentType, format, spec }` where `format` is `symbol\|voice\|fpml\|cdm`. Returns `{ instrumentRef, … }`. |
| `GET /api/stream?book=…` or `?desk=…` | SSE: named events `trade`, `tradeEvent`, `positions` |

## Pointing at a real backend

All server access goes through [public/js/api.js](public/js/api.js) — change the
paths/shapes there (and remove `server/`) to integrate with a real service. The
SSE contract is: named events `trade` and `tradeEvent` carrying a single row, and
`positions` carrying an array of position rows; rows are upserted by
`tradeRef` / `eventRef` / `book|instrument|tag`.
