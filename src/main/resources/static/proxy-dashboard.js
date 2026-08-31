const sessions = new Map();
const expandedTurns = new Set();
const autoExpandedTurns = new Set();
const turnEls = new Map();
let selectedSessionId = null;
let renderedSessionId = null;

// A completion event is emitted when the private workbook call ends, before the provider's
// client-visible continuation has necessarily reported its usage. Coalesce terminal refreshes so
// several turns finishing together share one request, and serialize refreshes so an older response
// can never race a newer one.
const SNAPSHOT_REFRESH_DELAY_MS = 100;
const SNAPSHOT_REFRESH_RETRY_DELAY_MS = 500;
const MAX_SNAPSHOT_REFRESH_ATTEMPTS = 8;
let snapshotRefreshTimer = null;
let snapshotRefreshInFlight = false;
let snapshotRefreshQueued = false;
let snapshotRefreshAttempts = 0;

// Streams the current turn's working notes inline the moment it starts (like ChatGPT's
// live reasoning view), then collapses it back to a one-line summary once it finishes --
// unless the user already clicked it open themselves, in which case leave their choice alone.
function syncAutoExpand(trace) {
  if (trace.status === 'running') {
    if (!expandedTurns.has(trace.requestId)) {
      expandedTurns.add(trace.requestId);
      autoExpandedTurns.add(trace.requestId);
    }
  } else if (autoExpandedTurns.delete(trace.requestId)) {
    expandedTurns.delete(trace.requestId);
  }
}

function ensureSession(sessionId, provider, model) {
  let session = sessions.get(sessionId);
  if (!session) {
    session = { sessionId, provider, model: model || '', traces: new Map(), order: [] };
    sessions.set(sessionId, session);
  }
  if (model) session.model = model;
  return session;
}

function ensureTrace(session, requestId, startedAt) {
  let trace = session.traces.get(requestId);
  if (!trace) {
    trace = { requestId, status: 'running', startedAt: startedAt || null, completedAt: null, visibleWork: '', toolName: '', inputTokens: 0, outputTokens: 0, eventSequence: 0, refreshPending: false };
    session.traces.set(requestId, trace);
    session.order.push(requestId);
  }
  if (!trace.startedAt && startedAt) trace.startedAt = startedAt;
  return trace;
}

function eventSequence(event) {
  const sequence = Number(event && event.sequence);
  return Number.isFinite(sequence) && sequence > 0 ? sequence : 0;
}

function terminalStatus(status) {
  return status && status !== 'running';
}

function ingestSnapshot(snapshot) {
  const session = ensureSession(snapshot.sessionId, snapshot.provider, snapshot.model);
  const trace = ensureTrace(session, snapshot.requestId, snapshot.startedAt);
  const events = snapshot.events || [];
  const sequence = events.reduce((latest, event) => Math.max(latest, eventSequence(event)), 0);
  const isNewer = sequence >= trace.eventSequence;
  const inputTokens = Number(snapshot.inputTokens) || 0;
  const outputTokens = Number(snapshot.outputTokens) || 0;
  const usageChanged = inputTokens > trace.inputTokens || outputTokens > trace.outputTokens;

  // Usage is monotonic for a turn and can change without a new trace event when the continuation
  // finishes. Always accept the canonical total, even when its event sequence has not advanced.
  trace.inputTokens = Math.max(trace.inputTokens, inputTokens);
  trace.outputTokens = Math.max(trace.outputTokens, outputTokens);
  if (usageChanged && terminalStatus(snapshot.status)) trace.refreshPending = false;
  if (isNewer && !(terminalStatus(trace.status) && snapshot.status === 'running')) {
    trace.status = snapshot.status;
    trace.visibleWork = snapshot.visibleWork || '';
    if (snapshot.status !== 'running' && events.length) {
      trace.completedAt = events[events.length - 1].timestamp;
    }
    trace.eventSequence = sequence;
  }
  const toolEvent = events.find(event => event.eventType === 'tool_call');
  if (toolEvent && toolEvent.visibleDelta) trace.toolName = toolEvent.visibleDelta;
  syncAutoExpand(trace);
  return usageChanged;
}

function pendingSnapshotRefresh() {
  return [...sessions.values()].some(session =>
    session.order.some(requestId => session.traces.get(requestId).refreshPending));
}

function markSnapshotRefreshPending(trace) {
  if (!pendingSnapshotRefresh()) snapshotRefreshAttempts = 0;
  trace.refreshPending = true;
}

