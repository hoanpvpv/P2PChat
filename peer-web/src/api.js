const API_BASE = '/api';

// ── Info & Peers ──────────────────────────────────────────────
export async function fetchInfo() {
  const res = await fetch(`${API_BASE}/info`);
  return res.json();
}

export async function autoDetectHost() {
  const res = await fetch(`${API_BASE}/auto-detect-host`);
  return res.json();
}

export async function initPeer(username, peerPort) {
  const res = await fetch(`${API_BASE}/init`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, peerPort }),
  });
  const data = await res.json();
  if (!res.ok) {
    throw new Error(data.error || data.lastBootstrapError || 'Initialization failed');
  }
  return data;
}

export async function fetchPeers() {
  const res = await fetch(`${API_BASE}/peers`);
  return res.json();
}

export async function discoverPeers() {
  const res = await fetch(`${API_BASE}/discover`);
  return res.json();
}

// ── Messages ──────────────────────────────────────────────────
export async function fetchHistory(peerName) {
  const res = await fetch(`${API_BASE}/history/${encodeURIComponent(peerName)}`);
  return res.json();
}

export async function fetchGroupHistory(groupId) {
  const res = await fetch(`${API_BASE}/group-history/${encodeURIComponent(groupId)}`);
  return res.json();
}

export async function fetchBroadcastHistory() {
  const res = await fetch(`${API_BASE}/broadcast-history`);
  return res.json();
}

export async function sendMessage(receiver, content) {
  const res = await fetch(`${API_BASE}/msg`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ receiver, content }),
  });
  return res.json();
}

export async function sendBroadcast(content) {
  const res = await fetch(`${API_BASE}/broadcast`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ content }),
  });
  return res.json();
}

// ── Groups — DHT-lite API ─────────────────────────────────────
export async function fetchGroups() {
  const res = await fetch(`${API_BASE}/group/list`);
  if (!res.ok) return [];
  return res.json();
}

export async function fetchGroupDetail(groupId) {
  const res = await fetch(`${API_BASE}/group/${encodeURIComponent(groupId)}`);
  return res.json();
}

export async function createGroup(groupName, members = [], groupMode = 'OPEN') {
  const res = await fetch(`${API_BASE}/group/create`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ groupName, members, groupMode }),
  });
  const data = await res.json();
  if (!res.ok) throw new Error(data.error || 'Failed to create group');
  return data;
}

export async function addToGroup(groupId, username) {
  const res = await fetch(`${API_BASE}/group/add`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ groupId, username }),
  });
  const data = await res.json();
  if (!res.ok) throw new Error(data.error || 'Failed to add member');
  return data;
}

export async function kickFromGroup(groupId, target) {
  const res = await fetch(`${API_BASE}/group/kick`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ groupId, target }),
  });
  return res.json();
}

export async function leaveGroup(groupId, newOwner) {
  const res = await fetch(`${API_BASE}/group/leave`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ groupId, newOwner }),
  });
  return res.json();
}

export async function disbandGroup(groupId) {
  const res = await fetch(`${API_BASE}/group/disband`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ groupId }),
  });
  return res.json();
}

export async function sendGroupMessage(groupId, content) {
  const res = await fetch(`${API_BASE}/group/msg`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ groupId, content }),
  });
  return res.json();
}


// ── File Transfer ─────────────────────────────────────────────
export async function getTransfers() {
  const res = await fetch(`${API_BASE}/file/transfers`);
  return res.json();
}

export async function offerFile(file, receiver, groupId) {
  const formData = new FormData();
  formData.append('file', file);
  if (receiver) formData.append('receiver', receiver);
  if (groupId) formData.append('groupId', groupId);

  const res = await fetch(`${API_BASE}/file/offer`, {
    method: 'POST',
    body: formData,
  });
  const data = await res.json();
  if (!res.ok) throw new Error(data.error || 'Failed to offer file');
  return data;
}

export async function acceptFile(transferId) {
  const res = await fetch(`${API_BASE}/file/accept`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ transferId }),
  });
  return res.json();
}

export async function rejectFile(transferId, reason = 'Rejected') {
  const res = await fetch(`${API_BASE}/file/reject`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ transferId, reason }),
  });
  return res.json();
}

export async function cancelFile(transferId) {
  const res = await fetch(`${API_BASE}/file/cancel`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ transferId }),
  });
  return res.json();
}

export async function fetchOutbox() {
  const res = await fetch(`${API_BASE}/outbox`);
  return res.json();
}

// ── Power (simulate abrupt offline) ──────────────────────────
export async function shutdownPeer() {
  const res = await fetch(`${API_BASE}/shutdown`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: '{}',
  });
  // The server kills its own JVM ~200ms after responding; the fetch
  // may resolve normally, or reject when the connection drops.
  return res.json().catch(() => ({ shuttingDown: true }));
}

// ── WebSocket ─────────────────────────────────────────────────
export function connectWebSocket(onMessage) {
  const WS_URL = `ws://${window.location.host}/ws`;
  let shouldReconnect = true;
  let ws;

  function connect() {
    ws = new WebSocket(WS_URL);
    ws.onmessage = (event) => {
      try { onMessage(JSON.parse(event.data)); } catch (e) { console.error('WS parse error:', e); }
    };
    ws.onclose = () => {
      if (shouldReconnect) setTimeout(connect, 3000);
    };
    ws.onerror = () => ws.close();
  }

  connect();
  return {
    stopReconnect: () => { shouldReconnect = false; },
    close: () => { shouldReconnect = false; ws?.close(); },
  };
}
