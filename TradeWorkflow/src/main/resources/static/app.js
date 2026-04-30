'use strict';

const API = '/api';

// ─────────────────────────────────────────────────────────────────────────────
// State
// ─────────────────────────────────────────────────────────────────────────────
let workflows  = [];
let selectedWf = null;
let network    = null;
let activeView = 'graph';
let selectedItem = null; // { type: 'state'|'event', name }
let lastGraphEdges = []; // raw edges from server, for tap lookup

// ─────────────────────────────────────────────────────────────────────────────
// Bootstrap
// ─────────────────────────────────────────────────────────────────────────────
document.addEventListener('DOMContentLoaded', async () => {
  await refreshWorkflows();
  pingApi();
});

async function pingApi() {
  try {
    await apiFetch('/workflows');
    setApiStatus(true);
  } catch {
    setApiStatus(false);
  }
}

function setApiStatus(ok) {
  const el = document.getElementById('api-status');
  el.textContent = ok ? '● Connected' : '● Disconnected';
  el.style.color = ok ? 'hsl(var(--success))' : 'hsl(var(--destructive))';
}

// ─────────────────────────────────────────────────────────────────────────────
// API
// ─────────────────────────────────────────────────────────────────────────────
async function apiFetch(path, opts = {}) {
  const headers = { 'Content-Type': 'application/json', ...(opts.headers || {}) };
  const res = await fetch(API + path, { ...opts, headers });
  if (!res.ok) {
    const body = await res.json().catch(() => ({ error: res.statusText }));
    throw new Error(body.error || res.statusText);
  }
  if (res.status === 204) return null;
  const ct = res.headers.get('content-type') || '';
  return ct.includes('json') ? res.json() : res.text();
}

// ─────────────────────────────────────────────────────────────────────────────
// Workflow list
// ─────────────────────────────────────────────────────────────────────────────
async function refreshWorkflows() {
  try { workflows = await apiFetch('/workflows'); }
  catch { workflows = []; }
  renderWorkflowList();
}

function renderWorkflowList() {
  const el = document.getElementById('workflow-list');
  if (!workflows.length) {
    el.innerHTML = '<div style="padding:14px;color:hsl(var(--muted-foreground));font-size:12px">No workflows yet. Click + New to create one.</div>';
    return;
  }
  el.innerHTML = workflows.map(wf => `
    <div class="list-item${selectedWf?.id === wf.id ? ' active' : ''}"
         onclick="selectWorkflow('${wf.id}')">
      <div class="item-name">${esc(wf.name)}</div>
      <div class="item-sub">${wf.states?.length || 0} states · ${wf.events?.length || 0} events</div>
    </div>
  `).join('');
}

async function selectWorkflow(id) {
  try {
    selectedWf = await apiFetch(`/workflows/${id}`);
    selectedItem = null;
    renderWorkflowList();
    document.getElementById('wf-title').textContent = selectedWf.name;
    document.getElementById('wf-desc').textContent  = (selectedWf.description||'').replace(/\n/g,' ').trim();
    document.getElementById('view-tabs').style.display = 'flex';
    setWorkflowButtons(true);
    renderPanelDefault();
    await loadActiveView();
  } catch (e) { toast(e.message, 'error'); }
}

function setWorkflowButtons(on) {
  ['btn-add-state','btn-add-event','btn-del-wf'].forEach(id =>
    document.getElementById(id).disabled = !on);
}

// ─────────────────────────────────────────────────────────────────────────────
// View switching
// ─────────────────────────────────────────────────────────────────────────────
function switchView(view, tabEl) {
  activeView = view;
  document.querySelectorAll('.view-tab').forEach(t => t.classList.remove('active'));
  tabEl.classList.add('active');
  document.getElementById('view-graph').style.display = view === 'graph' ? 'flex' : 'none';
  document.getElementById('view-forms').style.display = view === 'forms' ? 'flex' : 'none';
  document.getElementById('view-yaml' ).style.display = view === 'yaml'  ? 'flex' : 'none';
  loadActiveView();
}

async function loadActiveView() {
  if (!selectedWf) return;
  if (activeView === 'graph') await renderGraph();
  if (activeView === 'forms') renderForms();
  if (activeView === 'yaml')  await renderYaml();
}