function loadSnapshots() {
  if (snapshotRefreshInFlight) {
    snapshotRefreshQueued = true;
    return;
  }
  if (pendingSnapshotRefresh()) snapshotRefreshAttempts++;
  snapshotRefreshInFlight = true;
  fetch('/api/v1/traces')
    .then(response => response.json())
    .then(list => {
      list.slice().reverse().forEach(snapshot => {
        try {
          ingestSnapshot(snapshot);
        } catch (err) {
          console.error('failed to ingest trace snapshot', snapshot && snapshot.requestId, err);
        }
      });
      render();
      if (pendingSnapshotRefresh() && snapshotRefreshAttempts < MAX_SNAPSHOT_REFRESH_ATTEMPTS) {
        scheduleSnapshotRefresh(SNAPSHOT_REFRESH_RETRY_DELAY_MS);
      }
    })
    .catch(err => {
      console.error('failed to load trace history', err);
      if (pendingSnapshotRefresh() && snapshotRefreshAttempts < MAX_SNAPSHOT_REFRESH_ATTEMPTS) {
        scheduleSnapshotRefresh(SNAPSHOT_REFRESH_RETRY_DELAY_MS);
      }
    })
    .finally(() => {
      snapshotRefreshInFlight = false;
      if (snapshotRefreshQueued) {
        snapshotRefreshQueued = false;
        if (pendingSnapshotRefresh() && snapshotRefreshAttempts < MAX_SNAPSHOT_REFRESH_ATTEMPTS) {
          scheduleSnapshotRefresh();
        }
      }
    });
}

function scheduleSnapshotRefresh(delay = SNAPSHOT_REFRESH_DELAY_MS) {
  if (snapshotRefreshTimer !== null) return;
  snapshotRefreshTimer = setTimeout(() => {
    snapshotRefreshTimer = null;
    loadSnapshots();
  }, delay);
}

function ingestEvent(event) {
  const session = ensureSession(event.sessionId, event.provider, event.model);
  const trace = ensureTrace(session, event.requestId, event.timestamp);
  const sequence = eventSequence(event);
  const isNewer = sequence === 0 || sequence > trace.eventSequence;
  trace.inputTokens = Math.max(trace.inputTokens, Number(event.inputTokens) || 0);
  trace.outputTokens = Math.max(trace.outputTokens, Number(event.outputTokens) || 0);
  if (!isNewer) {
    render();
    return;
  }
  trace.eventSequence = Math.max(trace.eventSequence, sequence);
  switch (event.eventType) {
    case 'workbook_started':
      trace.status = 'running';
      break;
    case 'workbook_delta':
      trace.visibleWork += event.visibleDelta;
      break;
    case 'tool_call':
      trace.toolName = event.visibleDelta;
      break;
    case 'workbook_complete':
      trace.status = 'complete';
      trace.completedAt = event.timestamp;
      markSnapshotRefreshPending(trace);
      scheduleSnapshotRefresh();
      break;
    case 'usage_updated':
      trace.refreshPending = false;
      break;
    case 'cancelled':
      trace.status = 'cancelled';
      trace.completedAt = event.timestamp;
      markSnapshotRefreshPending(trace);
      scheduleSnapshotRefresh();
      break;
    case 'failed':
      trace.status = 'failed';
      trace.completedAt = event.timestamp;
      markSnapshotRefreshPending(trace);
      scheduleSnapshotRefresh();
      break;
    case 'capture_skipped':
      // The turn ran; only the capture didn't. A turn whose notes were already recorded fell back
      // afterwards and keeps both its notes and its completed status.
      if (trace.status === 'running') {
        trace.status = event.visibleDelta || 'capture_skipped';
        trace.completedAt = event.timestamp;
      }
      markSnapshotRefreshPending(trace);
      scheduleSnapshotRefresh();
      break;
    default:
      return;
  }
  syncAutoExpand(trace);
  render();
}

function latestStart(session) {
  let latest = 0;
  session.order.forEach(requestId => {
    const started = session.traces.get(requestId).startedAt;
    const time = started ? new Date(started).getTime() : 0;
    if (time > latest) latest = time;
  });
  return latest;
}

function dayLabel(date) {
  const startOfDay = value => new Date(value.getFullYear(), value.getMonth(), value.getDate()).getTime();
  const diffDays = Math.round((startOfDay(new Date()) - startOfDay(date)) / 86400000);
  if (diffDays <= 0) return 'Today';
  if (diffDays === 1) return 'Yesterday';
  if (diffDays <= 7) return 'Previous 7 days';
  return 'Older';
}

