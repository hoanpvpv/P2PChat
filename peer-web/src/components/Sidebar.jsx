import React, { useState } from 'react';
import { shutdownPeer } from '../api';

export default function Sidebar({
  username, address, peers, groups, activeChat, unreadCounts = {},
  onSelectChat, onDiscover, onCreateGroup, onAddToGroup,
  onKickFromGroup, onLeaveGroup, onDisbandGroup,
  isOwner, isCoord,
}) {
  const [shuttingDown, setShuttingDown] = useState(false);

  const handlePowerOff = async () => {
    const ok = window.confirm(
      `Tắt nguồn peer "${username}"?\n\n` +
      `Container sẽ exit ngay (mô phỏng người dùng off đột ngột).\n` +
      `Volume data/peers/${username} vẫn còn — chạy "docker start peer-${username}" để bật lại.`
    );
    if (!ok) return;
    setShuttingDown(true);
    try { await shutdownPeer(); } catch (e) { /* expected: connection drops */ }
  };
  const [showGroupForm, setShowGroupForm] = useState(false);
  const [groupName, setGroupName] = useState('');
  const [groupMode, setGroupMode] = useState('OPEN');
  const [selectedMembers, setSelectedMembers] = useState([]);
  const [showAddMember, setShowAddMember] = useState(false);
  
  const [peerSearch, setPeerSearch] = useState('');
  const [groupSearch, setGroupSearch] = useState('');

  const handleSelectChatLocal = (chat) => {
    setShowAddMember(false);
    onSelectChat(chat);
  };

  const handleCreateGroup = (e) => {
    e.preventDefault();
    if (!groupName.trim()) return;
    onCreateGroup(groupName.trim(), selectedMembers, groupMode);
    setGroupName(''); setGroupMode('OPEN'); setSelectedMembers([]); setShowGroupForm(false);
  };

  const toggleMember = (username) => {
    setSelectedMembers(prev =>
      prev.includes(username) ? prev.filter(m => m !== username) : [...prev, username]
    );
  };

  const activePeerId = activeChat?.type === 'peer' ? activeChat.name : null;
  const activeGroupId = activeChat?.type === 'group' ? activeChat.id : null;

  return (
    <div className="sidebar">
      {/* Header */}
      <div className="sidebar-header">
        <div className="sidebar-logo">
          <span>P2PChat</span>
          <button
            type="button"
            className="power-off-btn"
            onClick={handlePowerOff}
            disabled={shuttingDown}
            title="Tắt nguồn peer (mô phỏng off đột ngột)"
          >
            {shuttingDown ? '...' : '⏻'}
          </button>
        </div>
        <div className="sidebar-user">
          <div className="user-avatar">{username?.charAt(0)?.toUpperCase() || '?'}</div>
          <div className="user-info">
            <div className="user-name">{username}</div>
            <div className={`user-status ${shuttingDown ? 'offline' : 'online'}`}>
              {shuttingDown ? '● shutting down' : '● online'}
            </div>
          </div>
        </div>
      </div>

      {/* Broadcast shortcut */}
      <div
        className={`chat-item broadcast-item ${activeChat?.type === 'broadcast' ? 'active' : ''}`}
        onClick={() => handleSelectChatLocal({ type: 'broadcast', id: '__broadcast__', name: '# Broadcast' })}
      >
        <div className="chat-item-icon broadcast-icon">📢</div>
        <div className="chat-item-info">
          <div className="chat-item-name"># Broadcast</div>
          <div className="chat-item-status">All peers</div>
        </div>
        {unreadCounts['broadcast'] > 0 && (
          <div className="unread-badge">{unreadCounts['broadcast'] > 5 ? '5+' : unreadCounts['broadcast']}</div>
        )}
      </div>

      {/* Peers section */}
      <div className="sidebar-section">
        <span className="section-title">Peers</span>
        <button className="section-btn" onClick={onDiscover} title="Refresh peer list">↻</button>
      </div>
      <div className="search-container">
        <input 
          type="text" 
          className="search-input" 
          placeholder="Search peers..." 
          value={peerSearch}
          onChange={e => setPeerSearch(e.target.value)}
        />
      </div>
      <div className="sidebar-list">
        {(peers || []).filter(p => p.username.toLowerCase().includes(peerSearch.toLowerCase())).map(peer => (
          <div
            key={peer.username}
            className={`chat-item ${activePeerId === peer.username ? 'active' : ''}`}
            onClick={() => handleSelectChatLocal({ type: 'peer', id: peer.username, name: peer.username })}
          >
            <div className="chat-item-icon">
              {peer.username.charAt(0).toUpperCase()}
            </div>
            <div className="chat-item-info">
              <div className="chat-item-name">{peer.username}</div>
              <div className={`chat-item-status ${peer.online ? '' : 'offline'}`}>
                {peer.online ? '● online' : '○ offline'}
              </div>
            </div>
            {unreadCounts[peer.username] > 0 && (
              <div className="unread-badge">{unreadCounts[peer.username] > 5 ? '5+' : unreadCounts[peer.username]}</div>
            )}
          </div>
        ))}
        {!peers?.length && (
          <div className="empty-list">No peers online. Click ↻</div>
        )}
      </div>

      {/* Groups section */}
      <div className="sidebar-section">
        <span className="section-title">Groups</span>
        <button className="section-btn" onClick={() => setShowGroupForm(!showGroupForm)} title="New group">
          {showGroupForm ? '✕' : '+'}
        </button>
      </div>

      {/* Create group form */}
      {showGroupForm && (
        <form className="group-form" onSubmit={handleCreateGroup}>
          <input
            className="group-form-input"
            placeholder="Group name"
            value={groupName}
            onChange={e => setGroupName(e.target.value)}
            autoFocus
          />
          <div className="group-mode-row">
            <label>
              <input type="radio" name="gmode" value="OPEN" checked={groupMode === 'OPEN'}
                onChange={() => setGroupMode('OPEN')} /> Open
            </label>
            <label>
              <input type="radio" name="gmode" value="RESTRICTED" checked={groupMode === 'RESTRICTED'}
                onChange={() => setGroupMode('RESTRICTED')} /> Restricted
            </label>
          </div>
          {peers?.length > 0 && (
            <div className="member-select-list">
              <div className="member-select-label">Add members:</div>
              {peers.map(p => (
                <label key={p.username} className="member-checkbox">
                  <input type="checkbox" checked={selectedMembers.includes(p.username)}
                    onChange={() => toggleMember(p.username)} />
                  {p.username}
                </label>
              ))}
            </div>
          )}
          <div className="group-form-actions">
            <button type="submit" className="btn-primary">Create</button>
            <button type="button" className="btn-secondary" onClick={() => setShowGroupForm(false)}>Cancel</button>
          </div>
        </form>
      )}

      <div className="search-container">
        <input 
          type="text" 
          className="search-input" 
          placeholder="Search groups..." 
          value={groupSearch}
          onChange={e => setGroupSearch(e.target.value)}
        />
      </div>
      <div className="sidebar-list">
        {(groups || []).filter(g => g.groupName?.toLowerCase().includes(groupSearch.toLowerCase())).map(group => {
          const isActive = activeGroupId === group.groupId;
          const iAmOwner = group.owner === username;
          const iAmCoord = group.coordinators?.includes(username);

          return (
            <div key={group.groupId} className={`group-item-wrapper ${group.groupState === 'LEAVING' ? 'leaving' : ''}`}>
              <div
                className={`chat-item ${isActive ? 'active' : ''}`}
                onClick={() => handleSelectChatLocal({
                  type: 'group', id: group.groupId, name: group.groupName,
                  members: group.members, coordinators: group.coordinators,
                })}
              >
                <div className="chat-item-icon group-icon">#</div>
                <div className="chat-item-info">
                  <div className="chat-item-name">
                    {group.groupName}
                    {iAmOwner && <span className="role-badge owner" title="Owner">👑</span>}
                    {iAmCoord && <span className="role-badge coord" title="Coordinator">c</span>}
                  </div>
                  <div className="chat-item-status">
                    {group.members?.length || 0} members
                    {' · '}
                    <span className={`mode-tag ${group.groupMode?.toLowerCase() || 'open'}`}>
                      {group.groupMode || 'OPEN'}
                    </span>
                  </div>
                </div>
                {unreadCounts[group.groupId] > 0 && (
                  <div className="unread-badge">{unreadCounts[group.groupId] > 5 ? '5+' : unreadCounts[group.groupId]}</div>
                )}
              </div>

              {/* Expanded panel for active group */}
              {isActive && (
                <div className="group-panel">
                  {/* Members list */}
                  <div className="group-members-title">Members</div>
                  <div className="group-members-list">
                    {(group.members || []).map(addr => {
                      const peerName = addr; // now a username
                      const mIsOwner = addr === group.owner;
                      const mIsCoord = group.coordinators?.includes(addr);
                      return (
                        <div key={addr} className="member-row">
                          <span className="member-name">{peerName}</span>
                          {mIsOwner && <span className="role-badge owner" title="Owner">👑</span>}
                          {mIsCoord && <span className="role-badge coord" title="Coordinator">c</span>}
                          {iAmOwner && addr !== username && (
                            <button className="btn-kick" title="Kick"
                              onClick={e => { e.stopPropagation(); onKickFromGroup(group.groupId, peerName); }}>
                              ✕
                            </button>
                          )}
                        </div>
                      );
                    })}
                  </div>

                  {/* Add member (OPEN mode or owner/coord in RESTRICTED) */}
                  {showAddMember && (group.groupMode === 'OPEN' || iAmOwner || iAmCoord) && (
                    <AddMemberRow
                      groupId={group.groupId}
                      existingAddrs={group.members || []}
                      peers={peers}
                      onAdd={(g, p) => { onAddToGroup(g, p); setShowAddMember(false); }}
                    />
                  )}

                  {/* Group actions */}
                  <div className="group-actions">
                    <button className="btn-leave" onClick={() => onLeaveGroup(group.groupId)}>
                      Leave
                    </button>
                    {iAmOwner && (
                      <button className="btn-disband" onClick={() => onDisbandGroup(group.groupId)}>
                        Disband
                      </button>
                    )}
                    {(group.groupMode === 'OPEN' || iAmOwner || iAmCoord) && (
                      <button className="btn-add-member-toggle" style={{ marginLeft: 'auto' }} onClick={() => setShowAddMember(!showAddMember)}>
                        {showAddMember ? '✕' : '+'}
                      </button>
                    )}
                  </div>
                </div>
              )}
            </div>
          );
        })}
        {!groups?.length && (
          <div className="empty-list">No groups yet. Click + to create one.</div>
        )}
      </div>
    </div>
  );
}

function AddMemberRow({ groupId, existingAddrs, peers, onAdd }) {
  const [search, setSearch] = useState('');
  const available = peers.filter(p => !existingAddrs.includes(p.username));
  if (!available.length) return null;

  const filtered = available.filter(p => p.username.toLowerCase().includes(search.toLowerCase()));

  return (
    <div className="add-member-container">
      <input 
        type="text"
        className="search-input member-search" 
        placeholder="Search member to add..." 
        value={search} 
        onChange={e => setSearch(e.target.value)} 
      />
      <div className="add-member-list">
        {filtered.map(p => (
           <div key={p.username} className="add-member-item">
             <span>{p.username}</span>
             <button className="btn-add-member" onClick={() => { onAdd(groupId, p.username); setSearch(''); }}>+</button>
           </div>
        ))}
      </div>
    </div>
  );
}
