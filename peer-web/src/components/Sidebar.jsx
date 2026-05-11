import React, { useState } from 'react';

export default function Sidebar({
  username, peers, groups, activeChat, onSelectChat,
  onDiscover, onCreateGroup, onAddToGroup,
}) {
  const [newGroup, setNewGroup] = useState('');
  const [showGroupForm, setShowGroupForm] = useState(false);

  const handleCreateGroup = (e) => {
    e.preventDefault();
    if (newGroup.trim()) {
      onCreateGroup(newGroup.trim());
      setNewGroup('');
      setShowGroupForm(false);
    }
  };

  return (
    <div className="sidebar">
      <div className="sidebar-header">
        <h1>P2PChat</h1>
        <div className="username">{username}</div>
      </div>

      <div className="sidebar-section">
        <h3>Peers</h3>
        <button onClick={onDiscover}>Refresh</button>
      </div>
      <div className="sidebar-list">
        {(peers || []).map((peer) => (
          <div
            key={peer.username}
            className={`chat-item ${activeChat?.type === 'peer' && activeChat?.name === peer.username ? 'active' : ''}`}
            onClick={() => onSelectChat({ type: 'peer', name: peer.username })}
          >
            <div className="chat-item-icon">
              {peer.username.charAt(0).toUpperCase()}
            </div>
            <div className="chat-item-info">
              <div className="chat-item-name">{peer.username}</div>
              <div className={`chat-item-status ${peer.online ? '' : 'offline'}`}>
                {peer.online ? 'online' : 'offline'}
              </div>
            </div>
          </div>
        ))}
        {(!peers || peers.length === 0) && (
          <div style={{ padding: '12px 16px', color: '#666', fontSize: '13px' }}>
            No peers found. Click Refresh.
          </div>
        )}
      </div>

      <div className="sidebar-section">
        <h3>Groups</h3>
        <button onClick={() => setShowGroupForm(!showGroupForm)}>
          {showGroupForm ? 'Cancel' : '+ New'}
        </button>
      </div>

      {showGroupForm && (
        <form className="group-form" onSubmit={handleCreateGroup}>
          <input
            placeholder="Group name"
            value={newGroup}
            onChange={(e) => setNewGroup(e.target.value)}
            autoFocus
          />
          <button type="submit">Create</button>
        </form>
      )}

      <div className="sidebar-list">
        {(groups || []).map((group) => (
          <div key={group.groupName}>
            <div
              className={`chat-item ${activeChat?.type === 'group' && activeChat?.name === group.groupName ? 'active' : ''}`}
              onClick={() => onSelectChat({ type: 'group', name: group.groupName })}
            >
              <div className="chat-item-icon group">
                #
              </div>
              <div className="chat-item-info">
                <div className="chat-item-name">{group.groupName}</div>
                <div className="chat-item-status">
                  {group.members?.length || 0} members
                </div>
              </div>
            </div>
            {activeChat?.type === 'group' && activeChat?.name === group.groupName && (
              <div className="group-members">
                <div className="group-members-header">Members:</div>
                {(group.members || []).map((m) => (
                  <span key={m} className="member-badge">{m}</span>
                ))}
                {peers.filter(p => !(group.members || []).includes(p.username)).length > 0 && (
                  <div className="add-member-row">
                    <select id={`add-member-${group.groupName}`} defaultValue="">
                      <option value="" disabled>Add peer...</option>
                      {peers.filter(p => !(group.members || []).includes(p.username)).map(p => (
                        <option key={p.username} value={p.username}>{p.username}</option>
                      ))}
                    </select>
                    <button onClick={() => {
                      const sel = document.getElementById(`add-member-${group.groupName}`);
                      if (sel.value) onAddToGroup(group.groupName, sel.value);
                    }}>+</button>
                  </div>
                )}
              </div>
            )}
          </div>
        ))}
        {(!groups || groups.length === 0) && (
          <div style={{ padding: '12px 16px', color: '#666', fontSize: '13px' }}>
            No groups yet.
          </div>
        )}
      </div>
    </div>
  );
}
