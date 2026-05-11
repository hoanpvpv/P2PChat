const WS_URL = `ws://${window.location.host}/ws`;
const API_BASE = '/api';

export async function fetchInfo() {
  const res = await fetch(`${API_BASE}/info`);
  return res.json();
}

export async function fetchPeers() {
  const res = await fetch(`${API_BASE}/peers`);
  return res.json();
}

export async function discoverPeers() {
  const res = await fetch(`${API_BASE}/discover`);
  return res.json();
}

export async function fetchHistory(peerName) {
  const res = await fetch(`${API_BASE}/history/${encodeURIComponent(peerName)}`);
  return res.json();
}

export async function fetchGroupHistory(groupName) {
  const res = await fetch(`${API_BASE}/group-history/${encodeURIComponent(groupName)}`);
  return res.json();
}

export async function fetchGroups() {
  const res = await fetch(`${API_BASE}/groups`);
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

export async function createGroup(groupName) {
  const res = await fetch(`${API_BASE}/group/create`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ groupName }),
  });
  return res.json();
}

export async function addToGroup(groupName, username) {
  const res = await fetch(`${API_BASE}/group/add`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ groupName, username }),
  });
  return res.json();
}

export async function sendGroupMessage(groupName, content) {
  const res = await fetch(`${API_BASE}/group/msg`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ groupName, content }),
  });
  return res.json();
}

export function connectWebSocket(onMessage) {
  let shouldReconnect = true;
  const ws = new WebSocket(WS_URL);
  ws.onmessage = (event) => {
    try {
      const data = JSON.parse(event.data);
      onMessage(data);
    } catch (e) {
      console.error('WebSocket parse error:', e);
    }
  };
  ws.onclose = () => {
    if (shouldReconnect) {
      setTimeout(() => connectWebSocket(onMessage), 3000);
    }
  };
  ws.onerror = () => {
    ws.close();
  };
  ws.stopReconnect = () => { shouldReconnect = false; };
  return ws;
}
