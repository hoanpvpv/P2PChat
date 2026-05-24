import React, { useState, useEffect, useRef, useCallback } from 'react';
import {
  fetchInfo, fetchPeers, fetchGroups, fetchHistory, fetchGroupHistory, fetchBroadcastHistory,
  sendMessage, sendGroupMessage, sendBroadcast,
  createGroup, addToGroup, kickFromGroup, leaveGroup, disbandGroup,
  discoverPeers, connectWebSocket, autoDetectHost, initPeer,
  getTransfers, offerFile, acceptFile, fetchOutbox
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
  const [transfers, setTransfers] = useState([]);
  const [unreadCounts, setUnreadCounts] = useState({}); // { id: count }
  const [typingUsers, setTypingUsers] = useState({}); // { sender: timeoutId }
  const [error, setError] = useState('');
  const [toast, setToast] = useState(null);             // { message, variant: 'info'|'warn'|'error' }
  const [initing, setIniting] = useState(false);
  const [setupForm, setSetupForm] = useState({
    username: '',
    peerPort: '',
  });
  const [detectedHost, setDetectedHost] = useState('');
  const wsRef = useRef(null);
  const messagesEndRef = useRef(null);
  const activeChatRef = useRef(activeChat);
  const downloadedRefs = useRef(new Set());

  const appendUniqueMessage = useCallback((message) => {
    if (!message) return;
    setMessages(prev => {
      if (message.messageId && prev.some(m => m.messageId === message.messageId)) return prev;
      const optimisticIndex = prev.findIndex(m =>
        m.messageId?.startsWith('local-') &&
        m.sender === message.sender &&
        m.content === message.content &&
        (m.type || message.type) === (message.type || m.type) &&
        (m.receiver || '') === (message.receiver || '') &&
        (m.groupId || '') === (message.groupId || '') &&
        Math.abs((m.timestamp || 0) - (message.timestamp || Date.now())) < 10000
      );
      if (optimisticIndex >= 0) {
        const next = [...prev];
        next[optimisticIndex] = { ...prev[optimisticIndex], ...message };
        return next;
      }
      return [...prev, message];
    });
  }, []);

  useEffect(() => { activeChatRef.current = activeChat; }, [activeChat]);
  useEffect(() => { messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' }); }, [messages]);

  const showToast = useCallback((message, variant = 'info') => {
    setToast({ message, variant });
    setTimeout(() => setToast(null), 4000);
  }, []);

  const refreshData = useCallback(async () => {
    try {
      const [infoData, peersData, groupsData, transfersData] = await Promise.all([
        fetchInfo(), fetchPeers(), fetchGroups(), getTransfers()
      ]);
      setInfo(infoData);
      setSetupForm((prev) => ({
        username: prev.username || infoData.username || '',
        peerPort: prev.peerPort || String(infoData.peerPort || ''),
      }));
      setPeers(peersData || []);
      setGroups(groupsData || []);
      setTransfers(transfersData || []);
    } catch (e) {
      console.error('Failed to refresh data:', e);
    }
  }, []);

  // ── Chat history ──────────────────────────────────────────
  const loadHistory = useCallback(async (chat) => {
    try {
      let msgs;
      if (chat.type === 'broadcast') {
        msgs = await fetchBroadcastHistory();
      } else if (chat.type === 'peer') {
        msgs = await fetchHistory(chat.name);
      } else {
        msgs = await fetchGroupHistory(chat.id);
      }
      msgs = msgs || [];
      if (chat.type === 'peer') {
        try {
          const outbox = await fetchOutbox();
          const stateById = new Map((outbox || []).map(e => [e.messageId, e.state]));
          msgs = msgs.map(m => stateById.has(m.messageId)
            ? { ...m, deliveryState: stateById.get(m.messageId) }
            : m);
        } catch (e) { /* best-effort */ }
      }
      setMessages(msgs);
    } catch (e) {
      console.error('History load failed:', e);
      setMessages([]);
    }
  }, []);

  // ── WebSocket ──────────────────────────────────────────────
  useEffect(() => {
    autoDetectHost()
      .then((data) => setDetectedHost(data.host || 'localhost'))
      .catch(() => setDetectedHost('localhost'));

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
            appendUniqueMessage(data);
          } else {
            const senderName = data.sender.split(':')[0]; // get username
            setUnreadCounts(prev => ({ ...prev, [senderName]: (prev[senderName] || 0) + 1 }));
            showToast(`✉️ Message from ${senderName}`, 'info');
          }
          break;

        case 'GROUP_MESSAGE':
          if (cur?.type === 'group' && data.groupId === cur.id) {
            appendUniqueMessage(data);
          } else {
            setUnreadCounts(prev => ({ ...prev, [data.groupId]: (prev[data.groupId] || 0) + 1 }));
            showToast(`👥 Message in group`, 'info');
          }
          break;

        case 'OUTBOX_UPDATE':
          setMessages(prev => prev.map(m =>
            m.messageId === data.messageId ? { ...m, deliveryState: data.state } : m
          ));
          break;

        case 'BROADCAST':
          if (cur?.type === 'broadcast') {
            appendUniqueMessage({ ...data, type: 'BROADCAST' });
          } else {
            setUnreadCounts(prev => ({ ...prev, 'broadcast': (prev['broadcast'] || 0) + 1 }));
            showToast(`📢 ${data.sender}: ${data.content}`, 'info');
          }
          break;
          
        

        case 'GROUP_JOINED':
          refreshData();
          showToast(`✅ Joined group: ${data.groupName}`, 'info');
          break;

        case 'GROUP_UPDATED':
          setGroups(prev => prev.map(g => {
            if (g.groupId === data.groupId) {
              const updated = { ...g, ...data };
              if (data.members && data.members.length > 0) {
                 updated.owner = data.members[0];
              }
              return updated;
            }
            return g;
          }));
          if (cur?.type === 'group' && data.groupId === cur.id) {
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

        case 'FILE_OFFER_SENT':
          refreshData();
          if (cur) loadHistory(cur);
          break;
        case 'FILE_OFFER_RECEIVED': {
          refreshData();
          const chatKey = data.groupId || (data.sender ? data.sender : ''); // sender here is username from FileTransferManager
          if (cur && (cur.id === chatKey || cur.name === chatKey)) {
            loadHistory(cur);
          } else if (chatKey) {
            setUnreadCounts(prev => ({ ...prev, [chatKey]: (prev[chatKey] || 0) + 1 }));
            showToast(`📁 Bạn có file gửi đến!`, 'info');
          }
          break;
        }

        case 'FILE_ACCEPTED':
        case 'FILE_REJECTED':
        case 'FILE_DONE':
          refreshData();
          if (type === 'FILE_DONE') showToast(`✅ Transfer ${data.transferId} complete!`, 'info');
          break;

        case 'FILE_PROGRESS':
          setTransfers(prev => {
             const existing = prev.find(t => t.transferId === data.transferId);
             if (existing) {
               return prev.map(t => t.transferId === data.transferId ? { ...t, ...data } : t);
             }
             return [...prev, data];
          });
          
          if (data.status === 'DONE' && !downloadedRefs.current.has(data.transferId)) {
            downloadedRefs.current.add(data.transferId);
            setTimeout(() => {
                 const a = document.createElement('a');
                 a.href = `/api/file/download/${data.transferId}`;
                 a.download = data.filename || '';
                 document.body.appendChild(a);
                 a.click();
                 document.body.removeChild(a);
            }, 500);
          }
          break;

        default: break;
      }
    });
    wsRef.current = ws;
    return () => ws.close();
  }, [appendUniqueMessage, refreshData, showToast]);

  // ── Handlers ──────────────────────────────────────────────
  const handleSelectChat = useCallback((chat) => {
    setActiveChat(chat);
    const unreadKey = chat.type === 'broadcast' ? 'broadcast' : (chat.id || chat.name);
    setUnreadCounts(prev => ({ ...prev, [unreadKey]: 0 }));
        loadHistory(chat);
  }, [loadHistory]);

  const handleSend = useCallback(async (content) => {
    if (!activeChat || !content.trim()) return;
    const tempId = `local-${Date.now()}-${Math.random().toString(16).slice(2)}`;
    const timestamp = Date.now();
    const optimisticMessage = {
      messageId: tempId,
      sender: info.username,
      receiver: activeChat.type === 'peer' ? activeChat.name : undefined,
      groupId: activeChat.type === 'group' ? activeChat.id : undefined,
      groupName: activeChat.type === 'group' ? activeChat.name : undefined,
      content,
      timestamp,
      type: activeChat.type === 'peer'
        ? 'DIRECT_MESSAGE'
        : activeChat.type === 'group'
          ? 'GROUP_MESSAGE'
          : 'BROADCAST',
      deliveryState: activeChat.type === 'peer' ? 'PENDING_LOCAL' : undefined,
    };
    appendUniqueMessage(optimisticMessage);

    try {
      if (activeChat.type === 'peer') {
        const result = await sendMessage(activeChat.name, content);
        setMessages(prev => prev.map(msg => msg.messageId === tempId
          ? {
              ...msg,
              messageId: result.messageId || tempId,
              timestamp: result.timestamp || timestamp,
              deliveryState: result.status || 'DELIVERED',
            }
          : msg));
      } else if (activeChat.type === 'group') {
        sendGroupMessage(activeChat.id, content).catch((e) => {
          showToast('Group send is retrying/fallback: ' + e.message, 'warn');
        });
      } else if (activeChat.type === 'broadcast') {
        sendBroadcast(content).catch((e) => {
          showToast('Broadcast failed: ' + e.message, 'error');
        });
      }
    } catch (e) {
      setMessages(prev => prev.map(msg => msg.messageId === tempId
        ? { ...msg, deliveryState: 'FAILED_RETRYABLE' }
        : msg));
      setError('Send failed: ' + e.message);
      setTimeout(() => setError(''), 3000);
    }
  }, [activeChat, appendUniqueMessage, info.username, showToast]);

  const handleSendFile = useCallback(async (file) => {
    if (!activeChat) return;
    try {
      if (activeChat.type === 'peer') {
        await offerFile(file, activeChat.name, null);
      } else if (activeChat.type === 'group') {
        await offerFile(file, null, activeChat.id);
      }
      showToast(`Uploading ${file.name}...`, 'info');
    } catch (e) {
      showToast('File offer failed: ' + e.message, 'error');
    }
  }, [activeChat, showToast]);

  

  const handleAcceptTransfer = useCallback(async (transferId) => {
    try {
      await acceptFile(transferId);
      showToast('Starting download...', 'info');
    } catch (e) {
      showToast('Download failed: ' + e.message, 'error');
    }
  }, [showToast]);

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

  const handleSetupChange = useCallback((event) => {
    const { name, value } = event.target;
    setSetupForm((prev) => ({ ...prev, [name]: value }));
  }, []);

  const handleSetup = useCallback(async (event) => {
    event.preventDefault();
    setIniting(true);
    setError('');
    try {
      await initPeer(setupForm.username.trim(), Number(setupForm.peerPort));
      await refreshData();
    } catch (e) {
      setError(e.message || 'Failed to initialize peer');
      setTimeout(() => setError(''), 4000);
    } finally {
      setIniting(false);
    }
  }, [refreshData, setupForm.username, setupForm.peerPort]);

  // ── Registration screen ───────────────────────────────────
  if (!info.registered) {
    return (
      <div className="registration-shell">
        <div className="registration-panel">
          <div className="registration-badge">Peer Setup</div>
          <h1>Configure your peer node.</h1>
          <p className="registration-subtitle">
            Enter a username and choose a port for this peer. Your local IP will be
            detected automatically. Once started, this peer will connect to the
            bootstrap server on the default port.
          </p>
          <div className="registration-summary">
            <div>
              <span className="summary-label">Detected Host</span>
              <span className="summary-value host-value">{detectedHost || 'detecting...'}</span>
            </div>
            <div>
              <span className="summary-label">Web Port</span>
              <span className="summary-value">{info.webPort || '-'}</span>
            </div>
            <div>
              <span className="summary-label">Bootstrap Server</span>
              <span className="summary-value">localhost:{info.bootstrapPort || 8080}</span>
            </div>
          </div>

          <form className="registration-form" onSubmit={handleSetup}>
            <label>
              <span>Username <span className="required">*</span></span>
              <input
                name="username"
                value={setupForm.username}
                onChange={handleSetupChange}
                placeholder="e.g. alice"
                required
                autoFocus
              />
            </label>
            <label>
              <span>Peer Port <span className="required">*</span></span>
              <input
                name="peerPort"
                type="number"
                min="1024"
                max="65535"
                value={setupForm.peerPort}
                onChange={handleSetupChange}
                placeholder="5001"
                required
              />
            </label>
            <label>
              <span>Host (auto-detected)</span>
              <input
                type="text"
                value={detectedHost || ''}
                readOnly
                className="input-readonly"
              />
            </label>
            <button type="submit" disabled={initing}>
              {initing ? 'Starting...' : 'Start Peer'}
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
        address={`${info.host}:${info.peerPort}`}
        peers={peers}
        groups={groups}
        activeChat={activeChat}
        unreadCounts={unreadCounts}
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
              onDownload={handleAcceptTransfer}
              transfers={transfers}
              activeChatGroup={activeChatGroup}
              peers={peers}
            />

            

            <MessageInput
              onSend={handleSend}
              onSendFile={handleSendFile}
              
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
