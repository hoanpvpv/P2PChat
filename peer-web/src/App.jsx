import React, { useState, useEffect, useRef, useCallback } from 'react';
import {
  fetchInfo, fetchPeers, fetchGroups, fetchHistory, fetchGroupHistory, fetchBroadcastHistory,
  sendMessage, sendGroupMessage, sendBroadcast,
  createGroup, addToGroup, kickFromGroup, leaveGroup, disbandGroup,
  discoverPeers, connectWebSocket,
  getTransfers, offerFile, acceptFile, fetchOutbox
} from './api';
import Sidebar from './components/Sidebar';
import ChatArea from './components/ChatArea';
import MessageInput from './components/MessageInput';
import { useSound, useSoundEnabled, setSoundEnabled } from './hooks/useSound';
import './App.css';

export default function App() {
  const [info, setInfo] = useState({
    username: '', host: '', peerPort: '', webPort: '',
    bootstrapHost: '', bootstrapPort: '', initialized: false,
    registered: false, bootstrapConnected: false, lastBootstrapError: '',
  });
  const [infoLoaded, setInfoLoaded] = useState(false);
  const [peers, setPeers] = useState([]);
  const [groups, setGroups] = useState([]);
  const [activeChat, setActiveChat] = useState(null);   // { type:'peer'|'group'|'broadcast', id, name }
  const [messages, setMessages] = useState([]);
  const [transfers, setTransfers] = useState([]);
  const [unreadCounts, setUnreadCounts] = useState({}); // { id: count }
  const [typingUsers, setTypingUsers] = useState({}); // { sender: timeoutId }
  const [error, setError] = useState('');
  const [toast, setToast] = useState(null);             // { message, variant: 'info'|'warn'|'error' }
  const wsRef = useRef(null);
  const messagesEndRef = useRef(null);
  const activeChatRef = useRef(activeChat);
  const infoRef = useRef(info);
  const downloadedRefs = useRef(new Set());
  const playSound = useSound();
  const [soundEnabled, setSoundEnabledState] = useState(useSoundEnabled);

  const toggleSound = useCallback(() => {
    setSoundEnabledState(prev => {
      const next = !prev;
      setSoundEnabled(next);
      return next;
    });
  }, []);

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
  useEffect(() => { infoRef.current = info; }, [info]);
  useEffect(() => { messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' }); }, [messages]);

  const showToast = useCallback((message, variant = 'info') => {
    setToast({ message, variant });
    setTimeout(() => setToast(null), 4000);
  }, []);

  const refreshData = useCallback(async () => {
    try {
      const infoData = await fetchInfo();
      setInfo(infoData);
      setInfoLoaded(true);

      const [peersData, groupsData, transfersData] = await Promise.all([
        fetchPeers(), fetchGroups(), getTransfers()
      ]);
      setPeers(peersData || []);
      setGroups(groupsData || []);
      setTransfers(transfersData || []);
    } catch (e) {
      console.error('Failed to refresh data:', e);
      setInfoLoaded(true);
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
          const outboxById = new Map((outbox || []).map(e => [e.messageId, e]));
          msgs = msgs.map(m => outboxById.has(m.messageId)
            ? {
                ...m,
                deliveryState: outboxById.get(m.messageId).state,
                failureCode: outboxById.get(m.messageId).failureCode,
                deliveryError: outboxById.get(m.messageId).lastError,
              }
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
    refreshData();
    const ws = connectWebSocket((event) => {
      const { type, data } = event;
      const cur = activeChatRef.current;

      switch (type) {
        case 'PEER_JOIN':
          refreshData();
          playSound('join');
          break;
        case 'PEER_LEAVE':
          refreshData();
          break;

        case 'DIRECT_MESSAGE':
          if (cur?.type === 'peer' && (data.sender === cur.name || data.receiver === cur.name)) {
            appendUniqueMessage(data);
          } else {
            const senderName = data.sender.split(':')[0];
            setUnreadCounts(prev => ({ ...prev, [senderName]: (prev[senderName] || 0) + 1 }));
            showToast(`✉️ Message from ${senderName}`, 'info');
            if (data.sender !== infoRef.current.username) {
              playSound('mention');
            }
          }
          break;

        case 'GROUP_MESSAGE':
          if (cur?.type === 'group' && data.groupId === cur.id) {
            appendUniqueMessage(data);
          } else {
            setUnreadCounts(prev => ({ ...prev, [data.groupId]: (prev[data.groupId] || 0) + 1 }));
            showToast(`👥 Message in group`, 'info');
            if (data.sender !== infoRef.current.username) {
              playSound('mention');
            }
          }
          break;

        case 'OUTBOX_UPDATE':
          setMessages(prev => prev.map(m =>
            m.messageId === data.messageId
              ? { ...m, deliveryState: data.state, failureCode: data.failureCode, deliveryError: data.error }
              : m
          ));
          break;

        case 'BROADCAST':
          if (cur?.type === 'broadcast') {
            appendUniqueMessage({ ...data, type: 'BROADCAST' });
          } else {
            setUnreadCounts(prev => ({ ...prev, 'broadcast': (prev['broadcast'] || 0) + 1 }));
            showToast(`📢 ${data.sender}: ${data.content}`, 'info');
            if (data.sender !== infoRef.current.username) {
              playSound('mention');
            }
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

  useEffect(() => {
    if (infoLoaded && !info.initialized) {
      window.location.replace('http://localhost:9200');
    }
  }, [infoLoaded, info.initialized]);

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
        let latestOutbox = null;
        try {
          const outbox = await fetchOutbox();
          latestOutbox = (outbox || []).find(e => e.messageId === result.messageId);
        } catch (e) { /* best-effort */ }
        setMessages(prev => prev.map(msg => msg.messageId === tempId
          ? {
              ...msg,
              messageId: result.messageId || tempId,
              timestamp: result.timestamp || timestamp,
              deliveryState: latestOutbox?.state || result.status || 'DELIVERED',
              failureCode: latestOutbox?.failureCode,
              deliveryError: latestOutbox?.lastError,
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
    try {
      await kickFromGroup(groupId, target);
      refreshData();
    } catch (e) {
      showToast('Failed to kick member: ' + e.message, 'error');
    }
  }, [refreshData, showToast]);

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

  if (!infoLoaded || !info.initialized) return null;

  // ── Chat UI ───────────────────────────────────────────────
  const activeChatGroup = activeChat?.type === 'group'
    ? groups.find(g => g.groupId === activeChat.id) : null;
  const isOwner = activeChatGroup?.owner === info.username;
  const isCoord = activeChatGroup?.coordinators?.includes(info.username);

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
        soundEnabled={soundEnabled}
        onToggleSound={toggleSound}
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
