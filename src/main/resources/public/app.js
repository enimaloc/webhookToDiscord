'use strict';

const MONACO_BASE = 'https://cdn.jsdelivr.net/npm/monaco-editor@0.52.2/min/vs';
const MONACO_TIMEOUT_MS = 10000;
const app = document.getElementById('app');
const topbar = document.getElementById('topbar');
let navId = 0;
let currentEditor = null;

/* ---------- helpers ---------- */

function el(tag, attrs, ...children) {
  const node = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs || {})) {
    if (v === null || v === undefined || v === false) continue;
    if (k === 'class') node.className = v;
    else if (k.startsWith('on')) node.addEventListener(k.slice(2), v);
    else if (k === 'value') node.value = v;
    else if (v === true) node.setAttribute(k, '');
    else node.setAttribute(k, v);
  }
  for (const c of children.flat()) {
    if (c === null || c === undefined || c === false) continue;
    node.append(c instanceof Node ? c : document.createTextNode(String(c)));
  }
  return node;
}

class ApiError extends Error {
  constructor(status, message, retryAfter) {
    super(message);
    this.status = status;
    this.retryAfter = retryAfter;
  }
}

async function api(method, path, body, opts) {
  const init = { method, headers: {}, credentials: 'same-origin' };
  if (method !== 'GET') {
    init.headers['Content-Type'] = 'application/json';
    init.body = JSON.stringify(body === undefined ? {} : body);
  }
  let res;
  try {
    res = await fetch(path, init);
  } catch (e) {
    throw new ApiError(0, 'Serveur injoignable');
  }
  if (res.status === 401 && !(opts && opts.noRedirect)) {
    location.hash = '#/login';
    throw new ApiError(401, 'Session expirée', null);
  }
  if (res.status === 204) return null;
  let data = null;
  try { data = await res.json(); } catch (e) { /* empty or non-JSON body */ }
  if (!res.ok) {
    throw new ApiError(res.status, (data && data.error) || ('Erreur ' + res.status), res.headers.get('Retry-After'));
  }
  return data;
}

function errText(e) {
  return e instanceof ApiError ? e.message : String(e);
}

/** Replay error text; 409 means the request is still pending and owned by the processor. */
function replayErrText(e) {
  if (e instanceof ApiError && e.status === 409) {
    return 'Requête en cours de traitement, réessayez quand elle sera terminée (' + e.message + ').';
  }
  return errText(e);
}

function fmtDate(iso) {
  if (!iso) return '-';
  const d = new Date(iso);
  return isNaN(d) ? iso : d.toLocaleString('fr-FR');
}

function pretty(text) {
  if (text === null || text === undefined) return '';
  if (typeof text !== 'string') return JSON.stringify(text, null, 2);
  try { return JSON.stringify(JSON.parse(text), null, 2); } catch (e) { return text; }
}

function badge(status) {
  return el('span', { class: 'badge s-' + status }, status);
}

function setView(...nodes) {
  if (currentEditor) { currentEditor.dispose(); currentEditor = null; }
  app.replaceChildren(...nodes);
}

function copyText(text, button) {
  const done = () => {
    const old = button.textContent;
    button.textContent = 'Copié';
    setTimeout(() => { button.textContent = old; }, 1500);
  };
  if (navigator.clipboard && navigator.clipboard.writeText) {
    navigator.clipboard.writeText(text).then(done, () => fallbackCopy(text, done));
  } else {
    fallbackCopy(text, done);
  }
}

function fallbackCopy(text, done) {
  const ta = el('textarea', { style: 'position:fixed;opacity:0' });
  ta.value = text;
  document.body.append(ta);
  ta.select();
  try { document.execCommand('copy'); done(); } catch (e) { /* ignore */ }
  ta.remove();
}

/* ---------- Monaco ---------- */

let monacoPromise = null;

