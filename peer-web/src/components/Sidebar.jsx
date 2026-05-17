import React, { useState } from 'react';

export default function Sidebar({
  username, address, peers, groups, activeChat,
  onSelectChat, onDiscover, onCreateGroup, onAddToGroup,
  onKickFromGroup, onLeaveGroup, onDisbandGroup,
  isOwner, isCoord,
}) {
  const [showGroupForm, setShowGroupForm] = useState(false);
  const [groupName, setGroupName] = useState('');
  const [groupMode, setGroupMode] = useState('OPEN');
  const [selectedMembers, setSelectedMembers] = useState([]);

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
        <div className="sidebar-logo">P2PChat</div>
        <div className="sidebar-user">
          <div className="user-avatar">{username?.charAt(0)?.toUpperCase() || '?'}</div>
          <div className="user-info">
            <div className="user-name">{username}</div>
            <div className="user-status online">● online</div>
          </div>
        </div>
      </div>

      {/* Broadcast shortcut */}
      <div
        className={`chat-item broadcast-item ${activeChat?.type === 'broadcast' ? 'active' : ''}`}
        onClick={() => onSelectChat({ type: 'broadcast', id: '__broadcast__', name: '# Broadcast' })}
      >
        <div className="chat-item-icon broadcast-icon">📢</div>
        <div className="chat-item-info">
          <div className="chat-item-name"># Broadcast</div>
          <div className="chat-item-status">All peers</div>
        </div>
      </div>

      {/* Peers section */}
      <div className="sidebar-section">
        <span className="section-title">Peers</span>
        <button className="section-btn" onClick={onDiscover} title="Refresh peer list">↻</button>
      </div>
      <div className="sidebar-list">
        {(peers || []).map(peer => (
          <div
            key={peer.username}
            className={`chat-item ${activePeerId === peer.username ? 'active' : ''}`}
            onClick={() => onSelectChat({ type: 'peer', id: peer.username, name: peer.username })}
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

      <div className="sidebar-list">
        {(groups || []).map(group => {
          const isActive = activeGroupId === group.groupId;
          const iAmOwner = group.owner === address;
          const iAmCoord = group.coordinators?.includes(address);

          return (
            <div key={group.groupId} className={`group-item-wrapper ${group.groupState === 'LEAVING' ? 'leaving' : ''}`}>
              <div
                className={`chat-item ${isActive ? 'active' : ''}`}
                onClick={() => onSelectChat({
                  type: 'group', id: group.groupId, name: group.groupName,
                  members: group.members, coordinators: group.coordinators,
                })}
              >
                <div className="chat-item-icon group-icon">#</div>
                <div className="chat-item-info">
                  <div className="chat-item-name">
                    {group.groupName}
                    {iAmOwner && <span className="role-badge owner" title="Owner">👑</span>}
                    {!iAmOwner && iAmCoord && <span className="role-badge coord" title="Coordinator">●</span>}
                  </div>
                  <div className="chat-item-status">
                    {group.members?.length || 0} members
                    {' · '}
                    <span className={`mode-tag ${group.groupMode?.toLowerCase() || 'open'}`}>
                      {group.groupMode || 'OPEN'}
                    </span>
                  </div>
                </div>
              </div>

              {/* Expanded panel for active group */}
              {isActive && (
                <div className="group-panel">
                  {/* Members list */}
                  <div className="group-members-title">Members</div>
                  <div className="group-members-list">
                    {(group.members || []).map(addr => {
                      const peerName = peers.find(p => p.host + ':' + p.port === addr)?.username || addr;
                      const mIsOwner = addr === group.owner;
                      const mIsCoord = group.coordinators?.includes(addr);
                      return (
                        <div key={addr} className="member-row">
                          <span className="member-name">{peerName}</span>
                          {mIsOwner && <span className="role-badge owner" title="Owner">👑</span>}
                          {!mIsOwner && mIsCoord && <span className="role-badge coord">C</span>}
                          {iAmOwner && addr !== address && (
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
                  {(group.groupMode === 'OPEN' || iAmOwner || iAmCoord) && (
                    <AddMemberRow
                      groupId={group.groupId}
                      existingAddrs={group.members || []}
                      peers={peers}
                      onAdd={onAddToGroup}
                    />
                  )}

                  {/* Group actions */}
                  <div className="group-actions">
                    <button className="btn-leave" onClick={() => onLeaveGroup(group.groupId)}>
                      Leave Group
                    </button>
                    {iAmOwner && (
                      <button className="btn-disband" onClick={() => onDisbandGroup(group.groupId)}>
                        Disband
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
  const [selected, setSelected] = useState('');
  const available = peers.filter(p =>
    !existingAddrs.some(addr => addr.includes(p.host) && addr.includes(String(p.port)))
  );
  if (!available.length) return null;
  return (
    <div className="add-member-row">
      <select value={selected} onChange={e => setSelected(e.target.value)}>
        <option value="" disabled>Add member...</option>
        {available.map(p => (
          <option key={p.username} value={p.username}>{p.username}</option>
        ))}
      </select>
      <button
        className="btn-add-member"
        onClick={() => { if (selected) { onAdd(groupId, selected); setSelected(''); } }}
      >+</button>
    </div>
  );
}
