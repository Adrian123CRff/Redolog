'use strict';
const $ = s => document.querySelector(s);
const icons = () => window.lucide?.createIcons();
const esc = v => String(v ?? '').replace(/[&<>"']/g, c => ({'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'}[c]));
const icon = n => `<i data-lucide="${n}"></i>`;
const button = (action, id, title, symbol, extra = '') => `<button class="icon-button ${extra}" data-action="${action}" data-id="${esc(id)}" title="${title}" aria-label="${title}">${icon(symbol)}</button>`;
const ZONE = 'America/Costa_Rica';
const HOUR = 3600e3;

// Estados de ejecucion (seccion 10): color de estado + forma, nunca solo color.
const STATUS = {
  EXITOSO: {label: 'Exitoso', cls: 'good', color: '#0ca30c', shape: 'circle'},
  CON_ADVERTENCIAS: {label: 'Con advertencias', cls: 'warn', color: '#fab219', shape: 'triangle'},
  FALLIDO: {label: 'Fallido', cls: 'bad', color: '#d03b3b', shape: 'square'},
  EJECUTANDO: {label: 'Ejecutando', cls: 'blue', color: '#3b6fb6', shape: 'circle'},
  INCIERTO: {label: 'Incierto', cls: 'serious', color: '#ec835a', shape: 'diamond'},
  OMITIDO: {label: 'Omitido', cls: 'serious', color: '#ec835a', shape: 'diamond'}
};
const LEVEL = {
  ALERTA: {label: 'Alerta', cls: 'bad', icon: 'octagon-alert'},
  ERROR: {label: 'Error', cls: 'bad', icon: 'circle-x'},
  ADVERTENCIA: {label: 'Advertencia', cls: 'warn', icon: 'triangle-alert'},
  RECOMENDACION: {label: 'Recomendación', cls: 'rec', icon: 'lightbulb'},
  INFORMATIVA: {label: 'Informativa', cls: 'info', icon: 'info'}
};
const METHOD_HINT = {
  FULL: 'Copia todos los bloques usados del alcance. Restauración directa, pero no sirve como base de incrementales.',
  LEVEL0: 'Mismo contenido que un completo, pero inicia la cadena incremental: los niveles 1 parten de él.',
  LEVEL1: 'Solo bloques cambiados desde el último nivel 0 o 1. Rápido y pequeño; al recuperar se aplican todos los niveles 1 desde el nivel 0.',
  CUMULATIVE: 'Bloques cambiados desde el último nivel 0. Crece cada día, pero al recuperar se aplica solo el último.'
};
const LEVEL_TEXT = {FULL: 'Sin nivel (completo)', LEVEL0: '0', LEVEL1: '1 diferencial', CUMULATIVE: '1 acumulativo'};
const PRIORITY_HINT = {
  ALTA: 'Alta: información crítica para la continuidad de la operación. Objetivo: un respaldo correcto cada 24 h como máximo.',
  MEDIA: 'Media: información importante que admite mayores tiempos de recuperación. Objetivo: 72 h como máximo.',
  BAJA: 'Baja: información de menor impacto o que puede reconstruirse. Objetivo: 7 días (168 h) como máximo.'
};
const DAYS = [['MON', 'Lun'], ['TUE', 'Mar'], ['WED', 'Mié'], ['THU', 'Jue'], ['FRI', 'Vie'], ['SAT', 'Sáb'], ['SUN', 'Dom']];
const EVENT_LABEL = {ESTRATEGIA_CREADA: 'Estrategia creada', ESTRATEGIA_EDITADA: 'Estrategia editada', ESTRATEGIA_ELIMINADA: 'Estrategia eliminada', SCRIPT_APROBADO: 'Script aprobado', RECOMENDACION_APLICADA: 'Recomendación aplicada', BASE_LIBERADA: 'Base liberada', BASE_COMPROBADA: 'Base comprobada'};

let state = {databases: [], statuses: {}, strategies: [], executions: [], alerts: [], upcoming: [], missed: [], events: [], active: {}};
let currentView = 'monitor', alertFilter = '', formTimes = [], formDays = new Set(), refreshing = false, reviewId = null, previewTimer = null;
let detail = {id: null, data: null, mode: 'evidence'};

const fmt = (value, options) => value ? new Intl.DateTimeFormat('es-CR', {timeZone: ZONE, ...options}).format(new Date(value)) : '—';
const formatDate = value => fmt(value, {day: '2-digit', month: 'short', hour: '2-digit', minute: '2-digit', hourCycle: 'h23'});
const formatTime = value => fmt(value, {hour: '2-digit', minute: '2-digit', hourCycle: 'h23'});
const today = () => new Intl.DateTimeFormat('en-CA', {timeZone: ZONE}).format(new Date());
const hoursSince = value => (Date.now() - Date.parse(value)) / HOUR;
function ago(value) {
  const h = hoursSince(value);
  if (h < 1) return 'hace ' + Math.max(1, Math.round(h * 60)) + ' min';
  if (h < 48) return 'hace ' + Math.round(h) + ' h';
  return 'hace ' + Math.round(h / 24) + ' días';
}
function human(bytes) {
  if (bytes < 1048576) return Math.max(1, Math.round(bytes / 1024)) + ' KB';
  if (bytes < 1073741824) return (bytes / 1048576).toFixed(1) + ' MB';
  return (bytes / 1073741824).toFixed(1) + ' GB';
}
function duration(e) {
  if (!e.finishedAt) return 'En curso';
  const s = Math.max(0, Math.round((Date.parse(e.finishedAt) - Date.parse(e.startedAt)) / 1000));
  return s < 60 ? s + ' s' : Math.floor(s / 60) + ' min ' + s % 60 + ' s';
}
const view = id => state.strategies.find(v => v.strategy.id === id);
const dbName = id => state.databases.find(d => d.id === id)?.name || 'Base eliminada';
const statusBadge = status => { const s = STATUS[status] || {label: status, cls: ''}; return `<span class="badge ${s.cls}">${shapeIcon(status)}${esc(s.label)}</span>`; };
const levelBadge = level => { const l = LEVEL[level]; return `<span class="badge ${l.cls}">${icon(l.icon)}${l.label}</span>`; };
const badge = (text, cls = '') => `<span class="badge ${cls}">${esc(text)}</span>`;

function toast(message, error = false) {
  const t = $('#toast'); t.textContent = message; t.className = 'toast' + (error ? ' error' : '');
  clearTimeout(toast.timer); toast.timer = setTimeout(() => t.classList.add('hidden'), 7000);
}
async function api(path, body) {
  const r = await fetch('/api/' + path, body === undefined ? {} : {method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(body)});
  let data; try { data = await r.json(); } catch { throw Error('El servidor no responde.'); }
  if (!r.ok) throw Error(data.error || 'No se pudo completar la operación.');
  return data;
}
async function refresh() {
  if (refreshing) return; refreshing = true;
  try {
    state = await api('state'); render();
    $('#connection-dot').className = 'dot ok';
    if ($('#detail-dialog').open && detail.id && ['EJECUTANDO'].includes(detail.data?.execution.status)) await openExecution(detail.id, true);
  } catch (e) { $('#connection-text').textContent = 'Sin conexión'; $('#connection-dot').className = 'dot bad'; }
  finally { refreshing = false; }
}
function switchView(name) {
  currentView = name;
  document.querySelectorAll('.view').forEach(v => v.classList.toggle('hidden', v.id !== name + '-view'));
  document.querySelectorAll('.nav').forEach(n => n.classList.toggle('active', n.dataset.view === name));
  $('#view-title').textContent = {monitor: 'Monitor de respaldos', strategies: 'Estrategias de respaldo', executions: 'Historial de ejecuciones', databases: 'Bases de datos'}[name];
  $('#new-button').classList.toggle('hidden', name === 'executions');
  $('#new-button').innerHTML = icon('plus') + (name === 'databases' ? 'Registrar base' : 'Nueva estrategia');
  if (name === 'monitor') renderTimeline();
  icons();
}
function render() {
  renderMonitor(); renderStrategies(); renderExecutions(); renderDatabases();
  const selection = $('#database-filter').value;
  $('#database-filter').innerHTML = '<option value="">Todas las bases</option>' + state.databases.map(d => `<option value="${esc(d.id)}">${esc(d.name)}</option>`).join('');
  $('#database-filter').value = selection;
  $('#updated-at').textContent = 'Actualizado ' + formatDate(state.serverTime);
  $('#mode-banner').classList.toggle('hidden', state.mode !== 'SIMULACION');
  $('#connection-text').textContent = state.mode === 'SIMULACION' ? 'Simulación conectada' : 'Servidor conectado';
  icons();
}

// ---------------- MONITOR ----------------
function renderMonitor() {
  const all = state.strategies, operating = all.filter(v => v.scheduled).length;
  const pending = all.filter(v => !v.approved).length, inactive = all.filter(v => !v.strategy.enabled).length;
  $('#kpi-operating').innerHTML = `${operating}<span class="of"> / ${all.length}</span>`;
  $('#kpi-operating-note').textContent = `${pending} ${pending === 1 ? 'pendiente' : 'pendientes'} de aprobación · ${inactive} ${inactive === 1 ? 'inactiva' : 'inactivas'}`;

  const backups = state.executions.filter(e => e.operation === 'BACKUP');
  const last = backups.find(e => ['EXITOSO', 'CON_ADVERTENCIAS'].includes(e.status));
  $('#kpi-last').textContent = last ? ago(last.finishedAt) : 'Sin respaldos';
  $('#kpi-last-note').textContent = last ? `${last.strategyName} · ${formatDate(last.finishedAt)}` : 'Ningún respaldo correcto registrado';

  const week = backups.filter(e => e.finishedAt && hoursSince(e.startedAt) <= 168);
  const count = s => week.filter(e => e.status === s).length;
  const parts = [['EXITOSO', count('EXITOSO')], ['CON_ADVERTENCIAS', count('CON_ADVERTENCIAS')], ['FALLIDO', count('FALLIDO')], ['OMITIDO', count('OMITIDO') + count('INCIERTO')]];
  const total = parts.reduce((a, p) => a + p[1], 0);
  $('#kpi-results').innerHTML = `<div class="result-counts">${parts.slice(0, 3).map(([s, n]) => `<span title="${STATUS[s].label}">${shapeIcon(s)}<b>${n}</b></span>`).join('')}</div>`
    + `<div class="stack-bar" aria-hidden="true">${total ? parts.filter(p => p[1]).map(([s, n]) => `<span style="flex:${n};background:${STATUS[s].color}"></span>`).join('') : '<span class="empty-bar"></span>'}</div>`;
  $('#kpi-results-note').textContent = total ? `${total} respaldos · ${count('EXITOSO')} exitosos, ${count('CON_ADVERTENCIAS')} con advertencias, ${count('FALLIDO')} fallidos` : 'Sin respaldos en los últimos 7 días';

  const byLevel = l => state.alerts.filter(a => a.level === l).length;
  $('#kpi-alerts').textContent = byLevel('ALERTA');
  $('#kpi-alerts').classList.toggle('danger-text', byLevel('ALERTA') > 0);
  $('#kpi-alerts-note').textContent = `${byLevel('ADVERTENCIA')} advertencias · ${byLevel('RECOMENDACION')} recomendaciones`;

  renderAlerts(); renderTimeline(); renderHealth(); renderMiniDatabases(); renderEvents();
}

function renderAlerts() {
  const levels = ['ALERTA', 'ADVERTENCIA', 'RECOMENDACION', 'INFORMATIVA'];
  $('#alert-filters').innerHTML = [['', 'Todas', state.alerts.length], ...levels.map(l => [l, LEVEL[l].label, state.alerts.filter(a => a.level === l).length])]
    .map(([value, label, n]) => `<button class="chip ${alertFilter === value ? 'selected' : ''}" data-alert-filter="${value}" aria-pressed="${alertFilter === value}">${label} <b>${n}</b></button>`).join('');
  const rows = state.alerts.filter(a => !alertFilter || a.level === alertFilter);
  $('#alerts-list').innerHTML = rows.length ? rows.map(a => `<li class="alert-item ${LEVEL[a.level].cls}">
      <div class="alert-level">${levelBadge(a.level)}</div>
      <div class="alert-text"><strong>${esc(a.title)}</strong><p>${esc(a.detail)}</p></div>
      ${a.action ? `<button class="secondary small" data-alert-action="${esc(a.action)}" data-strategy="${esc(a.strategyId || '')}" data-db="${esc(a.databaseId || '')}">${esc(a.actionLabel)}</button>` : ''}
    </li>`).join('') : `<li class="empty">${icon('shield-check')}Sin condiciones en este nivel.</li>`;
}

function renderHealth() {
  $('#health-body').innerHTML = state.strategies.length ? state.strategies.map(v => {
    const s = v.strategy;
    const mine = state.executions.filter(e => e.strategyId === s.id && e.operation === 'BACKUP');
    const lastRun = mine[0], lastOk = mine.find(e => ['EXITOSO', 'CON_ADVERTENCIAS'].includes(e.status));
    const age = lastOk ? hoursSince(lastOk.finishedAt) : null, ratio = age === null ? 1 : Math.min(age / v.rpoHours, 1);
    const cls = age === null || age >= v.rpoHours ? 'bad' : age >= v.rpoHours * 0.75 ? 'warn' : 'good';
    const ageText = age === null ? 'Sin respaldo correcto' : (age < 1 ? Math.round(age * 60) + ' min' : Math.round(age) + ' h');
    return `<tr>
      <td><strong>${esc(s.name)}</strong><small>${esc(dbName(s.databaseId))} · prioridad ${s.priority.toLowerCase()}</small></td>
      <td>${lastRun ? statusBadge(lastRun.status) + `<small>${formatDate(lastRun.startedAt)}</small>` : '<small>Sin ejecuciones</small>'}</td>
      <td><div class="meter ${cls}" role="img" aria-label="${ageText} de ${v.rpoHours} h"><span style="width:${Math.max(ratio * 100, 3)}%"></span></div><small>${ageText} / objetivo ${v.rpoHours} h</small></td>
      <td><small>${v.nextRun ? formatDate(v.nextRun) : v.strategy.enabled ? 'No programada' : 'Inactiva'}</small></td>
      <td>${lifecycle(v)}</td>
    </tr>`;
  }).join('') : `<tr><td colspan="5" class="empty">${icon('layers')}Crea una estrategia para empezar.</td></tr>`;
}

function renderMiniDatabases() {
  $('#monitor-databases').innerHTML = state.databases.map(d => {
    const st = state.statuses[d.id];
    const mode = !st ? badge('Sin comprobar') : !st.reachable ? badge('Sin conexión', 'bad') : badge(st.logMode, st.logMode === 'ARCHIVELOG' ? 'good' : 'warn');
    const free = st?.freeKb?.['/opt/oracle/backup'];
    return `<div class="mini-db">${icon('database')}<div><strong>${esc(d.name)}</strong><small>${esc(d.container)}${st?.version ? ' · ' + esc(st.version) : ''}${free > 0 ? ' · ' + human(free * 1024) + ' libres' : ''}</small></div>${mode}</div>`;
  }).join('') || '<p class="muted">Sin bases registradas.</p>';
}

function renderEvents() {
  $('#events-list').innerHTML = state.events.slice(0, 8).map(e => `<li><time>${formatDate(e.at)}</time><span><b>${esc(EVENT_LABEL[e.type] || e.type)}</b> ${esc(e.detail)}</span></li>`).join('') || '<li class="muted">Sin acciones registradas.</li>';
}

// Linea de tiempo: 24 h atras y 24 h adelante, un carril por estrategia.
function shapePath(kind, cx, cy, r) {
  if (kind === 'triangle') return `<path d="M${cx} ${cy - r - 1.5}L${cx + r + 1.5} ${cy + r}H${cx - r - 1.5}Z"/>`;
  if (kind === 'square') return `<rect x="${cx - r + .5}" y="${cy - r + .5}" width="${2 * r - 1}" height="${2 * r - 1}" rx="1.5"/>`;
  if (kind === 'diamond') return `<path d="M${cx} ${cy - r - 1.5}L${cx + r + 1.5} ${cy}L${cx} ${cy + r + 1.5}L${cx - r - 1.5} ${cy}Z"/>`;
  return `<circle cx="${cx}" cy="${cy}" r="${r}"/>`;
}
function shapeIcon(status) {
  const s = STATUS[status]; if (!s) return '';
  return `<svg class="shape" viewBox="0 0 16 16" aria-hidden="true"><g fill="${s.color}" stroke="#fff" stroke-width="1.5">${shapePath(s.shape, 8, 8, 5)}</g></svg>`;
}
function renderTimeline() {
  const host = $('#timeline');
  $('#timeline-legend').innerHTML = ['EXITOSO', 'CON_ADVERTENCIAS', 'FALLIDO', 'OMITIDO'].map(s => `<span>${shapeIcon(s)}${s === 'OMITIDO' ? 'Omitido / incierto' : STATUS[s].label}</span>`).join('')
    + '<span><svg class="shape" viewBox="0 0 16 16" aria-hidden="true"><circle cx="8" cy="8" r="5" fill="none" stroke="#d03b3b" stroke-width="2" stroke-dasharray="2 2"/></svg>No ejecutado</span>'
    + '<span><svg class="shape" viewBox="0 0 16 16" aria-hidden="true"><circle cx="8" cy="8" r="5" fill="#fff" stroke="#898781" stroke-width="2"/></svg>Programado</span>';
  const lanes = state.strategies;
  if (!lanes.length) { host.innerHTML = `<p class="empty">${icon('calendar-clock')}Sin estrategias para mostrar.</p>`; icons(); return; }
  const W = Math.max(host.clientWidth || 640, 420), narrow = W < 560;
  const labelW = narrow ? 110 : 190, padR = 14, top = 28, laneH = 38, H = top + lanes.length * laneH + 6;
  const now = Date.now(), t0 = now - 24 * HOUR, t1 = now + 24 * HOUR;
  const x = t => labelW + (t - t0) / (t1 - t0) * (W - labelW - padR);
  const hourOf = t => Number(new Intl.DateTimeFormat('en-US', {timeZone: ZONE, hour: 'numeric', hourCycle: 'h23'}).format(new Date(t)));
  let svg = '';
  for (let t = Math.ceil(t0 / HOUR) * HOUR; t <= t1; t += HOUR) {
    if (hourOf(t) % 6) continue;
    const xx = x(t).toFixed(1);
    svg += `<line x1="${xx}" x2="${xx}" y1="${top - 6}" y2="${H}" class="grid"/><text x="${xx}" y="${top - 12}" class="tick" text-anchor="middle">${hourOf(t) === 0 ? fmt(t, {day: '2-digit', month: 'short'}) : String(hourOf(t)).padStart(2, '0') + ':00'}</text>`;
  }
  lanes.forEach((v, i) => {
    const s = v.strategy, cy = top + i * laneH + laneH / 2;
    const name = s.name.length > (narrow ? 13 : 24) ? s.name.slice(0, narrow ? 12 : 23) + '…' : s.name;
    svg += `<line x1="0" x2="${W}" y1="${top + (i + 1) * laneH}" y2="${top + (i + 1) * laneH}" class="lane"/>`;
    const sub = narrow ? s.priority.toLowerCase() : `${s.priority.toLowerCase()} · ${s.times.join(' ') || 'sin horario'}`;
    svg += `<text x="0" y="${cy - 3}" class="lane-label">${esc(name)}</text><text x="0" y="${cy + 11}" class="lane-sub">${esc(sub)}</text>`;
    svg += `<line x1="${labelW}" x2="${W - padR}" y1="${cy}" y2="${cy}" class="track"/>`;
    for (const u of state.upcoming.filter(u => u.strategyId === s.id))
      svg += mark(x(Date.parse(u.at)), cy, `<circle cx="${x(Date.parse(u.at)).toFixed(1)}" cy="${cy}" r="5" fill="#fff" stroke="#898781" stroke-width="2"/>`, `${s.name} · programado ${formatDate(u.at)}`);
    for (const m of state.missed.filter(m => m.strategyId === s.id))
      svg += mark(x(Date.parse(m.at)), cy, `<circle cx="${x(Date.parse(m.at)).toFixed(1)}" cy="${cy}" r="5" fill="#fff" stroke="#d03b3b" stroke-width="2" stroke-dasharray="2 2"/>`, `${s.name} · ${formatDate(m.at)} · no se ejecutó`);
    for (const e of state.executions.filter(e => e.strategyId === s.id && e.operation === 'BACKUP' && Date.parse(e.startedAt) >= t0)) {
      const st = STATUS[e.status] || STATUS.INCIERTO, cx = +x(Date.parse(e.startedAt)).toFixed(1);
      svg += mark(cx, cy, `<g fill="${st.color}" stroke="#fff" stroke-width="2" class="${e.status === 'EJECUTANDO' ? 'pulse' : ''}">${shapePath(st.shape, cx, cy, 6)}</g>`,
        `${s.name} · ${formatDate(e.startedAt)} · ${st.label} · ${duration(e)}`, e.id);
    }
  });
  const nx = x(now).toFixed(1);
  svg += `<line x1="${nx}" x2="${nx}" y1="${top - 6}" y2="${H}" class="now"/><text x="${nx}" y="${H + 14}" class="now-label" text-anchor="middle">Ahora ${formatTime(now)}</text>`;
  host.innerHTML = `<svg viewBox="0 0 ${W} ${H + 20}" width="${W}" height="${H + 20}" role="img" aria-label="Línea de tiempo de ejecuciones por estrategia. El detalle está en Ejecuciones.">${svg}</svg>`;
}
function mark(cx, cy, body, tip, executionId = '') {
  return `<g class="mark" tabindex="0" data-tip="${esc(tip)}" ${executionId ? `data-execution="${esc(executionId)}"` : ''}><circle cx="${(+cx).toFixed(1)}" cy="${cy}" r="12" class="hit"/>${body}</g>`;
}

// Ciclo de la estrategia (enunciado, seccion 15): configurada -> aprobada -> programada -> ejecutada -> verificada.
const CONFIG_ERRORS = ['TABLESPACE_INEXISTENTE', 'DATAFILE_INEXISTENTE', 'DESTINO_INEXISTENTE', 'ARCHIVELOG_SIN_MODO'];
const GLYPH = {done: '<path d="M4.5 8.2l2.3 2.3 4.7-4.9"/>', bad: '<path d="M5.5 5.5l5 5m0-5l-5 5"/>', warn: '<path d="M8 4.5v4.3m0 2.4v.1"/>', todo: ''};
function lifecycle(v) {
  const s = v.strategy, mine = state.executions.filter(e => e.strategyId === s.id);
  const lastRun = mine.find(e => e.operation === 'BACKUP' && e.status !== 'EJECUTANDO');
  const lastOk = mine.find(e => e.operation === 'BACKUP' && ['EXITOSO', 'CON_ADVERTENCIAS'].includes(e.status));
  const verified = lastOk && (lastOk.details.some(d => d.startsWith('Verificacion posterior correcta'))
    || mine.some(e => e.operation === 'VALIDATE' && e.status === 'EXITOSO' && e.startedAt >= lastOk.finishedAt));
  const configBad = state.alerts.some(a => a.strategyId === s.id && CONFIG_ERRORS.includes(a.code));
  const steps = [
    ['Configurada', configBad ? 'bad' : 'done', configBad ? 'Corregir la configuración' : ''],
    ['Aprobada', v.approved ? 'done' : 'todo', 'Revisar y aprobar el script'],
    ['Programada', v.scheduled ? 'done' : 'todo', !s.enabled ? 'Activar la estrategia' : !s.times.length ? 'Agregar horarios' : 'Aprobar para programar'],
    ['Ejecutada', !lastRun ? 'todo' : lastRun.status === 'FALLIDO' ? 'bad' : lastRun.status === 'CON_ADVERTENCIAS' ? 'warn' : ['OMITIDO', 'INCIERTO'].includes(lastRun.status) ? 'warn' : 'done',
      !lastRun ? 'Esperar o ejecutar el primer respaldo' : 'Revisar la última ejecución'],
    ['Verificada', verified ? 'done' : 'todo', 'Verificar con RESTORE … VALIDATE']
  ];
  const next = steps.find(st => st[1] !== 'done');
  const dots = steps.map(([label, st], i) => `${i ? `<i class="link ${steps[i - 1][1] === 'done' ? 'on' : ''}"></i>` : ''}<svg class="pdot ${st}" viewBox="0 0 16 16" role="img" aria-label="${label}: ${{done: 'hecho', bad: 'con error', warn: 'con advertencias', todo: 'pendiente'}[st]}"><title>${label}</title><circle cx="8" cy="8" r="7"/><g>${GLYPH[st]}</g></svg>`).join('');
  const action = next && {Configurada: 'edit', Aprobada: 'review', Programada: 'edit', Verificada: 'validate'}[next[0]];
  const text = next ? (action ? `<button class="link-button" data-action="${action}" data-id="${esc(s.id)}">${esc(next[2])}</button>` : esc(next[2])) : 'Ciclo completo: respaldo verificado';
  return `<div class="pipeline">${dots}</div><small>${next ? `Siguiente: ` : ''}${text}</small>`;
}

// Diagrama de recuperacion del paso "Como respaldar" (enunciado, seccion 5).
function renderChain() {
  const scope = radio('scope'), method = field('method').value, logs = field('archivelogs').checked;
  if (scope === 'COMPONENTS') { $('#chain').innerHTML = ''; return; }
  const days = ['Dom', 'Lun', 'Mar', 'Mié', 'Jue', 'Vie'];
  const incremental = method === 'LEVEL1' || method === 'CUMULATIVE';
  const cells = days.map((day, i) => {
    if (!incremental) return {day, type: method === 'LEVEL0' ? 'L0' : 'FULL', size: 100, needed: i === days.length - 1};
    if (i === 0) return {day, type: 'L0', size: 100, needed: true};
    return method === 'LEVEL1' ? {day, type: 'L1', size: 18, needed: true} : {day, type: 'L1 ac.', size: 12 + i * 12, needed: i === days.length - 1};
  });
  const count = cells.filter(c => c.needed).length;
  const pieces = cells.filter(c => c.needed).map(c => `${c.type} ${c.day.toLowerCase()}`).join(' + ');
  $('#chain').innerHTML = `<figcaption><b>Cómo se recuperaría</b> · ejemplo: ${incremental ? 'nivel 0 el domingo y un nivel 1 cada día' : 'un respaldo cada día'}; la base falla el viernes a las 15:00</figcaption>
    <div class="chain-bars">${cells.map(c => `<div class="chain-col ${c.needed ? 'needed' : ''}"><span class="bar" style="height:${c.size * .44}px"></span><b>${c.type}</b><small>${c.day}</small></div>`).join('')}
      <div class="chain-col fail">${icon('zap')}<b>Fallo</b><small>vie 15:00</small></div></div>
    ${logs ? '<div class="chain-logs"><span></span>archived redo logs</div>' : ''}
    <p><b>RMAN restaura ${count} ${count === 1 ? 'pieza' : 'piezas'}:</b> ${pieces}${logs ? ', y aplica los archived logs hasta las 15:00: se recupera todo.' : '. Sin archived logs solo se vuelve al momento del último respaldo; se pierden los cambios posteriores.'}</p>`;
}

// ---------------- ESTRATEGIAS ----------------
function renderStrategies() {
  const term = $('#search').value.toLowerCase(), filter = $('#status-filter').value, db = $('#database-filter').value;
  const rows = state.strategies.filter(v => (!db || v.strategy.databaseId === db) && (!term || (v.strategy.name + ' ' + dbName(v.strategy.databaseId)).toLowerCase().includes(term))
    && (!filter || (filter === 'scheduled' && v.scheduled) || (filter === 'pending' && !v.approved) || (filter === 'inactive' && !v.strategy.enabled)));
  $('#strategy-foot').textContent = rows.length + ' de ' + state.strategies.length + ' estrategias';
  $('#strategies-body').innerHTML = rows.length ? rows.map(v => {
    const s = v.strategy, busy = state.active[s.databaseId];
    const status = (busy ? statusBadge('EJECUTANDO') : '') + lifecycle(v);
    return `<tr>
      <td><strong>${esc(s.name)}</strong><small>${esc(dbName(s.databaseId))} · prioridad ${s.priority.toLowerCase()}${s.responsible ? ' · ' + esc(s.responsible) : ''}</small></td>
      <td><small class="dark">${esc(v.what)}</small></td>
      <td><small class="dark">${esc(v.how)}</small><small>Destino ${esc(s.destination)}</small></td>
      <td><small class="dark">${esc(v.schedule)}</small><small>${v.nextRun ? 'Próxima: ' + formatDate(v.nextRun) : ''}</small></td>
      <td>${status}</td>
      <td><div class="row-actions">${button('run', s.id, 'Ejecutar respaldo ahora', 'play', 'run')}${button('validate', s.id, 'Verificar respaldos (RESTORE VALIDATE)', 'shield-check')}${button('review', s.id, 'Revisar y aprobar script', 'file-check-2')}${button('edit', s.id, 'Editar estrategia', 'pencil')}${button('delete', s.id, 'Eliminar estrategia', 'trash-2')}</div></td>
    </tr>`;
  }).join('') : `<tr><td colspan="6" class="empty">${icon('layers')}No hay estrategias para esta selección.</td></tr>`;
}

// ---------------- EJECUCIONES ----------------
function renderExecutions() {
  const filter = $('#execution-filter').value, op = $('#operation-filter').value;
  const rows = state.executions.filter(e => (!filter || e.status === filter) && (!op || e.operation === op));
  $('#executions-body').innerHTML = rows.length ? rows.map(e => `<tr>
      <td>${formatDate(e.startedAt)}</td>
      <td><strong>${esc(e.strategyName)}</strong><small>${esc(e.databaseName || dbName(e.databaseId))}</small></td>
      <td><strong>${e.operation === 'BACKUP' ? 'Respaldo' : 'Verificación'}</strong><small>${esc(e.backupType || '')}</small></td>
      <td>${e.source === 'HORARIO' ? 'Programada' : 'Manual'}</td>
      <td>${duration(e)}</td>
      <td>${statusBadge(e.status)}<small class="clip">${esc(e.message)}</small></td>
      <td>${button('detail', e.id, 'Abrir evidencia', 'file-text')}</td>
    </tr>`).join('') : `<tr><td colspan="7" class="empty">${icon('history')}Todavía no hay ejecuciones${filter || op ? ' con este filtro' : ''}.</td></tr>`;
}

// ---------------- BASES ----------------
function renderDatabases() {
  $('#databases-list').innerHTML = state.databases.map(d => {
    const st = state.statuses[d.id];
    let body;
    if (!st) body = '<p class="muted">Sin comprobar. La comprobación lee el modo de archivado, los tablespaces, los datafiles y el espacio en los destinos.</p>';
    else if (!st.reachable) body = `<p class="form-error">${esc(st.message)}</p>`;
    else {
      const size = st.datafiles.reduce((a, f) => a + f.bytes, 0);
      const ts = [...new Set(st.datafiles.map(f => f.container && !['CDB$ROOT', '-'].includes(f.container) ? f.container + ':' + f.tablespace : f.tablespace))];
      body = `<dl class="facts">
        <dt>Versión</dt><dd>${esc(st.version)}</dd><dt>Base</dt><dd>${esc(st.dbName)} · ${esc(st.openMode)}</dd>
        <dt>PDBs</dt><dd>${esc(st.pdbs.join(', ') || '—')}</dd><dt>Tablespaces</dt><dd>${esc(ts.join(', '))}</dd>
        <dt>Datafiles</dt><dd>${st.datafiles.length} · ${human(size)}</dd><dt>Archived logs</dt><dd>${st.archivedLogs} disponibles</dd>
        <dt>Destinos</dt><dd>${Object.entries(st.freeKb).map(([k, v]) => `${esc(k)}: ${v < 0 ? 'no existe' : human(v * 1024) + ' libres'}`).join('<br>') || '—'}</dd>
        <dt>RMAN</dt><dd>${st.rmanAvailable ? 'Disponible' : 'No encontrado'}</dd></dl>`;
    }
    const mode = !st ? badge('Sin comprobar') : !st.reachable ? badge('Sin conexión', 'bad') : badge(st.logMode, st.logMode === 'ARCHIVELOG' ? 'good' : 'warn');
    return `<article class="database-item"><div class="database-head">${icon('database')}<div><strong>${esc(d.name)}</strong><small>Contenedor ${esc(d.container)}</small></div>${mode}</div>
      <div class="database-info">${body}</div><small class="muted">${st ? 'Comprobada ' + formatDate(st.checkedAt) : ''}</small>
      <button class="secondary" data-action="diagnose" data-id="${esc(d.id)}">${icon('plug-zap')}Comprobar conexión</button></article>`;
  }).join('');
}

// ---------------- CONSTRUCTOR ----------------
const form = $('#strategy-form');
const field = name => form.elements.namedItem(name);
const radio = name => form.querySelector(`input[name=${name}]:checked`)?.value;
const setRadio = (name, value) => { const el = form.querySelector(`input[name=${name}][value="${value}"]`); if (el) el.checked = true; };

function openStrategy(s) {
  form.reset();
  field('databaseId').innerHTML = state.databases.map(d => `<option value="${esc(d.id)}">${esc(d.name)}</option>`).join('');
  field('id').value = s?.id || '';
  field('name').value = s?.name || '';
  field('description').value = s?.description || '';
  if (s) field('databaseId').value = s.databaseId;
  field('responsible').value = s?.responsible || '';
  field('priority').value = s?.priority || 'ALTA';
  field('enabled').checked = s?.enabled ?? true;
  setRadio('scope', s?.scope || 'TABLESPACE');
  field('tablespaces').value = s?.tablespaces?.join(', ') || '';
  field('datafiles').value = s?.datafiles?.join(', ') || '';
  field('archivelogs').checked = s?.archivelogs ?? true;
  field('controlfile').checked = s?.controlfile ?? true;
  field('spfile').checked = s?.spfile ?? true;
  field('method').value = s?.method || 'FULL';
  field('compressed').checked = s?.compressed ?? true;
  field('verifyAfter').checked = s?.verifyAfter ?? false;
  field('startDate').value = s?.startDate || today();
  setRadio('frequency', s?.frequency || 'DIARIA');
  formDays = new Set(s?.frequency && s.frequency !== 'DIARIA' ? s.days : DAYS.map(d => d[0]));
  formTimes = s?.frequency === 'INTERVALO' ? [] : [...(s?.times || [])];
  field('intervalHours').value = s?.intervalHours || 4;
  $('#interval-start').value = s?.frequency === 'INTERVALO' ? s.times[0] || '00:00' : '00:00';
  field('windowMinutes').value = s?.windowMinutes ?? '';
  field('destination').value = s?.destination || '/opt/oracle/backup';
  $('#form-error').textContent = '';
  $('#strategy-dialog-title').textContent = s ? 'Editar estrategia' : 'Nueva estrategia';
  syncForm(); renderTimes();
  $('#strategy-dialog').showModal();
  schedulePreview(0);
}
function readStrategy() {
  const scope = radio('scope'), frequency = radio('frequency');
  const datafiles = field('datafiles').value.split(/[,\s]+/).filter(Boolean);
  if (scope === 'DATAFILE' && datafiles.some(v => !/^\d+$/.test(v))) throw Error('Los datafiles se indican por número, separados por coma.');
  const number = v => v === '' ? null : Number(v);
  return {
    id: field('id').value || null, name: field('name').value, description: field('description').value || null,
    databaseId: field('databaseId').value, responsible: field('responsible').value || null, priority: field('priority').value, enabled: field('enabled').checked,
    scope, tablespaces: scope === 'TABLESPACE' ? field('tablespaces').value.split(/[,\s]+/).filter(Boolean) : [],
    datafiles: scope === 'DATAFILE' ? datafiles.map(Number) : [],
    archivelogs: field('archivelogs').checked, controlfile: field('controlfile').checked, spfile: field('spfile').checked,
    method: field('method').value, compressed: field('compressed').checked, verifyAfter: field('verifyAfter').checked,
    startDate: field('startDate').value || null, frequency,
    days: frequency === 'DIARIA' ? [] : DAYS.map(d => d[0]).filter(d => formDays.has(d)),
    times: frequency === 'INTERVALO' ? [$('#interval-start').value].filter(Boolean) : formTimes,
    intervalHours: frequency === 'INTERVALO' ? number(field('intervalHours').value) : null,
    windowMinutes: number(field('windowMinutes').value), destination: field('destination').value || null
  };
}
function syncForm() {
  const scope = radio('scope'), frequency = radio('frequency'), method = field('method').value;
  $('#tablespaces-field').classList.toggle('hidden', scope !== 'TABLESPACE');
  $('#datafiles-field').classList.toggle('hidden', scope !== 'DATAFILE');
  field('method').disabled = scope === 'COMPONENTS';
  $('#level-output').textContent = scope === 'COMPONENTS' ? 'No aplica (solo componentes)' : LEVEL_TEXT[method];
  $('#method-hint').textContent = scope === 'COMPONENTS' ? 'Los archived logs, el control file y el SPFILE se copian completos; los incrementales solo aplican a datafiles.' : METHOD_HINT[method];
  $('#priority-hint').textContent = PRIORITY_HINT[field('priority').value];
  renderChain(); icons();
  $('#days-field').classList.toggle('hidden', frequency === 'DIARIA');
  $('#interval-field').classList.toggle('hidden', frequency !== 'INTERVALO');
  $('#times-field').classList.toggle('hidden', frequency === 'INTERVALO');
  $('#days-field').innerHTML = DAYS.map(([code, label]) => `<button type="button" class="day ${formDays.has(code) ? 'selected' : ''}" data-day="${code}" aria-pressed="${formDays.has(code)}">${label}</button>`).join('');
  const st = state.statuses[field('databaseId').value], dest = field('destination').value || '/opt/oracle/backup';
  const free = st?.freeKb?.[dest];
  $('#space-hint').textContent = !st ? 'Espacio no medido: comprueba la base en «Bases de datos».' : free === undefined ? 'Este destino aún no se ha medido; guarda la estrategia y vuelve a comprobar la base.'
    : free < 0 ? 'El destino no existe o no es accesible en el servidor Oracle.' : `${human(free * 1024)} libres en ${dest} (comprobado ${formatDate(st.checkedAt)}).`;
  const chosen = new Set(field('tablespaces').value.toUpperCase().split(/[,\s]+/).filter(Boolean));
  const ts = st?.reachable ? [...new Set(st.datafiles.filter(f => f.container !== 'PDB$SEED').map(f => f.container && !['CDB$ROOT', '-'].includes(f.container) ? f.container + ':' + f.tablespace : f.tablespace))] : [];
  $('#tablespace-options').innerHTML = ts.map(t => `<button type="button" class="pick ${chosen.has(t) ? 'selected' : ''}" data-pick-ts="${esc(t)}">${esc(t)}</button>`).join('');
  const files = new Set(field('datafiles').value.split(/[,\s]+/).filter(Boolean));
  $('#datafile-options').innerHTML = st?.reachable ? st.datafiles.map(f => `<button type="button" class="pick ${files.has(String(f.file)) ? 'selected' : ''}" data-pick-df="${f.file}" title="${esc(f.path)}">${f.file} · ${esc(f.tablespace)} · ${human(f.bytes)}</button>`).join('') : '';
}
function renderTimes() {
  $('#times-list').innerHTML = formTimes.map(t => `<span class="time-chip">${esc(t)}<button type="button" data-remove-time="${esc(t)}" aria-label="Quitar horario ${esc(t)}">${icon('x')}</button></span>`).join('') || '<span class="muted">Sin horas: la estrategia no se programará.</span>';
  icons();
}
function renderIssues(target, issues) {
  $(target).innerHTML = issues.map(i => `<li class="${LEVEL[i.level].cls}">${levelBadge(i.level)}<span>${esc(i.message)}</span></li>`).join('') || '<li class="good-line">Sin observaciones.</li>';
}
function schedulePreview(delay = 250) {
  clearTimeout(previewTimer);
  previewTimer = setTimeout(async () => {
    try {
      const result = await api('preview', {strategy: readStrategy()});
      $('#live-script').textContent = result.script; $('#live-script').classList.remove('stale');
      $('#preview-hash').textContent = 'huella ' + result.hash;
      renderIssues('#live-issues', result.issues);
      $('#live-schedule').textContent = result.schedule + (result.nextRuns.length ? ' · próximas: ' + result.nextRuns.map(formatDate).join(', ') : '');
    } catch (error) {
      $('#live-script').classList.add('stale');
      renderIssues('#live-issues', [{level: 'ERROR', message: error.message}]);
    }
    icons();
  }, delay);
}

// ---------------- REVISION Y APROBACION ----------------
async function openReview(id) {
  const v = view(id); if (!v) return;
  reviewId = id;
  const data = await api('preview', {strategy: v.strategy});
  $('#review-title').textContent = 'Revisar script · ' + v.strategy.name;
  const approvedNow = v.approved && v.approval?.scriptHash === data.hash;
  $('#review-summary').innerHTML = `<b>Qué:</b> ${esc(v.what)} · <b>Cómo:</b> ${esc(v.how)} · <b>Cuándo:</b> ${esc(data.schedule)} · <b>Destino:</b> ${esc(v.strategy.destination)}`
    + (approvedNow ? `<br>${icon('badge-check')} Aprobado por ${esc(v.approval.approvedBy)} el ${formatDate(v.approval.approvedAt)}.` : v.approval ? '<br>La configuración cambió después de la última aprobación.' : '');
  renderIssues('#review-issues', data.issues);
  $('#review-script').textContent = data.script;
  $('#review-hash').textContent = data.hash;
  $('#review-form').elements.approvedBy.value = v.approval?.approvedBy || v.strategy.responsible || '';
  const blocked = data.issues.some(i => i.level === 'ERROR');
  $('#approve-button').disabled = blocked;
  $('#review-error').textContent = blocked ? 'Corrige los errores antes de aprobar.' : '';
  document.querySelectorAll('#review-dialog .flow li').forEach((li, i) => { li.className = i < 3 || (approvedNow && i === 3) ? 'done' : (approvedNow ? i === 4 : i === 3) ? 'current' : ''; });
  if (!$('#review-dialog').open) $('#review-dialog').showModal();
  icons();
}

// ---------------- EVIDENCIA ----------------
async function openExecution(id, silent = false) {
  const data = await api('executions/' + id); const e = data.execution;
  detail = {id, data, mode: silent ? detail.mode : 'evidence'};
  $('#detail-title').textContent = e.strategyName;
  $('#detail-eyebrow').textContent = e.operation === 'BACKUP' ? 'EVIDENCIA DEL RESPALDO' : 'EVIDENCIA DE LA VERIFICACIÓN';
  $('#detail-message').innerHTML = statusBadge(e.status) + ' ' + esc(e.message);
  const facts = [['Estrategia', e.strategyName], ['Base de datos', e.databaseName || dbName(e.databaseId)], ['Fecha', fmt(e.startedAt, {dateStyle: 'medium'})],
    ['Hora de inicio', fmt(e.startedAt, {timeStyle: 'medium', hourCycle: 'h23'})], ['Hora de finalización', e.finishedAt ? fmt(e.finishedAt, {timeStyle: 'medium', hourCycle: 'h23'}) : 'En curso'],
    ['Duración', duration(e)], ['Tipo de respaldo', e.backupType || e.operation], ['Origen', e.source === 'HORARIO' ? 'Programada (' + formatDate(e.plannedAt) + ')' : 'Manual'],
    ['Código de salida RMAN', e.exitCode ?? '—'], ['Ubicación del respaldo', e.destination || '—'], ['Huella del script', e.scriptHash || '—']];
  $('#detail-facts').innerHTML = facts.map(([k, v]) => `<dt>${k}</dt><dd>${esc(v)}</dd>`).join('');
  $('#show-verify').classList.toggle('hidden', !data.verifyLog);
  showDetail();
  if (!$('#detail-dialog').open) $('#detail-dialog').showModal();
  icons();
}
function detailText() {
  const {data, mode} = detail, e = data.execution;
  if (mode === 'script') return data.script;
  if (mode === 'log') return data.log || 'Esperando salida de RMAN...';
  if (mode === 'verify') return (data.verifyScript ? data.verifyScript + '\n' : '') + (data.verifyLog || '');
  const pieces = e.pieces.length ? e.pieces.map(p => '  ' + p).join('\n') : '  (ninguna)';
  const details = e.details.length ? e.details.map(d => '  - ' + d).join('\n') : '  (ninguno)';
  return `RESULTADO: ${STATUS[e.status]?.label || e.status}\n${e.message}\n\nPIEZAS DE RESPALDO COMPROBADAS EN EL SERVIDOR\n${pieces}\n\nMENSAJES DE RMAN (errores y advertencias)\n${details}`;
}
function showDetail() {
  $('#detail-content').textContent = detailText();
  [['evidence', '#show-evidence'], ['script', '#show-script'], ['log', '#show-log'], ['verify', '#show-verify']].forEach(([m, sel]) => $(sel).classList.toggle('selected', detail.mode === m));
}

// ---------------- ACCIONES ----------------
async function runOperation(strategyId, operation) {
  const result = await api('run', {strategyId, operation});
  toast(operation === 'BACKUP' ? 'Respaldo iniciado.' : 'Verificación iniciada.');
  await refresh();
  await openExecution(result.id);
}
async function diagnose(id, buttonEl) {
  if (buttonEl) { buttonEl.innerHTML = icon('loader-circle') + 'Comprobando...'; icons(); }
  const result = await api('diagnose', {databaseId: id});
  toast(result.message, !result.reachable);
  await refresh();
}
async function alertAction(action, strategyId, databaseId, el) {
  if (action === 'COMPROBAR') return diagnose(databaseId, el);
  if (action === 'LIBERAR') {
    if (!confirm('Libera la base solo si comprobaste en Oracle que RMAN ya no se está ejecutando. ¿Continuar?')) return;
    await api('release', {databaseId}); toast('Base liberada.'); return refresh();
  }
  if (action === 'APROBAR') return openReview(strategyId);
  if (action === 'EDITAR') return openStrategy(view(strategyId)?.strategy);
  if (action === 'VERIFICAR') return runOperation(strategyId, 'VALIDATE');
  if (action === 'EJECUTAR') return runOperation(strategyId, 'BACKUP');
  if (action.startsWith('VER:')) return openExecution(action.slice(4));
  if (action === 'AGREGAR_ARCHIVELOGS') {
    if (!confirm('La recomendación agregará los archived redo logs a la estrategia. El script cambiará y deberá aprobarse de nuevo. ¿Aplicar?')) return;
    await api('recommendations/apply', {strategyId, action});
    toast('Recomendación aplicada. Revisa y aprueba el script nuevo.');
    await refresh(); return openReview(strategyId);
  }
}

document.querySelectorAll('.nav').forEach(n => n.addEventListener('click', () => switchView(n.dataset.view)));
document.querySelectorAll('.close-dialog').forEach(b => b.addEventListener('click', () => b.closest('dialog').close()));
$('#new-button').addEventListener('click', () => {
  if (currentView === 'databases') { $('#database-form').reset(); $('#db-error').textContent = ''; $('#database-dialog').showModal(); }
  else openStrategy();
});
$('#refresh').addEventListener('click', refresh);
$('#search').addEventListener('input', renderStrategies);
['#database-filter', '#status-filter'].forEach(s => $(s).addEventListener('change', renderStrategies));
['#execution-filter', '#operation-filter'].forEach(s => $(s).addEventListener('change', renderExecutions));
form.addEventListener('input', () => { syncForm(); schedulePreview(); });
form.addEventListener('change', () => { syncForm(); schedulePreview(); });
form.addEventListener('click', e => {
  const day = e.target.closest('[data-day]');
  if (day) { formDays.has(day.dataset.day) ? formDays.delete(day.dataset.day) : formDays.add(day.dataset.day); syncForm(); schedulePreview(); }
  const ts = e.target.closest('[data-pick-ts]');
  if (ts) {
    const list = field('tablespaces').value.toUpperCase().split(/[,\s]+/).filter(Boolean), value = ts.dataset.pickTs;
    field('tablespaces').value = (list.includes(value) ? list.filter(v => v !== value) : [...list, value]).join(', ');
    syncForm(); schedulePreview();
  }
  const df = e.target.closest('[data-pick-df]');
  if (df) {
    const list = field('datafiles').value.split(/[,\s]+/).filter(Boolean), value = df.dataset.pickDf;
    field('datafiles').value = (list.includes(value) ? list.filter(v => v !== value) : [...list, value]).join(', ');
    syncForm(); schedulePreview();
  }
  const remove = e.target.closest('[data-remove-time]');
  if (remove) { formTimes = formTimes.filter(t => t !== remove.dataset.removeTime); renderTimes(); schedulePreview(); }
});
$('#add-time').addEventListener('click', () => {
  const value = $('#time-input').value; if (!value) return;
  if (!formTimes.includes(value)) formTimes.push(value);
  formTimes.sort(); renderTimes(); schedulePreview();
});
form.addEventListener('submit', async e => {
  e.preventDefault();
  const submit = form.querySelector('[type=submit]'); submit.disabled = true;
  try {
    const result = await api('strategies', readStrategy());
    $('#strategy-dialog').close(); await refresh();
    if (!result.approved) { toast('Estrategia guardada. Revisa y aprueba el script para programarla.'); await openReview(result.strategy.id); }
    else toast('Estrategia guardada; el script no cambió y sigue aprobado.');
  } catch (error) { $('#form-error').textContent = error.message; }
  finally { submit.disabled = false; }
});
$('#review-form').addEventListener('submit', async e => {
  e.preventDefault();
  try {
    await api('approve', {strategyId: reviewId, approvedBy: e.currentTarget.elements.approvedBy.value});
    const v = view(reviewId);
    toast(v?.strategy.enabled && v.strategy.times.length ? 'Script aprobado: la estrategia quedó programada.' : 'Script aprobado. Actívala y agrega horarios para programarla.');
    $('#review-dialog').close(); await refresh();
  } catch (error) { $('#review-error').textContent = error.message; }
});
$('#database-form').addEventListener('submit', async e => {
  e.preventDefault(); const f = e.currentTarget, b = f.querySelector('[type=submit]'); b.disabled = true;
  try { await api('databases', {name: f.elements.name.value, container: f.elements.container.value}); $('#database-dialog').close(); toast('Base registrada. Comprueba la conexión para validar sus estrategias.'); await refresh(); }
  catch (error) { $('#db-error').textContent = error.message; }
  finally { b.disabled = false; }
});
$('#alert-filters').addEventListener('click', e => { const b = e.target.closest('[data-alert-filter]'); if (b) { alertFilter = b.dataset.alertFilter; renderAlerts(); icons(); } });
document.body.addEventListener('click', async e => {
  const b = e.target.closest('[data-action],[data-alert-action]'); if (!b) return;
  b.disabled = true;
  try {
    if (b.dataset.alertAction) return await alertAction(b.dataset.alertAction, b.dataset.strategy, b.dataset.db, b);
    const {action, id} = b.dataset, v = view(id);
    if (action === 'edit') openStrategy(v.strategy);
    if (action === 'review') await openReview(id);
    if (action === 'delete' && confirm('¿Eliminar la estrategia "' + v.strategy.name + '"? El historial se conserva.')) { await api('strategies/delete', {id}); toast('Estrategia eliminada.'); await refresh(); }
    if (action === 'run') await runOperation(id, 'BACKUP');
    if (action === 'validate') await runOperation(id, 'VALIDATE');
    if (action === 'detail') await openExecution(id);
    if (action === 'diagnose') await diagnose(id, b);
  } catch (error) { toast(error.message, true); }
  finally { b.disabled = false; }
});
// Tooltip y apertura de evidencia desde la linea de tiempo.
const tip = $('#timeline-tip');
function showTip(target) {
  const panel = target.closest('.panel').getBoundingClientRect(), box = target.getBoundingClientRect();
  tip.textContent = target.dataset.tip; tip.classList.remove('hidden');
  const left = Math.min(Math.max(box.left - panel.left + box.width / 2 - tip.offsetWidth / 2, 8), panel.width - tip.offsetWidth - 8);
  tip.style.left = left + 'px'; tip.style.top = (box.top - panel.top - tip.offsetHeight - 8) + 'px';
}
$('#timeline').addEventListener('mouseover', e => { const m = e.target.closest('.mark'); if (m) showTip(m); });
$('#timeline').addEventListener('focusin', e => { const m = e.target.closest('.mark'); if (m) showTip(m); });
$('#timeline').addEventListener('mouseout', e => { if (e.target.closest('.mark')) tip.classList.add('hidden'); });
$('#timeline').addEventListener('focusout', () => tip.classList.add('hidden'));
$('#timeline').addEventListener('click', e => { const m = e.target.closest('.mark[data-execution]'); if (m) openExecution(m.dataset.execution).catch(err => toast(err.message, true)); });
$('#timeline').addEventListener('keydown', e => { const m = e.target.closest('.mark[data-execution]'); if (m && e.key === 'Enter') openExecution(m.dataset.execution); });
[['#show-evidence', 'evidence'], ['#show-script', 'script'], ['#show-log', 'log'], ['#show-verify', 'verify']].forEach(([sel, mode]) => $(sel).addEventListener('click', () => { detail.mode = mode; showDetail(); }));
$('#download-detail').addEventListener('click', () => {
  const url = URL.createObjectURL(new Blob([detailText()], {type: 'text/plain;charset=utf-8'}));
  const a = document.createElement('a'); a.href = url; a.download = {script: 'estrategia.rman', log: 'ejecucion.log', verify: 'verificacion.log', evidence: 'evidencia.txt'}[detail.mode]; a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
});
window.addEventListener('resize', () => { clearTimeout(renderTimeline.t); renderTimeline.t = setTimeout(renderTimeline, 150); });
icons(); refresh(); setInterval(refresh, 5000);