function loadMonaco() {
  if (monacoPromise) return monacoPromise;
  if (window.monaco && window.monaco.editor) return Promise.resolve(window.monaco);
  const stale = document.getElementById('monaco-loader');
  if (stale) stale.remove();
  monacoPromise = new Promise((resolve, reject) => {
    // a stalled CDN must not block the page: fall back to the plain editor
    const timer = setTimeout(() => reject(new Error('Monaco : délai dépassé')), MONACO_TIMEOUT_MS);
    const fail = (e) => { clearTimeout(timer); reject(e); };
    window.MonacoEnvironment = {
      getWorkerUrl() {
        const src = "self.MonacoEnvironment={baseUrl:'" + MONACO_BASE + "/../'};importScripts('" +
          MONACO_BASE + "/base/worker/workerMain.js');";
        return 'data:text/javascript;charset=utf-8,' + encodeURIComponent(src);
      }
    };
    const s = document.createElement('script');
    s.id = 'monaco-loader';
    s.src = MONACO_BASE + '/loader.js';
    s.onerror = () => fail(new Error('Monaco indisponible'));
    s.onload = () => {
      try {
        window.require.config({ paths: { vs: MONACO_BASE } });
        window.require(['vs/editor/editor.main'], () => { clearTimeout(timer); resolve(window.monaco); }, fail);
      } catch (e) { fail(e); }
    };
    document.head.append(s);
  });
  monacoPromise.catch(() => { monacoPromise = null; });
  return monacoPromise;
}

/** Returns {getValue, dispose}; falls back to a plain textarea when Monaco cannot load. */
async function mountEditor(container, value) {
  try {
    const monaco = await loadMonaco();
    const dark = window.matchMedia('(prefers-color-scheme: dark)').matches;
    const editor = monaco.editor.create(container, {
      value, language: 'javascript', theme: dark ? 'vs-dark' : 'vs',
      automaticLayout: true, minimap: { enabled: false }, scrollBeyondLastLine: false
    });
    return { getValue: () => editor.getValue(), dispose: () => editor.dispose() };
  } catch (e) {
    const ta = el('textarea', { rows: 20, spellcheck: 'false', style: 'font-family:monospace;height:100%' });
    ta.value = value;
    container.replaceChildren(ta);
    container.prepend(el('p', { class: 'error small' }, 'Éditeur Monaco indisponible, éditeur simple utilisé.'));
    return { getValue: () => ta.value, dispose: () => {} };
  }
}

/* ---------- login ---------- */

function renderLogin() {
  topbar.hidden = true;
  const msg = el('div', { class: 'error', role: 'alert' });
  const pwd = el('input', { id: 'pwd', type: 'password', autocomplete: 'current-password', required: true });
  const submit = el('button', { type: 'submit' }, 'Se connecter');
  const form = el('form', { class: 'card login', onsubmit: async (ev) => {
    ev.preventDefault();
    msg.textContent = '';
    submit.disabled = true;
    try {
      await api('POST', '/api/login', { password: pwd.value }, { noRedirect: true });
      location.hash = '#/';
    } catch (e) {
      if (e.status === 401) msg.textContent = 'Mot de passe incorrect.';
      else if (e.status === 429) {
        msg.textContent = 'Trop de tentatives, réessayez' + (e.retryAfter ? ' dans ' + e.retryAfter + ' s' : ' plus tard') + '.';
      } else msg.textContent = errText(e);
    } finally {
      submit.disabled = false;
    }
  } },
  el('h1', {}, 'Connexion'),
  el('label', { for: 'pwd' }, 'Mot de passe'),
  pwd, msg, el('div', { class: 'row', style: 'margin-top:12px' }, submit));
  setView(form);
  pwd.focus();
}

/* ---------- list ---------- */

