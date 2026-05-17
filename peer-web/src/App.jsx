import React, { useState, useEffect, useRef, useCallback } from 'react';
import {
  fetchInfo, fetchPeers, fetchGroups, fetchHistory, fetchGroupHistory,
  sendMessage, sendGroupMessage, sendBroadcast,
  createGroup, addToGroup, kickFromGroup, leaveGroup, disbandGroup,
  discoverPeers, connectWebSocket, registerPeer,
} from './api';
import Sidebar from './components/Sidebar';
import ChatArea from './components/ChatArea';
import MessageInput from './components/MessageInput';
import './App.css';

export default function App() {
  const [info, setInfo] = useState({
    username: '', host: '', peerPort: '', webPort: '',
    bootstrapHost: '', bootstrapPort: '', registered: false, lastBootstrapError: '',
  });
  const [peers, setPeers] = useState([]);
  const [groups, setGroups] = useState([]);
  const [activeChat, setActiveChat] = useState(null);   // { type:'peer'|'group'|'broadcast', id, name }
  const [messages, setMessages] = useState([]);
  const [error, setError] = useState('');
  const [toast, setToast] = useState(null);             // { message, variant: 'info'|'warn'|'error' }
  const [registering, setRegistering] = useState(false);
  const [registrationForm, setRegistrationForm] = useState({
    username: '', host: '', bootstrapHost: '', bootstrapPort: '',
  });
  const wsRef = useRef(null);
  const messagesEndRef = useRef(null);
  const activeChatRef = useRef(activeChat);

  useEffect(() => { activeChatRef.current = activeChat; }, [activeChat]);
  useEffect(() => { messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' }); }, [messages]);

  const showToast = useCallback((message, variant = 'info') => {
    setToast({ message, variant });
    setTimeout(() => setToast(null), 4000);
  }, []);

  // ── Data refresh ──────────────────────────────────────────
  const refreshData = useCallback(async () => {
    try {
      const [infoData, peersData, groupsData] = await Promise.all([
        fetchInfo(), fetchPeers(), fetchGroups(),
      ]);
      setInfo(infoData);
      setRegistrationForm(prev => ({
        username: prev.username || infoData.username || '',
        host: prev.host || infoData.host || '',
        bootstrapHost: prev.bootstrapHost || infoData.bootstrapHost || '',
        bootstrapPort: prev.bootstrapPort || String(infoData.bootstrapPort || ''),
      }));
      setPeers(peersData || []);
      setGroups(groupsData || []);
    } catch (e) {
      console.error('Failed to refresh data:', e);
    }
  }, []);

  // ── Chat history ──────────────────────────────────────────
  const loadHistory = useCallback(async (chat) => {
    try {
      if (chat.type === 'broadcast') { setMessages([]); return; }
      const msgs = chat.type === 'peer'
        ? await fetchHistory(chat.name)
        : await fetchGroupHistory(chat.id);
      setMessages(msgs || []);
    } catch (e) {
      console.error('History load failed:', e);
      setMessages([]);
    }
  }, []);

  // ── WebSocket ──────────────────────────────────────────────
  useEffect(() => {
    refreshData();
    const ws = connectWebSocket((event) => {
      const { type, data } = event;
      const cur = activeChatRef.current;

      switch (type) {
        case 'PEER_JOIN':
        case 'PEER_LEAVE':
          refreshData();
          break;

        case 'DIRECT_MESSAGE':
          if (cur?.type === 'peer' && (data.sender === cur.name || data.receiver === cur.name)) {
            setMessages(prev => [...prev, data]);
          }
          break;

        case 'GROUP_MESSAGE':
          if (cur?.type === 'group' && data.groupId === cur.id) {
            setMessages(prev => [...prev, data]);
          }
          break;

        case 'BROADCAST':
          if (cur?.type === 'broadcast') {
            setMessages(prev => [...prev, { ...data, type: 'BROADCAST' }]);
          }
          showToast(`📢 ${data.sender}: ${data.content}`, 'info');
          break;

        case 'GROUP_JOINED':
          refreshData();
          showToast(`✅ Joined group: ${data.groupName}`, 'info');
          break;

        case 'GROUP_UPDATED':
          setGroups(prev => prev.map(g =>
            g.groupId === data.groupId ? { ...g, ...data } : g
          ));
          if (cur?.type === 'group' && data.groupId === cur.id) {
            // Update members in chat header
            setActiveChat(prev => prev ? { ...prev, members: data.members, coordinators: data.coordinators } : prev);
          }
          break;

        case 'GROUP_KICKED':
          showToast(`⚠️ Bạn đã bị xóa khỏi nhóm`, 'warn');
          if (cur?.type === 'group' && data.groupId === cur.id) {
            setActiveChat(prev => prev ? { ...prev, kicked: true } : prev);
          }
          break;

        case 'GROUP_REMOVED':
          setGroups(prev => prev.filter(g => g.groupId !== data.groupId));
          if (cur?.type === 'group' && data.groupId === cur.id) {
            setActiveChat(null);
            setMessages([]);
          }
          break;

        case 'GROUP_DISBANDED':
          setGroups(prev => prev.filter(g => g.groupId !== data.groupId));
          if (cur?.type === 'group' && data.groupId === cur.id) {
            showToast('❌ Group has been disbanded', 'error');
            setActiveChat(null);
            setMessages([]);
          }
          break;

        default: break;
      }
    });
    wsRef.current = ws;
    return () => ws.close();
  }, [refreshData, showToast]);

  // ── Handlers ──────────────────────────────────────────────
  const handleSelectChat = useCallback((chat) => {
    setActiveChat(chat);
    loadHistory(chat);
  }, [loadHistory]);

  const handleSend = useCallback(async (content) => {
    if (!activeChat || !content.trim()) return;
    try {
      if (activeChat.type === 'peer') {
        const result = await sendMessage(activeChat.name, content);
        if (result.sent !== false) {
          setMessages(prev => [...prev, {
            sender: info.username, receiver: activeChat.name,
            content, timestamp: Date.now(), type: 'DIRECT_MESSAGE',
          }]);
        }
      } else if (activeChat.type === 'group') {
        await sendGroupMessage(activeChat.id, content);
        setMessages(prev => [...prev, {
          sender: info.username, groupId: activeChat.id,
          groupName: activeChat.name, content, timestamp: Date.now(), type: 'GROUP_MESSAGE',
        }]);
      } else if (activeChat.type === 'broadcast') {
        await sendBroadcast(content);
        setMessages(prev => [...prev, {
          sender: info.username, content, timestamp: Date.now(), type: 'BROADCAST',
        }]);
      }
    } catch (e) {
      setError('Send failed: ' + e.message);
      setTimeout(() => setError(''), 3000);
    }
  }, [activeChat, info.username]);

  const handleDiscover = useCallback(async () => {
    await discoverPeers();
    refreshData();
  }, [refreshData]);

  const handleCreateGroup = useCallback(async (groupName, members, groupMode) => {
    try {
      const result = await createGroup(groupName, members, groupMode);
      refreshData();
      return result;
    } catch (e) {
      showToast('Failed to create group: ' + e.message, 'error');
    }
  }, [refreshData, showToast]);

  const handleAddToGroup = useCallback(async (groupId, username) => {
    try {
      await addToGroup(groupId, username);
      refreshData();
    } catch (e) {
      showToast('Failed to add member: ' + e.message, 'error');
    }
  }, [refreshData, showToast]);

  const handleKickFromGroup = useCallback(async (groupId, target) => {
    await kickFromGroup(groupId, target);
    refreshData();
  }, [refreshData]);

  const handleLeaveGroup = useCallback(async (groupId) => {
    await leaveGroup(groupId, null);
    setGroups(prev => prev.filter(g => g.groupId !== groupId));
    if (activeChat?.id === groupId) { setActiveChat(null); setMessages([]); }
  }, [activeChat]);

  const handleDisbandGroup = useCallback(async (groupId) => {
    await disbandGroup(groupId);
    setGroups(prev => prev.filter(g => g.groupId !== groupId));
    if (activeChat?.id === groupId) { setActiveChat(null); setMessages([]); }
  }, [activeChat]);

  const handleRegistrationChange = useCallback((e) => {
    const { name, value } = e.target;
    setRegistrationForm(prev => ({ ...prev, [name]: value }));
  }, []);

  const handleRegister = useCallback(async (e) => {
    e.preventDefault();
    setRegistering(true); setError('');
    try {
      await registerPeer({
        username: registrationForm.username.trim(),
        host: registrationForm.host.trim(),
        bootstrapHost: registrationForm.bootstrapHost.trim(),
        bootstrapPort: Number(registrationForm.bootstrapPort),
      });
      await refreshData();
    } catch (err) {
      setError(err.message || 'Registration failed');
      setTimeout(() => setError(''), 4000);
    } finally {
      setRegistering(false);
    }
  }, [refreshData, registrationForm]);

  // ── Registration screen ───────────────────────────────────
  if (!info.registered) {
    return (
      <div className="registration-shell">
        <div className="registration-panel">
          <div className="registration-badge">P2PChat</div>
          <h1>Connect to Network</h1>
          <p className="registration-subtitle">
            Register this peer with the bootstrap server to start chatting.
          </p>
          <div className="registration-summary">
            <div><span className="summary-label">Peer Port</span><span className="summary-value">{info.peerPort || '-'}</span></div>
            <div><span className="summary-label">Web Port</span><span className="summary-value">{info.webPort || '-'}</span></div>
          </div>
          <form className="registration-form" onSubmit={handleRegister}>
            {[
              { label: 'Username', name: 'username', placeholder: 'alice' },
              { label: 'Peer Host', name: 'host', placeholder: 'localhost or LAN IP' },
              { label: 'Bootstrap Host', name: 'bootstrapHost', placeholder: 'bootstrap server host' },
            ].map(({ label, name, placeholder }) => (
              <label key={name}>
                <span>{label}</span>
                <input name={name} value={registrationForm[name]}
                  onChange={handleRegistrationChange} placeholder={placeholder} required />
              </label>
            ))}
            <label>
              <span>Bootstrap Port</span>
              <input name="bootstrapPort" type="number" min="1" max="65535"
                value={registrationForm.bootstrapPort}
                onChange={handleRegistrationChange} placeholder="9000" required />
            </label>
            <button type="submit" disabled={registering}>
              {registering ? 'Connecting...' : 'Connect Peer'}
            </button>
          </form>
          {info.lastBootstrapError && (
            <div className="registration-note">
              <strong>Error:</strong> {info.lastBootstrapError}
            </div>
          )}
          {error && <div className="error-toast">{error}</div>}
        </div>
      </div>
    );
  }

  // ── Chat UI ───────────────────────────────────────────────
  const activeChatGroup = activeChat?.type === 'group'
    ? groups.find(g => g.groupId === activeChat.id) : null;
  const isOwner = activeChatGroup?.owner === info.address;
  const isCoord = activeChatGroup?.coordinators?.includes(info.address);

  return (
    <div className="app">
      <Sidebar
        username={info.username}
        address={info.address}
        peers={peers}
        groups={groups}
        activeChat={activeChat}
        onSelectChat={handleSelectChat}
        onDiscover={handleDiscover}
        onCreateGroup={handleCreateGroup}
        onAddToGroup={handleAddToGroup}
        onKickFromGroup={handleKickFromGroup}
        onLeaveGroup={handleLeaveGroup}
        onDisbandGroup={handleDisbandGroup}
        isOwner={isOwner}
        isCoord={isCoord}
      />

      <div className="chat-container">
        {activeChat ? (
          <>
            <div className="chat-header">
              <span className="chat-header-icon">
                {activeChat.type === 'group' ? '#' : activeChat.type === 'broadcast' ? '📢' : '@'}
              </span>
              <span className="chat-header-name">{activeChat.name}</span>
              {activeChat.type === 'group' && activeChatGroup && (
                <span className="chat-header-meta">
                  {activeChatGroup.members?.length || 0} members ·{' '}
                  <span className={`group-mode-badge ${activeChatGroup.groupMode?.toLowerCase()}`}>
                    {activeChatGroup.groupMode || 'OPEN'}
                  </span>
                </span>
              )}
              {activeChat.kicked && (
                <span className="kicked-banner">⚠️ Bạn đã bị xóa khỏi nhóm này</span>
              )}
            </div>

            <ChatArea
              messages={messages}
              username={info.username}
              messagesEndRef={messagesEndRef}
            />

            <MessageInput
              onSend={handleSend}
              disabled={activeChat.kicked}
              activeChat={activeChat}
            />
          </>
        ) : (
          <div className="no-chat">
            <div className="no-chat-icon">💬</div>
            <h2>P2PChat</h2>
            <p>Select a peer or group to start chatting</p>
            <button className="broadcast-btn" onClick={() =>
              handleSelectChat({ type: 'broadcast', id: '__broadcast__', name: '# Broadcast' })
            }>
              📢 Open Broadcast Channel
            </button>
          </div>
        )}
        {error && <div className="error-toast">{error}</div>}
      </div>

      {/* Toast notifications */}
      {toast && (
        <div className={`global-toast toast-${toast.variant}`}>{toast.message}</div>
      )}
    </div>
  );
}
