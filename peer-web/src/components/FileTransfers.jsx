import React from 'react';
import { acceptFile, rejectFile, cancelFile } from '../api';

export default function FileTransfers({ transfers, currentChat, username, showToast }) {
  if (!transfers || !transfers.length || !currentChat) return null;

  // Filter transfers relevant to the current chat
  const relevantTransfers = transfers.filter(t => {
    if (t.status === 'PENDING') return false; // Hide pending, they are shown in ChatArea
    if (currentChat.type === 'peer') {
      return (t.sender.includes(currentChat.name) && t.receiver.includes(username)) ||
             (t.sender.includes(username) && t.receiver.includes(currentChat.name));
    } else if (currentChat.type === 'group') {
      return t.groupId === currentChat.id;
    }
    return false;
  });

  if (relevantTransfers.length === 0) return null;

  const handleAction = async (action, id) => {
    try {
      if (action === 'accept') await acceptFile(id);
      if (action === 'reject') await rejectFile(id);
      if (action === 'cancel') await cancelFile(id);
    } catch (e) {
      showToast('Error: ' + e.message, 'error');
    }
  };

  return (
    <div className="file-transfers-panel">
      {relevantTransfers.map(t => {
        const isSender = t.sender.includes(username);
        return (
          <div key={t.transferId} className={`transfer-item ${isSender ? 'sent' : 'received'}`}>
            <div className="transfer-icon">📄</div>
            <div className="transfer-info">
              <div className="transfer-filename">{t.filename}</div>
              <div className="transfer-meta">
                {(t.fileSize / 1024 / 1024).toFixed(2)} MB • {t.status}
              </div>
              {t.status === 'ACTIVE' && (
                <div className="progress-bar">
                  <div className="progress-fill" style={{ width: `${t.percent || 0}%` }}></div>
                </div>
              )}
            </div>
            <div className="transfer-actions">
              {t.status === 'PENDING' && isSender && (
                <button className="btn-cancel" onClick={() => handleAction('cancel', t.transferId)}>Cancel</button>
              )}
              {t.status === 'PENDING' && !isSender && (
                <>
                  <button className="btn-accept" onClick={() => handleAction('accept', t.transferId)}>Accept</button>
                  <button className="btn-reject" onClick={() => handleAction('reject', t.transferId)}>Reject</button>
                </>
              )}
              {t.status === 'ACTIVE' && isSender && (
                <button className="btn-cancel" onClick={() => handleAction('cancel', t.transferId)}>Cancel</button>
              )}
              {t.status === 'DONE' && (
                <>
                  <span className="done-badge">✓ Done</span>
                  <button className="btn-accept" onClick={() => window.open(`/api/file/download/${t.transferId}`)}>
                    ⬇️ Save
                  </button>
                </>
              )}
            </div>
          </div>
        );
      })}
    </div>
  );
}