async function renderList(id) {
  const endpoints = await api('GET', '/api/endpoints');
  if (id !== navId) return;
  topbar.hidden = false;

  const nameInput = el('input', { type: 'text', placeholder: 'Nom de la nouvelle entrée', maxlength: 200 });
  const createMsg = el('div', { class: 'error', role: 'alert' });
  const newBtn = el('button', { type: 'button' }, 'Nouvelle entrée');
  const createForm = el('form', { class: 'row', hidden: true, onsubmit: async (ev) => {
    ev.preventDefault();
    createMsg.textContent = '';
    try {
      const created = await api('POST', '/api/endpoints', { name: nameInput.value });
      location.hash = '#/endpoints/' + encodeURIComponent(created.id);
    } catch (e) { createMsg.textContent = errText(e); }
  } },
  el('div', { class: 'grow' }, nameInput),
  el('button', { type: 'submit' }, 'Créer'),
  el('button', { type: 'button', class: 'secondary', onclick: () => { createForm.hidden = true; newBtn.hidden = false; } }, 'Annuler'));
  newBtn.addEventListener('click', () => {
    createForm.hidden = false; newBtn.hidden = true; nameInput.focus();
  });

  let body;
  if (endpoints.length === 0) {
    body = el('p', { class: 'muted' }, 'Aucune entrée pour le moment.');
  } else {
    body = el('table', {},
      el('thead', {}, el('tr', {}, ['Nom', 'URL', 'Actif', 'Dernière réception', ''].map((h) => el('th', {}, h)))),
      el('tbody', {}, endpoints.map((e) => {
        const copy = el('button', { type: 'button', class: 'secondary', onclick: () => copyText(e.url, copy) }, 'Copier');
        return el('tr', {},
          el('td', {}, el('a', { href: '#/endpoints/' + encodeURIComponent(e.id) }, e.name)),
          el('td', {}, el('code', {}, e.url), ' ', copy),
          el('td', {}, el('span', { class: e.enabled ? 'on' : 'off' }, e.enabled ? 'Oui' : 'Non')),
          el('td', {}, fmtDate(e.lastReceived)),
          el('td', {}, el('a', { href: '#/endpoints/' + encodeURIComponent(e.id) + '/history' }, 'Historique')));
      })));
  }
  setView(
    el('div', { class: 'row between' }, el('h1', {}, 'Entrées'), newBtn),
    el('div', { class: 'card', style: 'overflow-x:auto' }, createForm, createMsg, body));
}

/* ---------- edit ---------- */

const AUTH_TYPES = [
  ['none', 'Aucune'], ['secretheader', 'En-tête secret'], ['bearer', 'Bearer token'],
  ['basic', 'Basic (utilisateur / mot de passe)'], ['hmac', 'Signature HMAC']
];

/** Field specs per auth type: [key, label, secret?]. */
const AUTH_FIELDS = {
  none: [],
  secretheader: [['header', 'Nom de l\'en-tête'], ['value', 'Valeur', true]],
  bearer: [['token', 'Token', true]],
  basic: [['username', 'Utilisateur'], ['password', 'Mot de passe', true]],
  hmac: [['header', 'Nom de l\'en-tête'], ['secret', 'Secret', true], ['algorithm', 'Algorithme (ex. HmacSHA256)'],
    ['prefix', 'Préfixe (ex. sha256=)'], ['encoding', 'Encodage']]
};

