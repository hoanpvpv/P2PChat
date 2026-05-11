import React, { useState, useEffect, useRef, useCallback } from 'react';
import {
  fetchInfo, fetchPeers, fetchGroups, fetchHistory, fetchGroupHistory,
  sendMessage, sendGroupMessage, sendBroadcast, createGroup, addToGroup,
  discoverPeers, connectWebSocket,
} from './api';
import Sidebar from './components/Sidebar';
import ChatArea from './components/ChatArea';
import MessageInput from './components/MessageInput';
import './App.css';

export default function App() {
  const [info, setInfo] = useState({ username: '' });
  const [peers, setPeers] = useState([]);
  const [groups, setGroups] = useState([]);
  const [activeChat, setActiveChat] = useState(null);
  const [messages, setMessages] = useState([]);
  const [error, setError] = useState('');
  const wsRef = useRef(null);
  const messagesEndRef = useRef(null);

  const scrollToBottom = () => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  };

  useEffect(() => {
    scrollToBottom();
  }, [messages]);

  const loadHistory = useCallback(async (chat) => {
    try {
      let msgs;
      if (chat.type === 'peer') {
        msgs = await fetchHistory(chat.name);
      } else {
        msgs = await fetchGroupHistory(chat.name);
      }
      setMessages(msgs);
    } catch (e) {
      console.error('Failed to load history:', e);
      setMessages([]);
    }
  }, []);

  const refreshData = useCallback(async () => {
    try {
      const [infoData, peersData, groupsData] = await Promise.all([
        fetchInfo(),
        fetchPeers(),
        fetchGroups(),
      ]);
      setInfo(infoData);
      setPeers(peersData || []);
      setGroups(groupsData || []);
    } catch (e) {
      console.error('Failed to refresh data:', e);
    }
  }, []);

  const activeChatRef = useRef(activeChat);
  useEffect(() => {
    activeChatRef.current = activeChat;
  }, [activeChat]);

  useEffect(() => {
    refreshData();
    wsRef.current = connectWebSocket((event) => {
      const { type, data } = event;
      const currentChat = activeChatRef.current;
      if (type === 'PEER_JOIN' || type === 'PEER_LEAVE') {
        refreshData();
      }
      if (type === 'DIRECT_MESSAGE' && currentChat?.type === 'peer') {
        if (data.sender === currentChat.name || data.receiver === currentChat.name) {
          setMessages((prev) => [...prev, data]);
        }
      }
      if (type === 'GROUP_MESSAGE' && currentChat?.type === 'group') {
        if (data.groupName === currentChat.name) {
          setMessages((prev) => [...prev, data]);
        }
      }
      if (type === 'BROADCAST') {
        setMessages((prev) => [...prev, { ...data, type: 'BROADCAST' }]);
      }
      if (type === 'DIRECT_MESSAGE' || type === 'GROUP_MESSAGE') {
        refreshData();
      }
    });
    return () => {
      wsRef.current?.stopReconnect();
      wsRef.current?.close();
    };
  }, [refreshData]);

  const handleSelectChat = useCallback((chat) => {
    setActiveChat(chat);
    loadHistory(chat);
  }, [loadHistory]);

  const handleSend = useCallback(async (content) => {
    if (!activeChat || !content.trim()) return;
    try {
      if (activeChat.type === 'peer') {
        const result = await sendMessage(activeChat.name, content);
        if (result.sent) {
          setMessages((prev) => [...prev, {
            sender: info.username,
            receiver: activeChat.name,
            content,
            timestamp: Date.now(),
            type: 'DIRECT_MESSAGE',
          }]);
        }
      } else if (activeChat.type === 'group') {
        await sendGroupMessage(activeChat.name, content);
        setMessages((prev) => [...prev, {
          sender: info.username,
          groupName: activeChat.name,
          content,
          timestamp: Date.now(),
          type: 'GROUP_MESSAGE',
        }]);
      }
    } catch (e) {
      setError('Failed to send message');
      setTimeout(() => setError(''), 3000);
    }
  }, [activeChat, info.username]);

  const handleDiscover = useCallback(async () => {
    await discoverPeers();
    refreshData();
  }, [refreshData]);

  const handleCreateGroup = useCallback(async (groupName) => {
    await createGroup(groupName);
    refreshData();
  }, [refreshData]);

  const handleAddToGroup = useCallback(async (groupName, username) => {
    await addToGroup(groupName, username);
    refreshData();
  }, [refreshData]);

  const handleBroadcast = useCallback(async (content) => {
    await sendBroadcast(content);
    setMessages((prev) => [...prev, {
      sender: info.username,
      content,
      timestamp: Date.now(),
      type: 'BROADCAST',
    }]);
  }, [info.username]);

  return (
    <div className="app">
      <Sidebar
        username={info.username}
        peers={peers}
        groups={groups}
        activeChat={activeChat}
        onSelectChat={handleSelectChat}
        onDiscover={handleDiscover}
        onCreateGroup={handleCreateGroup}
        onAddToGroup={handleAddToGroup}
      />
      <div className="chat-container">
        {activeChat ? (
          <>
            <div className="chat-header">
              <span className="chat-header-icon">
                {activeChat.type === 'group' ? '#' : '@'}
              </span>
              <span className="chat-header-name">{activeChat.name}</span>
              <span className="chat-header-type">
                {activeChat.type === 'group' ? 'Group' : 'Direct Message'}
              </span>
            </div>
            <ChatArea
              messages={messages}
              username={info.username}
              messagesEndRef={messagesEndRef}
            />
            <MessageInput
              onSend={handleSend}
              onBroadcast={handleBroadcast}
              activeChat={activeChat}
            />
          </>
        ) : (
          <div className="no-chat">
            <div className="no-chat-icon">💬</div>
            <h2>Welcome to P2PChat</h2>
            <p>Select a peer or group to start chatting</p>
          </div>
        )}
        {error && <div className="error-toast">{error}</div>}
      </div>
    </div>
  );
}