// ─────────────────────────────────────────────────────────────────────────────
// Graph view (Cytoscape.js + dagre)
// ─────────────────────────────────────────────────────────────────────────────
async function renderGraph() {
  const empty = document.getElementById('graph-empty');
  const container = document.getElementById('graph-network');

  if (typeof cytoscape === 'undefined') {
    empty.innerHTML = '<div style="color:hsl(var(--destructive))">Cytoscape failed to load.</div>';
    empty.style.display = 'flex';
    return;
  }
  empty.style.display = 'none';

  let graphData;
  try {
    graphData = await apiFetch(`/workflows/${selectedWf.id}/graph`);
  } catch (e) {
    empty.innerHTML = `<div style="color:hsl(var(--destructive))">Failed to load graph: ${esc(e.message)}</div>`;
    empty.style.display = 'flex';
    return;
  }
  lastGraphEdges = graphData.edges || [];

  if (!graphData.nodes.length) {
    empty.innerHTML = '<div class="icon">⬡</div><div>This workflow has no states yet.<br/>Click <strong>+ State</strong> to add one.</div>';
    empty.style.display = 'flex';
    return;
  }

  const elements = [
    ...graphData.nodes.map(n => ({
      data: { id: n.id, label: n.id, ...n },
      classes: [
        n.isInitial ? 'initial' : '',
        /SETTLED|DONE|COMPLETE|MATCHED|VALIDATED|HIGH/.test(n.id) ? 'success' : '',
        /FAIL|REJECT|CANCEL|UNMATCHED|LOW/.test(n.id)             ? 'danger'  : '',
      ].filter(Boolean).join(' '),
    })),
    ...graphData.edges.map(e => ({
      data: { id: e.id, source: e.from, target: e.to, label: e.label,
              trigger: e.trigger, eventName: e.eventName, outcomeName: e.outcomeName },
      classes: e.trigger === 'AUTO' ? 'auto' : 'external',
    })),
  ];

  if (network) { network.destroy(); network = null; }

  network = cytoscape({
    container,
    elements,
    wheelSensitivity: 0.2,

    style: [
      {
        selector: 'node',
        style: {
          'shape': 'round-rectangle',
          'background-color': 'hsl(240, 6%, 10%)',
          'border-color':     'hsl(240, 4%, 16%)',
          'border-width': 1,
          'label': 'data(label)',
          'color': 'hsl(0, 0%, 98%)',
          'text-valign': 'center',
          'text-halign': 'center',
          'font-family': 'Inter, sans-serif',
          'font-size': 13,
          'font-weight': 500,
          'padding': '16px',
          'width':  'label',
          'height': 'label',
          'text-wrap': 'wrap',
          'text-max-width': 160,
          'transition-property': 'border-color, background-color, border-width',
          'transition-duration': 150,
        },
      },
      { selector: 'node.initial', style: {
          'background-color': 'hsl(0, 0%, 98%)',
          'color':            'hsl(240, 6%, 10%)',
          'border-color':     'hsl(0, 0%, 98%)',
          'font-weight': 600,
      } },
      { selector: 'node.success', style: {
          'background-color': 'hsl(142, 71%, 12%)',
          'border-color':     'hsl(142, 71%, 35%)',
      } },
      { selector: 'node.danger', style: {
          'background-color': 'hsl(0, 62%, 12%)',
          'border-color':     'hsl(0, 72%, 40%)',
      } },
      { selector: 'node:selected', style: {
          'border-color': 'hsl(240, 5%, 84%)',
          'border-width': 2,
      } },
      { selector: 'node:active', style: { 'overlay-opacity': 0 } },

      {
        selector: 'edge',
        style: {
          'curve-style': 'taxi',
          'taxi-direction': 'vertical',
          'taxi-turn': 30,
          'taxi-turn-min-distance': 8,
          'width': 1.5,
          'line-color':         'hsl(240, 5%, 35%)',
          'target-arrow-color': 'hsl(240, 5%, 35%)',
          'target-arrow-shape': 'triangle',
          'arrow-scale': 1.1,
          'label': 'data(label)',
          'font-family': 'Inter, sans-serif',
          'font-size': 11,
          'font-weight': 500,
          'color': 'hsl(240, 5%, 65%)',
          'text-background-color':   'hsl(240, 10%, 4%)',
          'text-background-opacity': 1,
          'text-background-padding': 4,
          'text-background-shape':  'roundrectangle',
          'text-border-color':      'hsl(240, 4%, 16%)',
          'text-border-width': 1,
          'text-border-opacity': 1,
          'text-rotation': 'autorotate',
          'edge-text-rotation': 'autorotate',
          'transition-property': 'line-color, target-arrow-color, width',
          'transition-duration': 150,
        },
      },
      { selector: 'edge.auto', style: {
          'line-color':         'hsl(280, 60%, 60%)',
          'target-arrow-color': 'hsl(280, 60%, 60%)',
          'color':              'hsl(280, 60%, 75%)',
          'line-style':         'dashed',
          'line-dash-pattern':  [6, 4],
      } },
      { selector: 'edge:selected', style: {
          'line-color':         'hsl(0, 0%, 98%)',
          'target-arrow-color': 'hsl(0, 0%, 98%)',
          'width': 2.5,
      } },
    ],

    layout: {
      name: 'dagre',
      rankDir: 'TB', align: 'UL',
      nodeSep: 60, edgeSep: 30, rankSep: 90,
      ranker: 'network-simplex',
      fit: true, padding: 30, animate: false,
    },
  });

  network.on('tap', 'node', evt => {
    const data = evt.target.data();
    selectedItem = { type: 'state', name: data.id };
    renderStatePanelByName(data.id);
  });
  network.on('tap', 'edge', evt => {
    const data = evt.target.data();
    selectedItem = { type: 'event', name: data.eventName, outcomeName: data.outcomeName };
    renderEventPanelByName(data.eventName, data.outcomeName);
  });
  network.on('tap', evt => {
    if (evt.target === network) { selectedItem = null; renderPanelDefault(); }
  });
}