async function renderEdit(id, endpointId) {
  const ep = await api('GET', '/api/endpoints/' + encodeURIComponent(endpointId));
  if (id !== navId) return;
  topbar.hidden = false;
  // the stored config (secrets are always null); updated after each save
  let stored = ep.auth || { type: 'none' };
  const storedType = () => String(stored.type || 'none').toLowerCase();

  const name = el('input', { type: 'text', value: ep.name, maxlength: 200 });
  const enabled = el('input', { type: 'checkbox' });
  enabled.checked = ep.enabled;
  const typeSel = el('select', {}, AUTH_TYPES.map(([v, l]) => el('option', { value: v }, l)));
  typeSel.value = AUTH_TYPES.some(([v]) => v === storedType()) ? storedType() : 'none';
  const authBox = el('div', { class: 'grid2' });
  let authInputs = {};

  function renderAuthFields() {
    authInputs = {};
    const type = typeSel.value;
    const same = type === storedType();
    authBox.replaceChildren(...AUTH_FIELDS[type].map(([key, label, secret]) => {
      let input;
      if (key === 'encoding') {
        input = el('select', {}, ['HEX', 'BASE64'].map((v) => el('option', { value: v }, v)));
        input.value = same && stored.encoding ? stored.encoding : 'HEX';
      } else {
        input = el('input', {
          type: secret ? 'password' : 'text', autocomplete: 'off',
          placeholder: secret ? (same ? 'inchangé' : 'requis') : null
        });
        if (!secret && same && stored[key] != null) input.value = stored[key];
        if (key === 'algorithm' && !input.value) input.value = 'HmacSHA256';
      }
      authInputs[key] = input;
      return el('div', {}, el('label', {}, label), input);
    }));
  }
  typeSel.addEventListener('change', renderAuthFields);
  renderAuthFields();

  function authPayload() {
    const out = { type: typeSel.value };
    for (const [key, label, secret] of AUTH_FIELDS[typeSel.value]) {
      const value = authInputs[key].value;
      out[key] = secret && value === '' ? null : value;
    }
    return out;
  }

  const saveMsg = el('div', { role: 'status' });
  // enabled once the editor (Monaco or textarea fallback) is mounted
  const saveBtn = el('button', { type: 'button', disabled: true }, 'Enregistrer');
  const editorBox = el('div', { id: 'editor' });
  let editor = null;

  saveBtn.addEventListener('click', async () => {
    if (!editor) return;
    saveMsg.className = '';
    saveMsg.textContent = '';
    saveBtn.disabled = true;
    try {
      const saved = await api('PUT', '/api/endpoints/' + encodeURIComponent(endpointId), {
        name: name.value, enabled: enabled.checked, script: editor.getValue(), auth: authPayload()
      });
      stored = saved.auth || { type: 'none' };
      renderAuthFields(); // clears typed secrets, back to "inchangé"
      saveMsg.className = 'notice';
      saveMsg.textContent = 'Enregistré.';
    } catch (e) {
      saveMsg.className = 'error';
      saveMsg.textContent = errText(e);
    } finally {
      saveBtn.disabled = false;
    }
  });

  // delete with inline confirmation
  const delMsg = el('span', { class: 'error' });
  const delBtn = el('button', { type: 'button', class: 'secondary' }, 'Supprimer');
  const delConfirm = el('span', { hidden: true, class: 'row' },
    el('span', { class: 'error' }, 'Supprimer définitivement cette entrée et son historique ?'),
    el('button', { type: 'button', class: 'danger', onclick: async () => {
      try {
        await api('DELETE', '/api/endpoints/' + encodeURIComponent(endpointId));
        location.hash = '#/';
      } catch (e) { delMsg.textContent = errText(e); }
    } }, 'Oui, supprimer'),
    el('button', { type: 'button', class: 'secondary', onclick: () => { delConfirm.hidden = true; delBtn.hidden = false; } }, 'Annuler'));
  delBtn.addEventListener('click', () => { delBtn.hidden = true; delConfirm.hidden = false; });

  const testPanel = el('div', { class: 'card' }, el('h2', {}, 'Tester'));

  setView(
    el('p', {}, el('a', { href: '#/' }, '< Entrées'), ' · ',
      el('a', { href: '#/endpoints/' + encodeURIComponent(endpointId) + '/history' }, 'Historique')),
    el('h1', {}, ep.name),
    el('div', { class: 'card' },
      el('label', {}, 'Nom'), name,
      el('p', { class: 'small muted' }, 'URL : ', el('code', {}, ep.url)),
      el('label', { class: 'inline' }, enabled, 'Actif'),
      el('label', {}, 'Authentification'), typeSel, authBox,
      el('label', {}, 'Script'), editorBox,
      el('div', { class: 'row', style: 'margin-top:12px' }, saveBtn, delBtn, delConfirm, delMsg),
      saveMsg),
    testPanel);

  editor = await mountEditor(editorBox, ep.script || '');
  if (id !== navId) { editor.dispose(); return; }
  currentEditor = editor;
  saveBtn.disabled = false;

  buildTestPanel(testPanel, endpointId, () => editor.getValue());
}