function formatClock(date) {
  return Number.isNaN(date.getTime()) ? '' : date.toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' });
}

// Titles from the first turn that actually captured notes, not from turn one. Claude Code opens an
// interactive session with a probe the capture path skips, and any turn can end untraced; reading
// turn one alone pinned every such session at "New session" for the rest of its life.
function titleFor(session) {
  const titled = session.order
      .map(requestId => session.traces.get(requestId))
      .find(trace => trace && trace.visibleWork.trim());
  const raw = (titled ? titled.visibleWork : '').replace(/\s+/g, ' ').trim();
  if (!raw) return 'New session';
  return raw.length > 48 ? raw.slice(0, 47) + '…' : raw;
}

function durationSeconds(trace) {
  if (!trace.startedAt || !trace.completedAt) return null;
  const start = new Date(trace.startedAt).getTime();
  const end = new Date(trace.completedAt).getTime();
  if (Number.isNaN(start) || Number.isNaN(end) || end < start) return null;
  return Math.max(0, Math.round((end - start) / 1000));
}

// Output tokens only. inputTokens is the whole prompt — context plus cache reads — so adding it in
// reported a six-figure "thought for" on a two-line note and grew every turn as the conversation did.
function thoughtTokens(trace) {
  const total = trace.outputTokens || 0;
  return total > 0 ? total : null;
}

// The client's real task completed for every one of these; only the private capture didn't happen,
// so none of them is a failure to report as one.
const UNTRACED_LABELS = new Map([
  ['unsupported_capture', 'Untraced — this request shape can’t carry a capture'],
  ['capture_timeout', 'Untraced — the private call ran past its deadline'],
  ['capture_skipped', 'Untraced — no notes came back'],
  ['capture_refused', 'Untraced — the model declined to write a note'],
  ['capture_loop_exhausted', 'Untraced — the private loop budget ran out'],
  ['provider_error', 'Untraced — the provider refused the call'],
]);

function turnLabel(trace) {
  if (trace.status === 'running') return trace.visibleWork ? 'Thinking…' : 'Waiting for the first note…';
  if (trace.status === 'cancelled') return 'Cancelled';
  if (UNTRACED_LABELS.has(trace.status)) return UNTRACED_LABELS.get(trace.status);
  if (trace.status === 'complete') {
    const seconds = durationSeconds(trace);
    const tokens = thoughtTokens(trace);
    if (seconds == null && tokens == null) return 'Thought';
    if (tokens == null) return `Thought for ${seconds}s`;
    if (seconds == null) return `Thought for ${tokens} tokens`;
    return `Thought for ${tokens} tokens in ${seconds}s`;
  }
  return trace.status === 'failed' ? 'Failed' : `Failed — ${trace.status}`;
}

function sessionHasRunningTurn(session) {
  return session.order.some(requestId => session.traces.get(requestId).status === 'running');
}

function render() {
  const current = selectedSessionId != null ? sessions.get(selectedSessionId) : null;
  if (!current) {
    // A session that is streaming right now outranks one that merely started later. Two requests
    // microseconds apart can invert a start-time ordering, which is how a fresh page load used to
    // open on a finished session while the live one sat unselected in the sidebar.
    const ordered = [...sessions.values()].sort((a, b) => latestStart(b) - latestStart(a));
    const live = ordered.filter(sessionHasRunningTurn);
    const chosen = live.length ? live[0] : ordered[0];
    selectedSessionId = chosen ? chosen.sessionId : null;
  } else if (!sessionHasRunningTurn(current)) {
    // Nothing pulling focus right now -- jump to whichever OTHER session is actively
    // streaming, so a new run is visible the instant it starts without stealing focus
    // from a session you're still watching mid-stream.
    const liveElsewhere = [...sessions.values()]
        .filter(session => session.sessionId !== current.sessionId && sessionHasRunningTurn(session))
        .sort((a, b) => latestStart(b) - latestStart(a));
    if (liveElsewhere.length) selectedSessionId = liveElsewhere[0].sessionId;
  }
  renderSidebar();
  renderPanel();
}

function selectSession(sessionId) {
  selectedSessionId = sessionId;
  render();
}