// ─────────────────────────────────────────────────────────────────────────────
// Forms view
// ─────────────────────────────────────────────────────────────────────────────
function renderForms() {
  if (!selectedWf) return;
  const states = selectedWf.states || [];
  const events = selectedWf.events || [];

  document.getElementById('states-count').textContent = states.length;
  document.getElementById('events-count').textContent = events.length;

  document.getElementById('states-list').innerHTML = states.map(s => {
    const isInitial = s.name === selectedWf.initialState;
    const isActive  = selectedItem?.type === 'state' && selectedItem?.name === s.name;
    return `
      <div class="form-card${isActive ? ' active' : ''}"
           onclick="selectFormItem('state','${esc(s.name)}')">
        <div class="card-name">
          ${esc(s.name)}
          ${isInitial ? '<span class="tag initial" style="font-size:9px">INITIAL</span>' : ''}
          ${(s.autoEvents||[]).length ? `<span class="tag auto" style="font-size:9px">AUTO →${s.autoEvents.length}</span>` : ''}
        </div>
        ${s.description ? `<div class="card-meta">${esc(s.description)}</div>` : ''}
        ${(s.autoEvents||[]).length ? `<div class="card-criteria">auto: ${esc((s.autoEvents||[]).join(', '))}</div>` : ''}
      </div>`;
  }).join('');

  document.getElementById('events-list').innerHTML = events.map(e => {
    const isActive = selectedItem?.type === 'event' && selectedItem?.name === e.name;
    const outs = e.outcomes || [];
    const branchTag = outs.length > 1
      ? `<span class="tag" style="background:hsl(38,92%,60%,0.12);color:hsl(38,92%,65%);border-color:hsl(38,92%,60%,0.3);font-size:9px">${outs.length} BRANCHES</span>`
      : '';
    return `
      <div class="form-card${isActive ? ' active' : ''}"
           onclick="selectFormItem('event','${esc(e.name)}')">
        <div class="card-name">
          ${esc(e.name)}
          <span class="tag ${e.trigger === 'AUTO' ? 'auto' : 'external'}" style="font-size:9px">${esc(e.trigger||'EXTERNAL')}</span>
          <span class="tag" style="background:hsl(var(--muted));color:hsl(var(--muted-foreground));border-color:hsl(var(--border));font-size:9px">${esc(e.type||'DECLARATIVE')}</span>
          ${branchTag}
        </div>
        <div class="card-route">from: ${esc(e.fromState||'?')}</div>
        ${e.description ? `<div class="card-meta" style="margin-top:3px">${esc(e.description)}</div>` : ''}
        <div style="margin-top:8px">
          ${outs.map(o => `
            <span class="outcome-chip">
              <strong>${esc(o.name)}</strong>
              <span class="outcome-arrow">→</span>
              ${esc(o.toState)}
              ${o.when ? `<span class="outcome-when">if ${esc(o.when)}</span>` : ''}
            </span>`).join('')}
        </div>
      </div>`;
  }).join('');
}

function selectFormItem(type, name) {
  selectedItem = { type, name };
  renderForms();
  if (type === 'state') renderStatePanelByName(name);
  else                  renderEventPanelByName(name);
}

// ─────────────────────────────────────────────────────────────────────────────
// YAML view
// ─────────────────────────────────────────────────────────────────────────────
let currentYaml = '';

async function renderYaml() {
  if (!selectedWf) return;
  document.getElementById('yaml-filename').textContent =
    (selectedWf.name || 'workflow').replace(/\s+/g,'_').toLowerCase() + '.yaml';
  try {
    currentYaml = await apiFetch(`/workflows/${selectedWf.id}/export`);
    document.getElementById('yaml-content').innerHTML = syntaxHighlightYaml(currentYaml);
  } catch (e) {
    document.getElementById('yaml-content').textContent = 'Failed to load YAML: ' + e.message;
  }
}