async function buildTestPanel(panel, endpointId, getScript) {
  let requests = [];
  try {
    requests = await api('GET', '/api/endpoints/' + encodeURIComponent(endpointId) + '/requests?size=20');
  } catch (e) {
    panel.append(el('div', { class: 'error' }, errText(e)));
    return;
  }
  if (requests.length === 0) {
    panel.append(el('p', { class: 'muted' }, 'Aucune requête reçue : envoyez un webhook pour pouvoir tester le script.'));
    return;
  }
  const statusOf = new Map(requests.map((r) => [String(r.id), r.status]));
  const sel = el('select', {}, requests.map((r) =>
    el('option', { value: r.id }, '#' + r.id + ' - ' + fmtDate(r.receivedAt) + ' - ' + r.method + ' - ' + r.status)));
  const out = el('div', {});
  const previewBtn = el('button', { type: 'button' }, 'Aperçu');
  const sendBtn = el('button', { type: 'button', class: 'secondary' }, 'Envoyer pour de vrai');
  const yesBtn = el('button', { type: 'button', class: 'danger' }, 'Oui, envoyer');
  const pendingNote = el('span', { class: 'muted small', hidden: true },
    'Requête en attente de traitement : envoi réel indisponible.');
  const confirmBox = el('span', { hidden: true, class: 'row' },
    el('span', { class: 'error' }, 'Envoyer réellement les messages sur Discord (script en cours d\'édition) ?'),
    yesBtn,
    el('button', { type: 'button', class: 'secondary', onclick: () => { confirmBox.hidden = true; sendBtn.hidden = false; } }, 'Annuler'));
  let running = false;

  // a pending request is delivered by the processor: sending it from here would deliver it twice
  function refreshButtons() {
    const pending = statusOf.get(String(sel.value)) === 'pending';
    previewBtn.disabled = running;
    sendBtn.disabled = running || pending;
    yesBtn.disabled = running || pending;
    pendingNote.hidden = !pending;
    if (pending) { confirmBox.hidden = true; sendBtn.hidden = false; }
  }
  sel.addEventListener('change', refreshButtons);

  async function run(mode) {
    if (running) return;
    running = true;
    refreshButtons();
    out.replaceChildren(el('p', { class: 'muted' }, 'Exécution…'));
    try {
      const res = await api('POST', '/api/requests/' + encodeURIComponent(sel.value) + '/replay',
        { mode, script: getScript() });
      out.replaceChildren(renderResult(res));
    } catch (e) {
      out.replaceChildren(el('div', { class: 'error' }, replayErrText(e)));
    } finally {
      running = false;
      refreshButtons();
    }
  }
  previewBtn.addEventListener('click', () => run('preview'));
  sendBtn.addEventListener('click', () => { sendBtn.hidden = true; confirmBox.hidden = false; });
  yesBtn.addEventListener('click', () => {
    confirmBox.hidden = true; sendBtn.hidden = false; run('send');
  });
  refreshButtons();
  panel.append(
    el('label', {}, 'Requête reçue'), sel,
    el('div', { class: 'row', style: 'margin-top:10px' }, previewBtn, sendBtn, confirmBox, pendingNote), out);
}

/** Renders a replay response ({status, error, logs, messages:[{url,message}], sends}). */
function renderResult(res) {
  return el('div', {},
    el('p', {}, 'Statut : ', badge(res.status)),
    res.error ? el('div', { class: 'error' }, res.error) : null,
    renderLogs(res.logs),
    renderMessages(res.messages),
    renderSends(res.sends));
}

function renderLogs(logs) {
  if (!logs) return null;
  return el('div', {}, el('strong', {}, 'Logs'), el('pre', {}, logs));
}

function renderMessages(messages) {
  if (!messages || (Array.isArray(messages) && messages.length === 0)) {
    return el('p', { class: 'muted small' }, 'Aucun message produit.');
  }
  const list = Array.isArray(messages) ? messages : [messages];
  return el('div', {}, el('strong', {}, 'Messages (' + list.length + ')'),
    list.map((m) => el('div', {},
      m && m.url ? el('div', { class: 'small muted' }, m.url) : null,
      el('pre', {}, JSON.stringify(m && m.message !== undefined ? m.message : m, null, 2)))));
}

