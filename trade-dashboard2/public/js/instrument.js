// "Create instrument" dialog: pick an instrument type, then specify it via one
// of four formats (symbol / voice key-value pairs / FpML / CDM) and mint an
// instrument reference usable in the Book Trade Event dialog.

import { fetchInstrumentMeta, createInstrument } from './api.js';

export function initInstrumentDialog({ onInstrumentCreated, onUseInstrument }) {
  const els = {
    openBtn: document.getElementById('create-instrument-btn'),
    dialog: document.getElementById('instrument-dialog'),
    form: document.getElementById('instrument-form'),
    close: document.getElementById('instrument-close'),
    cancel: document.getElementById('instrument-cancel'),
    type: document.getElementById('inst-type'),
    tabs: [...document.querySelectorAll('.inst-tab')],
    panels: [...document.querySelectorAll('.inst-panel')],
    symbol: document.getElementById('inst-symbol'),
    voiceRows: document.getElementById('inst-voice-rows'),
    voiceAdd: document.getElementById('inst-voice-add'),
    fpml: document.getElementById('inst-fpml'),
    cdm: document.getElementById('inst-cdm'),
    error: document.getElementById('inst-error'),
    result: document.getElementById('inst-result'),
    resultRef: document.getElementById('inst-result-ref'),
    use: document.getElementById('inst-use'),
  };

  let format = 'symbol';
  let voiceTerms = [];
  let lastRef = null;

  fetchInstrumentMeta().then((meta) => {
    els.type.innerHTML = meta.types.map((t) => `<option value="${t}">${t}</option>`).join('');
    voiceTerms = meta.voiceTerms;
  });

  function voiceRow() {
    const row = document.createElement('div');
    row.className = 'voice-row';
    const opts = voiceTerms.map((t) => `<option value="${t}">${t}</option>`).join('');
    row.innerHTML =
      `<select class="voice-term">${opts}</select>` +
      `<input class="voice-value" placeholder="value" autocomplete="off" />` +
      `<button class="voice-remove" type="button" aria-label="Remove term">✕</button>`;
    row.querySelector('.voice-remove').addEventListener('click', () => {
      row.remove();
      if (!els.voiceRows.children.length) els.voiceRows.appendChild(voiceRow());
    });
    return row;
  }

  function selectTab(fmt) {
    format = fmt;
    els.tabs.forEach((t) => t.classList.toggle('active', t.dataset.fmt === fmt));
    els.panels.forEach((p) => p.classList.toggle('hidden', p.dataset.fmt !== fmt));
  }

  function open() {
    els.symbol.value = '';
    els.fpml.value = '';
    els.cdm.value = '';
    els.voiceRows.innerHTML = '';
    els.voiceRows.appendChild(voiceRow());
    els.error.hidden = true;
    els.result.hidden = true;
    lastRef = null;
    selectTab('symbol');
    els.dialog.showModal();
  }

  function buildSpec() {
    if (format === 'voice') {
      return [...els.voiceRows.querySelectorAll('.voice-row')].map((r) => ({
        term: r.querySelector('.voice-term').value,
        value: r.querySelector('.voice-value').value,
      }));
    }
    if (format === 'fpml') return els.fpml.value;
    if (format === 'cdm') return els.cdm.value;
    return els.symbol.value;
  }

  els.openBtn.addEventListener('click', open);
  els.tabs.forEach((t) => t.addEventListener('click', () => selectTab(t.dataset.fmt)));
  els.voiceAdd.addEventListener('click', () => els.voiceRows.appendChild(voiceRow()));
  els.close.addEventListener('click', () => els.dialog.close());
  els.cancel.addEventListener('click', () => els.dialog.close());

  els.use.addEventListener('click', () => {
    if (!lastRef) return;
    els.dialog.close();
    onUseInstrument?.(lastRef);
  });

  els.form.addEventListener('submit', async (e) => {
    e.preventDefault();
    els.error.hidden = true;
    try {
      const instrument = await createInstrument({
        instrumentType: els.type.value,
        format,
        spec: buildSpec(),
      });
      lastRef = instrument.instrumentRef;
      els.resultRef.textContent = lastRef;
      els.result.hidden = false;
      onInstrumentCreated?.(instrument);
    } catch (err) {
      els.error.textContent = err.message;
      els.error.hidden = false;
    }
  });
}