function copyYaml() {
  if (!currentYaml) return;
  navigator.clipboard.writeText(currentYaml).then(() => toast('Copied to clipboard'));
}

function downloadYaml() {
  if (!currentYaml || !selectedWf) return;
  const a = Object.assign(document.createElement('a'), {
    href: URL.createObjectURL(new Blob([currentYaml], { type: 'text/plain' })),
    download: (selectedWf.name || 'workflow').replace(/\s+/g,'_').toLowerCase() + '.yaml',
  });
  a.click();
  URL.revokeObjectURL(a.href);
}

function syntaxHighlightYaml(text) {
  const escape = s => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  const KEY = /^([\w][\w.-]*)(\s*):(\s*)(.*)$/;

  return text.split('\n').map(line => {
    let code = line, comment = '';
    const hashIdx = findCommentStart(line);
    if (hashIdx !== -1) { code = line.slice(0, hashIdx); comment = line.slice(hashIdx); }

    const indentMatch = code.match(/^(\s*)(.*)$/);
    const indent = indentMatch[1];
    const rest   = indentMatch[2];

    let html;
    const listKv = rest.match(/^(-\s+)([\w][\w.-]*)(\s*):(\s*)(.*)$/);
    if (listKv) {
      const [, dash, key, sp1, sp2, value] = listKv;
      html = `<span class="yp">${dash}</span><span class="yk">${escape(key)}</span>${sp1}:${sp2}${highlightValue(value)}`;
    } else {
      const kv = rest.match(KEY);
      if (kv) {
        const [, key, sp1, sp2, value] = kv;
        html = `<span class="yk">${escape(key)}</span>${sp1}:${sp2}${highlightValue(value)}`;
      } else {
        const li = rest.match(/^(-\s+)(.*)$/);
        if (li) html = `<span class="yp">${escape(li[1])}</span>${highlightValue(li[2])}`;
        else if (/^-\s*$/.test(rest)) html = `<span class="yp">${escape(rest)}</span>`;
        else html = highlightValue(rest);
      }
    }

    return indent + html + (comment ? `<span class="yc">${escape(comment)}</span>` : '');
  }).join('\n');

  function findCommentStart(s) {
    let inSingle = false, inDouble = false;
    for (let i = 0; i < s.length; i++) {
      const c = s[i];
      if (c === "'" && !inDouble) inSingle = !inSingle;
      else if (c === '"' && !inSingle) inDouble = !inDouble;
      else if (c === '#' && !inSingle && !inDouble) {
        if (i === 0 || /\s/.test(s[i - 1])) return i;
      }
    }
    return -1;
  }

  function highlightValue(v) {
    if (v === '') return '';
    const trimmed = v.trim();
    const qm = v.match(/^(\s*)(['"])(.*)\2(\s*)$/);
    if (qm) {
      return `${qm[1]}<span class="ys">${escape(qm[2] + qm[3] + qm[2])}</span>${qm[4]}`;
    }
    if (/^(true|false|null|yes|no|~)$/i.test(trimmed)) {
      return v.replace(trimmed, `<span class="yb">${escape(trimmed)}</span>`);
    }
    if (/^-?\d+(\.\d+)?$/.test(trimmed)) {
      return v.replace(trimmed, `<span class="yn">${escape(trimmed)}</span>`);
    }
    if (/^[|>][-+]?$/.test(trimmed)) {
      return v.replace(trimmed, `<span class="yb">${escape(trimmed)}</span>`);
    }
    return `<span class="yv">${escape(v)}</span>`;
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// Right panel
// ─────────────────────────────────────────────────────────────────────────────
function renderPanelDefault() {
  document.getElementById('panel-title').textContent = 'Details';
  document.getElementById('panel-subtitle').textContent = '';
  document.getElementById('panel-body').innerHTML =
    '<p style="color:hsl(var(--muted-foreground));font-size:13px;line-height:1.6">Click a state or event in the Graph or Forms view to inspect it here.</p>';
}

function renderStatePanelByName(name) {
  const s = selectedWf?.states?.find(x => x.name === name);
  if (!s) return;
  document.getElementById('panel-title').textContent = s.name;
  document.getElementById('panel-subtitle').textContent = 'STATE';
  const isInitial = s.name === selectedWf.initialState;

  document.getElementById('panel-body').innerHTML = `
    ${s.description ? `
    <div class="detail-section">
      <div class="detail-label">Description</div>
      <div class="detail-value">${esc(s.description)}</div>
    </div>` : ''}

    <div class="detail-section">
      <div class="detail-label">Flags</div>
      <div style="display:flex;gap:5px;flex-wrap:wrap">
        ${isInitial ? '<span class="tag initial">INITIAL</span>' : ''}
        ${!isInitial ? '<span style="color:hsl(var(--muted-foreground));font-size:12px">Intermediate state</span>' : ''}
      </div>
    </div>

    ${s.autoEvents?.length ? `
    <div class="detail-section">
      <div class="detail-label">Auto Events on Entry</div>
      <div style="display:flex;flex-wrap:wrap;gap:5px">
        ${s.autoEvents.map(e=>`<span class="tag auto">${esc(e)}</span>`).join('')}
      </div>
    </div>` : ''}

    <div class="detail-actions">
      <button class="btn" onclick="openEditStateModal('${esc(s.name)}')">Edit</button>
      <button class="btn danger" onclick="deleteState('${esc(s.name)}')">Delete</button>
    </div>
  `;
}

function renderEventPanelByName(name, highlightOutcome) {
  const ev = selectedWf?.events?.find(e => e.name === name);
  if (!ev) return;
  document.getElementById('panel-title').textContent = ev.name;
  document.getElementById('panel-subtitle').textContent = 'EVENT';

  const outcomes = ev.outcomes || [];
  const outcomesHtml = outcomes.map(o => {
    const setRows = Object.entries(o.set || {}).map(([k,v]) =>
      `<tr><td>${esc(k)}</td><td>${esc(v)}</td></tr>`).join('');
    const unsetList = (o.unset || []).map(k =>
      `<span style="font-family:'JetBrains Mono',monospace;font-size:11px;color:hsl(var(--destructive));text-decoration:line-through">${esc(k)}</span>`
    ).join(' ');
    const isHL = o.name === highlightOutcome;
    return `
      <div class="form-card" style="${isHL ? 'border-color:hsl(var(--ring));' : ''}">
        <div class="card-name">
          ${esc(o.name)}
          <span class="outcome-arrow" style="color:hsl(var(--muted-foreground));font-weight:400">→</span>
          <span style="color:hsl(var(--foreground))">${esc(o.toState)}</span>
        </div>
        ${o.when ? `<div class="card-criteria"><span style="color:hsl(38,92%,60%)">when</span> ${esc(o.when)}</div>` : ''}
        ${setRows ? `
          <div style="margin-top:8px">
            <div class="detail-label">Set</div>
            <table class="kv-table"><tbody>${setRows}</tbody></table>
          </div>` : ''}
        ${unsetList ? `
          <div style="margin-top:8px">
            <div class="detail-label">Unset</div>
            <div style="display:flex;flex-wrap:wrap;gap:5px">${unsetList}</div>
          </div>` : ''}
      </div>`;
  }).join('');

  const scriptSection = (ev.type === 'JAVASCRIPT' || ev.type === 'PYTHON') && ev.script ? `
    <div class="detail-section">
      <div class="detail-label">Script — ${esc(ev.type)}</div>
      <div class="code-block">${esc(ev.script)}</div>
    </div>` : '';

  document.getElementById('panel-body').innerHTML = `
    <div class="detail-section">
      <div class="detail-label">From</div>
      <div style="font-family:'JetBrains Mono',monospace;font-size:13px">${esc(ev.fromState||'?')}</div>
    </div>

    <div class="detail-section">
      <div class="detail-label">Trigger / Type</div>
      <div style="display:flex;gap:5px;flex-wrap:wrap">
        <span class="tag ${(ev.trigger||'').toLowerCase() === 'auto' ? 'auto' : 'external'}">${esc(ev.trigger||'EXTERNAL')}</span>
        <span class="tag" style="background:hsl(var(--muted));color:hsl(var(--muted-foreground));border-color:hsl(var(--border))">${esc(ev.type||'DECLARATIVE')}</span>
      </div>
    </div>

    ${ev.description ? `
    <div class="detail-section">
      <div class="detail-label">Description</div>
      <div class="detail-value">${esc(ev.description)}</div>
    </div>` : ''}

    ${scriptSection}

    <div class="detail-section">
      <div class="detail-label">Outcomes (${outcomes.length})</div>
      <div style="display:flex;flex-direction:column;gap:8px;margin-top:6px">
        ${outcomesHtml || '<span style="color:hsl(var(--muted-foreground));font-size:12px">No outcomes defined</span>'}
      </div>
    </div>

    <div class="detail-actions">
      <button class="btn" onclick="openEditEventModal('${esc(ev.name)}')">Edit</button>
      <button class="btn danger" onclick="deleteEvent('${esc(ev.name)}')">Delete</button>
    </div>
  `;
}

// ─────────────────────────────────────────────────────────────────────────────
// New Workflow
// ─────────────────────────────────────────────────────────────────────────────
function openNewWorkflowModal() {
  ['nwf-name','nwf-desc','nwf-initial'].forEach(id => document.getElementById(id).value = '');
  openModal('modal-new-workflow');
}

async function createWorkflow() {
  const name    = document.getElementById('nwf-name').value.trim();
  const initial = document.getElementById('nwf-initial').value.trim().toUpperCase();
  if (!name || !initial) { toast('Name and Initial State are required', 'error'); return; }
  const payload = {
    name,
    description: document.getElementById('nwf-desc').value.trim() || null,
    initialState: initial,
    states: [{ name: initial, description: 'Initial state', autoEvents: [] }],
    events: []
  };
  try {
    const wf = await apiFetch('/workflows', { method:'POST', body: JSON.stringify(payload) });
    closeModal('modal-new-workflow');
    toast(`Workflow "${wf.name}" created`);
    await refreshWorkflows();
    selectWorkflow(wf.id);
  } catch (e) { toast(e.message, 'error'); }
}

async function deleteCurrentWorkflow() {
  if (!selectedWf || !confirm(`Delete workflow "${selectedWf.name}"?`)) return;
  try {
    await apiFetch(`/workflows/${selectedWf.id}`, { method:'DELETE' });
    toast('Workflow deleted');
    selectedWf = null; selectedItem = null;
    if (network) { network.destroy(); network = null; }
    document.getElementById('wf-title').textContent = 'Select a workflow';
    document.getElementById('wf-desc').textContent  = 'Choose a workflow from the left panel to inspect it';
    document.getElementById('view-tabs').style.display = 'none';
    document.getElementById('graph-empty').style.display = 'flex';
    setWorkflowButtons(false);
    renderPanelDefault();
    await refreshWorkflows();
  } catch (e) { toast(e.message, 'error'); }
}

// ─────────────────────────────────────────────────────────────────────────────
// Add / Edit State
// ─────────────────────────────────────────────────────────────────────────────
function openAddStateModal() {
  document.getElementById('modal-state-title').textContent = 'Add State';
  ['st-name','st-desc','st-auto-events'].forEach(id => document.getElementById(id).value = '');
  document.getElementById('st-name').dataset.editName = '';
  openModal('modal-state');
}

function openEditStateModal(stateName) {
  const s = selectedWf?.states?.find(s => s.name === stateName);
  if (!s) return;
  document.getElementById('modal-state-title').textContent = 'Edit State';
  document.getElementById('st-name').value = s.name;
  document.getElementById('st-desc').value = s.description || '';
  document.getElementById('st-auto-events').value = (s.autoEvents||[]).join(', ');
  document.getElementById('st-name').dataset.editName = stateName;
  openModal('modal-state');
}

async function saveState() {
  if (!selectedWf) return;
  const name = document.getElementById('st-name').value.trim().toUpperCase();
  if (!name) { toast('State name is required', 'error'); return; }

  const autoEvents = document.getElementById('st-auto-events').value.trim()
    .split(',').map(s=>s.trim()).filter(Boolean);

  const editName = document.getElementById('st-name').dataset.editName;
  const wf = deepCopy(selectedWf);
  const newState = {
    name,
    description: document.getElementById('st-desc').value.trim() || null,
    autoEvents
  };

  if (editName) {
    const idx = wf.states.findIndex(s => s.name === editName);
    if (idx >= 0) {
      wf.states[idx] = newState;
      // Rename references if state was renamed
      if (editName !== name) {
        if (wf.initialState === editName) wf.initialState = name;
        wf.events.forEach(e => {
          if (e.fromState === editName) e.fromState = name;
          (e.outcomes || []).forEach(o => { if (o.toState === editName) o.toState = name; });
        });
      }
    } else {
      wf.states.push(newState);
    }
  } else {
    if (wf.states.some(s => s.name === name)) { toast(`State "${name}" already exists`, 'error'); return; }
    wf.states.push(newState);
  }
  await persistWorkflow(wf, 'modal-state');
}

async function deleteState(name) {
  if (!selectedWf || !confirm(`Delete state "${name}"?`)) return;
  const wf = deepCopy(selectedWf);
  wf.states = wf.states.filter(s => s.name !== name);
  // Remove events that originate from or target the deleted state
  wf.events = wf.events.filter(e =>
    e.fromState !== name && (e.outcomes||[]).every(o => o.toState !== name));
  selectedItem = null;
  await persistWorkflow(wf);
}

// ─────────────────────────────────────────────────────────────────────────────
// Add / Edit Event
// ─────────────────────────────────────────────────────────────────────────────
function populateStateSelects() {
  const opts = (selectedWf?.states||[]).map(s =>
    `<option value="${esc(s.name)}">${esc(s.name)}</option>`).join('');
  document.getElementById('ev-from').innerHTML = opts;
}

function openAddEventModal() {
  document.getElementById('modal-event-title').textContent = 'Add Event';
  ['ev-name','ev-desc','ev-script'].forEach(id => document.getElementById(id).value = '');
  document.getElementById('ev-trigger').value = 'EXTERNAL';
  document.getElementById('ev-type').value    = 'DECLARATIVE';
  document.getElementById('ev-name').dataset.editName = '';
  populateStateSelects();
  setOutcomeRows([{ name: 'ok', toState: '', when: '', set: '', unset: '' }]);
  onEventTypeChange();
  openModal('modal-event');
}

function openEditEventModal(eventName) {
  const ev = selectedWf?.events?.find(e => e.name === eventName);
  if (!ev) return;
  document.getElementById('modal-event-title').textContent = 'Edit Event';
  document.getElementById('ev-name').value    = ev.name;
  document.getElementById('ev-desc').value    = ev.description || '';
  document.getElementById('ev-trigger').value = ev.trigger || 'EXTERNAL';
  document.getElementById('ev-type').value    = ev.type    || 'DECLARATIVE';
  document.getElementById('ev-script').value  = ev.script  || '';
  populateStateSelects();
  document.getElementById('ev-from').value = ev.fromState || '';

  setOutcomeRows((ev.outcomes||[]).map(o => ({
    name:    o.name || '',
    toState: o.toState || '',
    when:    o.when || '',
    set:     Object.entries(o.set || {}).map(([k,v]) => `${k}=${v}`).join('\n'),
    unset:   (o.unset || []).join(', '),
  })));

  document.getElementById('ev-name').dataset.editName = eventName;
  onEventTypeChange();
  openModal('modal-event');
}

function onEventTypeChange() {
  const t = document.getElementById('ev-type').value;
  document.getElementById('ev-script-section').style.display = (t === 'JAVASCRIPT' || t === 'PYTHON') ? '' : 'none';
  // For scripts, hide the per-outcome `when`/`set`/`unset` since they're not used.
  document.querySelectorAll('.outcome-declarative-block').forEach(el => {
    el.style.display = (t === 'DECLARATIVE') ? '' : 'none';
  });
}

// ── Outcomes editor ──────────────────────────────────────────────────────
function setOutcomeRows(rows) {
  document.getElementById('outcomes-editor').innerHTML = '';
  if (!rows.length) rows = [{ name: '', toState: '', when: '', set: '', unset: '' }];
  rows.forEach(addOutcomeRow);
}

function addOutcomeRow(initial) {
  const states = (selectedWf?.states || []).map(s =>
    `<option value="${esc(s.name)}"${initial?.toState === s.name ? ' selected' : ''}>${esc(s.name)}</option>`).join('');

  const row = document.createElement('div');
  row.className = 'outcome-row';
  row.innerHTML = `
    <div class="outcome-row-header">
      <input class="form-control outcome-name" placeholder="outcome name (e.g. success)" value="${esc(initial?.name||'')}">
      <span style="color:hsl(var(--muted-foreground))">→</span>
      <select class="form-control outcome-to">${states}</select>
      <button class="outcome-row-remove" type="button" title="Remove" onclick="this.closest('.outcome-row').remove()">✕</button>
    </div>
    <div class="outcome-declarative-block">
      <div style="margin-bottom:8px">
        <label>When (predicate, optional)</label>
        <input class="form-control outcome-when" placeholder="amount &gt;= 1000   (leave empty for default)" value="${esc(initial?.when||'')}">
      </div>
      <div class="outcome-row-grid">
        <div>
          <label>Set (key=value, one per line)</label>
          <textarea class="form-control outcome-set" rows="3" placeholder="status=approved&#10;approvedAt=${'$'}{now}">${esc(initial?.set||'')}</textarea>
        </div>
        <div>
          <label>Unset (comma-separated)</label>
          <input class="form-control outcome-unset" placeholder="reason, errorAt" value="${esc(initial?.unset||'')}">
        </div>
      </div>
    </div>
  `;
  document.getElementById('outcomes-editor').appendChild(row);
  // Restore selection if not in option list (e.g. state list changed).
  if (initial?.toState) row.querySelector('.outcome-to').value = initial.toState;
  onEventTypeChange();
}

function readOutcomeRows() {
  return Array.from(document.querySelectorAll('#outcomes-editor .outcome-row')).map(row => {
    const name    = row.querySelector('.outcome-name').value.trim();
    const toState = row.querySelector('.outcome-to').value;
    const when    = row.querySelector('.outcome-when')?.value.trim() || '';
    const setText = row.querySelector('.outcome-set')?.value || '';
    const unsetText = row.querySelector('.outcome-unset')?.value || '';

    const setMap = {};
    setText.split('\n').filter(Boolean).forEach(l => {
      const [k, ...rest] = l.split('=');
      if (k?.trim()) setMap[k.trim()] = rest.join('=').trim();
    });
    const unsetArr = unsetText.split(',').map(s=>s.trim()).filter(Boolean);

    return {
      name,
      toState,
      when:  when || null,
      set:   Object.keys(setMap).length ? setMap : null,
      unset: unsetArr.length            ? unsetArr : null,
    };
  });
}

async function saveEvent() {
  if (!selectedWf) return;
  const name = document.getElementById('ev-name').value.trim().toUpperCase();
  if (!name) { toast('Event name is required', 'error'); return; }
  const fromState = document.getElementById('ev-from').value;
  if (!fromState) { toast('From State is required', 'error'); return; }

  const type = document.getElementById('ev-type').value;
  const script = (type === 'JAVASCRIPT' || type === 'PYTHON')
    ? document.getElementById('ev-script').value.trim() : null;
  if ((type === 'JAVASCRIPT' || type === 'PYTHON') && !script) {
    toast('Script body is required', 'error'); return;
  }

  const outcomes = readOutcomeRows();
  if (!outcomes.length) { toast('At least one outcome is required', 'error'); return; }
  for (const o of outcomes) {
    if (!o.name)    { toast('Every outcome needs a name', 'error'); return; }
    if (!o.toState) { toast(`Outcome "${o.name}" needs a target state`, 'error'); return; }
  }
  // For declarative, drop script; for script, drop per-outcome set/unset/when (they're ignored).
  if (type === 'DECLARATIVE') {
    // keep when/set/unset as-is
  } else {
    outcomes.forEach(o => { o.when = null; o.set = null; o.unset = null; });
  }

  const editName = document.getElementById('ev-name').dataset.editName;
  const wf = deepCopy(selectedWf);
  const newEvent = {
    name,
    description: document.getElementById('ev-desc').value.trim() || null,
    fromState,
    trigger: document.getElementById('ev-trigger').value,
    type,
    script,
    outcomes,
  };

  if (editName) {
    const idx = wf.events.findIndex(e => e.name === editName);
    if (idx >= 0) wf.events[idx] = newEvent; else wf.events.push(newEvent);
  } else {
    if (wf.events.some(e => e.name === name)) { toast(`Event "${name}" already exists`, 'error'); return; }
    wf.events.push(newEvent);
  }
  await persistWorkflow(wf, 'modal-event');
}

async function deleteEvent(eventName) {
  if (!selectedWf || !confirm(`Delete event "${eventName}"?`)) return;
  const wf = deepCopy(selectedWf);
  wf.events = wf.events.filter(e => e.name !== eventName);
  selectedItem = null;
  await persistWorkflow(wf);
}

// ─────────────────────────────────────────────────────────────────────────────
// Persist workflow + refresh all views
// ─────────────────────────────────────────────────────────────────────────────
async function persistWorkflow(wf, modalToClose) {
  try {
    selectedWf = await apiFetch(`/workflows/${wf.id}`, { method:'PUT', body: JSON.stringify(wf) });
    if (modalToClose) closeModal(modalToClose);
    toast('Workflow saved');
    await refreshWorkflows();
    await loadActiveView();
    renderPanelDefault();
    selectedItem = null;
  } catch (e) { toast(e.message, 'error'); }
}

// ─────────────────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────────────────
function openModal(id)  { document.getElementById(id).classList.remove('hidden'); }
function closeModal(id) { document.getElementById(id).classList.add('hidden'); }
function deepCopy(o)    { return JSON.parse(JSON.stringify(o)); }
function esc(s) {
  return String(s ?? '')
    .replace(/&/g,'&amp;').replace(/</g,'&lt;')
    .replace(/>/g,'&gt;').replace(/"/g,'&quot;');
}

function toast(msg, type = 'success') {
  const el = document.createElement('div');
  el.className = `toast ${type}`;
  el.textContent = msg;
  document.getElementById('toast-container').appendChild(el);
  setTimeout(() => el.remove(), 3500);
}

document.querySelectorAll('.modal-overlay').forEach(o =>
  o.addEventListener('click', e => { if (e.target === o) o.classList.add('hidden'); }));