function renderSends(sends) {
  if (!sends || sends.length === 0) return null;
  return el('div', {}, el('strong', {}, 'Envois'),
    el('table', {},
      el('thead', {}, el('tr', {}, ['URL', 'Statut HTTP', 'Tentatives', 'Résultat', 'Réponse'].map((h) => el('th', {}, h)))),
      el('tbody', {}, sends.map((s) => el('tr', {},
        el('td', {}, el('code', {}, s.url)),
        el('td', {}, s.status),
        el('td', {}, s.attempts),
        el('td', {}, el('span', { class: s.ok ? 'on' : 'error' }, s.ok ? 'OK' : 'Échec')),
        el('td', {}, s.response ? el('code', {}, String(s.response).slice(0, 300)) : '-'))))));
}

/* ---------- history ---------- */

const PAGE_SIZE = 50;

async function renderHistory(id, endpointId, page) {
  const base = '/api/endpoints/' + encodeURIComponent(endpointId);
  const [ep, rows] = await Promise.all([
    api('GET', base),
    api('GET', base + '/requests?page=' + page + '&size=' + PAGE_SIZE)
  ]);
  if (id !== navId) return;
  topbar.hidden = false;
  const link = (p) => '#/endpoints/' + encodeURIComponent(endpointId) + '/history?page=' + p;
  const prev = el('a', { href: page > 0 ? link(page - 1) : null, class: page > 0 ? '' : 'muted' }, '< Précédent');
  const hasNext = rows.length === PAGE_SIZE;
  const next = el('a', { href: hasNext ? link(page + 1) : null, class: hasNext ? '' : 'muted' }, 'Suivant >');
  setView(
    el('p', {}, el('a', { href: '#/' }, '< Entrées'), ' · ',
      el('a', { href: '#/endpoints/' + encodeURIComponent(endpointId) }, 'Modifier')),
    el('h1', {}, 'Historique : ' + ep.name),
    el('div', { class: 'card', style: 'overflow-x:auto' },
      rows.length === 0 ? el('p', { class: 'muted' }, 'Aucune requête sur cette page.') :
        el('table', {},
          el('thead', {}, el('tr', {}, ['#', 'Reçue le', 'Méthode', 'Statut', ''].map((h) => el('th', {}, h)))),
          el('tbody', {}, rows.map((r) => el('tr', {},
            el('td', {}, r.id), el('td', {}, fmtDate(r.receivedAt)), el('td', {}, r.method),
            el('td', {}, badge(r.status)),
            el('td', {}, el('a', { href: '#/requests/' + r.id }, 'Détail')))))),
      el('div', { class: 'row between', style: 'margin-top:12px' }, prev, el('span', { class: 'muted' }, 'Page ' + (page + 1)), next)));
}

/* ---------- request detail ---------- */