function renderSidebar() {
  const empty = document.querySelector('#empty');
  const list = document.querySelector('#session-list');
  const dayTemplate = document.querySelector('#day-group-template');
  const itemTemplate = document.querySelector('#session-item-template');
  const ordered = [...sessions.values()].sort((a, b) => latestStart(b) - latestStart(a));

  empty.hidden = ordered.length > 0;
  list.textContent = '';

  const groups = new Map();
  ordered.forEach(session => {
    const label = dayLabel(new Date(latestStart(session)));
    if (!groups.has(label)) groups.set(label, []);
    groups.get(label).push(session);
  });

  ['Today', 'Yesterday', 'Previous 7 days', 'Older'].forEach(label => {
    const group = groups.get(label);
    if (!group || group.length === 0) return;
    const groupEl = dayTemplate.content.firstElementChild.cloneNode(true);
    groupEl.querySelector('.day-label').textContent = label;
    const items = groupEl.querySelector('.day-items');
    group.forEach(session => {
      const item = itemTemplate.content.firstElementChild.cloneNode(true);
      item.dataset.sessionId = session.sessionId;
      item.dataset.provider = session.provider;
      item.classList.toggle('active', session.sessionId === selectedSessionId);
      item.querySelector('.session-title').textContent = titleFor(session);
      item.querySelector('.session-model').textContent = session.model || session.provider;
      item.querySelector('.session-time').textContent = formatClock(new Date(latestStart(session)));
      item.addEventListener('click', () => selectSession(session.sessionId));
      items.appendChild(item);
    });
    list.appendChild(groupEl);
  });
}

function applyExpanded(el, requestId) {
  const expanded = expandedTurns.has(requestId);
  el.querySelector('.turn-work').hidden = !expanded;
  el.querySelector('.turn-summary').setAttribute('aria-expanded', String(expanded));
  el.classList.toggle('expanded', expanded);
}

function buildTurnElement(requestId) {
  const template = document.querySelector('#turn-template');
  const el = template.content.firstElementChild.cloneNode(true);
  el.dataset.requestId = requestId;
  el.querySelector('.turn-summary').addEventListener('click', () => {
    autoExpandedTurns.delete(requestId);
    if (expandedTurns.has(requestId)) expandedTurns.delete(requestId); else expandedTurns.add(requestId);
    applyExpanded(el, requestId);
  });
  return el;
}

function updateTurnElement(el, trace, position) {
  el.dataset.status = trace.status;
  el.querySelector('.turn-number').textContent = `${position}.`;
  el.querySelector('.turn-label').textContent = turnLabel(trace);
  el.querySelector('.turn-work').textContent = trace.visibleWork || '';
  applyExpanded(el, trace.requestId);
  const toolEl = el.querySelector('.tool-call');
  if (trace.toolName) {
    toolEl.hidden = false;
    toolEl.querySelector('.tool-name').textContent = trace.toolName;
  } else {
    toolEl.hidden = true;
  }
}

function renderPanel() {
  const panelModel = document.querySelector('#panel-model');
  const turnsEl = document.querySelector('#turns');
  const panelEmpty = document.querySelector('#panel-empty');
  const session = selectedSessionId ? sessions.get(selectedSessionId) : null;

  if (!session) {
    turnsEl.textContent = '';
    turnEls.clear();
    renderedSessionId = null;
    panelModel.textContent = 'Select a session';
    panelEmpty.hidden = false;
    return;
  }

  panelEmpty.hidden = true;
  panelModel.textContent = session.model || session.provider;

  if (renderedSessionId !== session.sessionId) {
    turnsEl.textContent = '';
    turnEls.clear();
    renderedSessionId = session.sessionId;
  }

  session.order.forEach((requestId, index) => {
    let el = turnEls.get(requestId);
    if (!el) {
      el = buildTurnElement(requestId);
      turnEls.set(requestId, el);
      turnsEl.appendChild(el);
    }
    updateTurnElement(el, session.traces.get(requestId), index + 1);
  });
}

const connection = document.querySelector('#connection');
function setConnectionState(state, label) {
  connection.textContent = label;
  connection.dataset.state = state;
}
const events = new EventSource('/api/v1/events');
events.onopen = () => {
  setConnectionState('live', 'live');
  // EventSource does not replay mutations that happened while disconnected. Reconcile from the
  // canonical snapshots on every connection so a missed late usage update cannot stay stale.
  loadSnapshots();
};
events.onerror = () => { setConnectionState('reconnecting', 'reconnecting'); };
// Every event type TraceStore emits needs a listener here: EventSource delivers a named event only
// to a listener for that name, so a missing one leaves its turn stuck mid-stream until a reload.
['workbook_started', 'workbook_delta', 'tool_call', 'workbook_complete', 'usage_updated', 'cancelled',
  'failed', 'capture_skipped']
  .forEach(name => events.addEventListener(name, message => ingestEvent(JSON.parse(message.data))));

loadSnapshots();
