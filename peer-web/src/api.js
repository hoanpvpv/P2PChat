const API_BASE = '/api';

// ── Info & Peers ──────────────────────────────────────────────
export async function fetchInfo() {
  const res = await fetch(`${API_BASE}/info`);
  return res.json();
}

export async function registerPeer(payload) {
  const res = await fetch(`${API_BASE}/register`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  });
  const data = await res.json();
  if (!res.ok) throw new Error(data.error || data.lastBootstrapError || 'Registration failed');
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