async function renderRequest(id, rid) {
  const r = await api('GET', '/api/requests/' + encodeURIComponent(rid));
  if (id !== navId) return;
  topbar.hidden = false;

  const out = el('div', {});
  // a pending request is delivered by the processor: replaying it would deliver it twice
  const pending = r.status === 'pending';
  const previewBtn = el('button', { type: 'button' }, 'Aperçu');
  const replayBtn = el('button', { type: 'button', class: 'secondary', hidden: pending }, 'Rejouer');
  const yesBtn = el('button', { type: 'button', class: 'danger' }, 'Oui, rejouer');
  const pendingNote = pending
    ? el('span', { class: 'muted small' }, 'Requête en attente de traitement : rejeu réel indisponible.') : null;
  const confirmBox = el('span', { hidden: true, class: 'row' },
    el('span', { class: 'error' }, 'Rejouer et envoyer réellement les messages sur Discord ?'),
    yesBtn,
    el('button', { type: 'button', class: 'secondary', onclick: () => { confirmBox.hidden = true; replayBtn.hidden = false; } }, 'Annuler'));
  let running = false;

  function setRunning(value) {
    running = value;
    previewBtn.disabled = value;
    replayBtn.disabled = value;
    yesBtn.disabled = value;
  }

  async function run(mode) {
    if (running) return;
    setRunning(true);
    out.replaceChildren(el('p', { class: 'muted' }, 'Exécution…'));
    try {
      const res = await api('POST', '/api/requests/' + encodeURIComponent(rid) + '/replay', { mode });
      out.replaceChildren(renderResult(res));
    } catch (e) {
      out.replaceChildren(el('div', { class: 'error' }, replayErrText(e)));
    } finally {
      setRunning(false);
    }
  }
  previewBtn.addEventListener('click', () => run('preview'));
  replayBtn.addEventListener('click', () => { replayBtn.hidden = true; confirmBox.hidden = false; });
  yesBtn.addEventListener('click', () => {
    confirmBox.hidden = true; replayBtn.hidden = false; run('send');
  });

  const headers = Object.entries(r.headers || {});
  setView(
    el('p', {}, el('a', { href: '#/endpoints/' + encodeURIComponent(r.endpointId) + '/history' }, '< Historique')),
    el('h1', {}, 'Requête #' + r.id),
    el('div', { class: 'card' },
      el('p', {}, r.method, ' · ', fmtDate(r.receivedAt), ' · ', badge(r.status)),
      el('h2', {}, 'En-têtes'),
      headers.length === 0 ? el('p', { class: 'muted' }, 'Aucun.') :
        el('table', {}, el('tbody', {}, headers.map(([k, v]) => el('tr', {},
          el('td', {}, el('code', {}, k)), el('td', {}, Array.isArray(v) ? v.join(', ') : String(v)))))),
      el('h2', { style: 'margin-top:14px' }, 'Query'),
      r.query ? el('pre', {}, r.query) : el('p', { class: 'muted' }, 'Aucune.'),
      el('h2', {}, 'Corps'),
      r.body ? el('pre', {}, pretty(r.body)) : el('p', { class: 'muted' }, 'Vide.')),
    el('div', { class: 'card' },
      el('h2', {}, 'Rejouer'),
      el('div', { class: 'row' }, previewBtn, replayBtn, confirmBox, pendingNote), out),
    el('div', { class: 'card' },
      el('h2', {}, 'Exécutions (' + r.executions.length + ')'),
      r.executions.length === 0 ? el('p', { class: 'muted' }, 'Aucune exécution.') :
        r.executions.map((x) => el('div', { style: 'border-top:1px solid var(--border);padding-top:8px;margin-top:8px' },
          el('p', {}, '#' + x.id + ' · ', fmtDate(x.startedAt), ' · mode ', el('strong', {}, x.mode), ' · ', badge(x.status)),
          x.error ? el('div', { class: 'error' }, x.error) : null,
          renderLogs(x.logs), renderMessages(x.messages), renderSends(x.sends)))));
}

/* ---------- router ---------- */

async function route() {
  const id = ++navId;
  const hash = location.hash.replace(/^#/, '') || '/';
  const [path, query = ''] = hash.split('?');
  const params = new URLSearchParams(query);
  let m;
  try {
    if (path === '/login') renderLogin();
    else if (path === '/') await renderList(id);
    else if ((m = path.match(/^\/endpoints\/([^/]+)\/history$/))) {
      await renderHistory(id, decodeURIComponent(m[1]), Math.max(0, parseInt(params.get('page') || '0', 10) || 0));
    } else if ((m = path.match(/^\/endpoints\/([^/]+)$/))) await renderEdit(id, decodeURIComponent(m[1]));
    else if ((m = path.match(/^\/requests\/([^/]+)$/))) await renderRequest(id, decodeURIComponent(m[1]));
    else location.hash = '#/';
  } catch (e) {
    if (e instanceof ApiError && e.status === 401) return; // redirected to login
    if (id !== navId) return;
    topbar.hidden = false;
    setView(el('p', {}, el('a', { href: '#/' }, '< Entrées')), el('div', { class: 'card error' }, errText(e)));
  }
}

document.getElementById('logout').addEventListener('click', async () => {
  try { await api('POST', '/api/logout', {}, { noRedirect: true }); } catch (e) { /* ignore */ }
  location.hash = '#/login';
});
window.addEventListener('hashchange', route);
route();
