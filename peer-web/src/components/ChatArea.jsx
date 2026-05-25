import React from 'react';

function formatTime(ts) {
  return new Date(ts).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
}

function formatDate(ts) {
  const d = new Date(ts);
  const today = new Date();
  if (d.toDateString() === today.toDateString()) return 'Today';
  const yesterday = new Date(today);
  yesterday.setDate(today.getDate() - 1);
  if (d.toDateString() === yesterday.toDateString()) return 'Yesterday';
  return d.toLocaleDateString();
}

function renderDeliveryState(state, msg = {}) {
  switch (state) {
    case 'DELIVERED_DIRECT':
    case 'DELIVERED':
      return ' ✓✓ Đã giao';
    case 'STORED_MAILBOX':
      return ' 📬 Đã lưu mailbox';
    case 'QUEUED_LOCAL':
    case 'FAILED_RETRYABLE':
      if (msg.failureCode === 'ERR_MAILBOX_SEND' && /Missing E2EE public key/i.test(msg.deliveryError || '')) {
        return ' 🔑 Chờ public key để lưu mailbox';
      }
      if (msg.failureCode === 'ERR_MAILBOX_SEND') {
        return ' 📬 Chưa lưu mailbox, sẽ thử lại';
      }
      if (msg.failureCode === 'ERR_DIRECT_SEND') {
        return ' 📬 Đang chuyển sang mailbox';
      }
      return ' ⏳ Đang chờ retry';
    case 'DEAD_LETTER':
      return ' ❌ Giao thất bại';
    case 'PENDING_LOCAL':
    case 'DIRECT_IN_FLIGHT':
    case 'MAILBOX_IN_FLIGHT':
      return ' ⌛ Đang gửi';
    default:
      return ` · ${state}`;
  }
}

function getFileIcon(filename) {
  if (!filename) return '📁';
  const parts = filename.split('.');
  const ext = parts.length > 1 ? parts.pop().toLowerCase() : '';
  if (['txt', 'md', 'docx', 'doc', 'pdf', 'rtf'].includes(ext)) return '📄';
  if (['png', 'jpg', 'jpeg', 'gif', 'svg', 'webp'].includes(ext)) return '🖼️';
  if (['mp4', 'webm', 'mov', 'avi'].includes(ext)) return '🎬';
  if (['mp3', 'wav', 'ogg', 'flac'].includes(ext)) return '🎵';
  if (['zip', 'rar', '7z', 'tar', 'gz'].includes(ext)) return '📦';
  return '📁';
}

export default function ChatArea({ messages, username, messagesEndRef, onDownload, transfers, activeChatGroup, peers }) {
  const getOwnerUsername = () => {
    if (!activeChatGroup || !activeChatGroup.owner || !peers) return null;
    const ownerAddr = activeChatGroup.owner;
    const ownerPeer = peers.find(p => p.host + ':' + p.port === ownerAddr);
    return ownerPeer ? ownerPeer.username : ownerAddr;
  };
  const ownerUsername = getOwnerUsername();
  const isCoord = (uname) => {
    if (!activeChatGroup || !activeChatGroup.coordinators || !peers) return false;
    const peer = peers.find(p => p.username === uname);
    if (!peer) return false;
    return activeChatGroup.coordinators.includes(peer.host + ':' + peer.port);
  };
  const scrollToMessage = (id) => {
    const el = document.getElementById(`msg-${id}`);
    if (el) el.scrollIntoView({ behavior: 'smooth', block: 'center' });
  };
  let lastDate = null;

  return (
    <div className="chat-area">
      {(!messages || messages.length === 0) && (
        <div className="chat-empty">No messages yet. Say hello! 👋</div>
      )}

      {(messages || []).map((msg, i) => {
        const isSent = msg.sender === username;
        const isBroadcast = msg.type === 'BROADCAST';
        const dateLabel = formatDate(msg.timestamp);
        const showDate = dateLabel !== lastDate;
        if (showDate) lastDate = dateLabel;

        return (
          <React.Fragment key={i}>
            {showDate && (
              <div className="date-divider">
                <span>{dateLabel}</span>
              </div>
            )}

            {msg.type === 'SYSTEM' ? (
              <div className="message system-message">
                <div className="system-message-content">{msg.content}</div>
              </div>
            ) : isBroadcast ? (
              /* ── Regular broadcast: sent = right, received = left ── */
              <div className={`message-wrapper ${isSent ? 'sent' : 'received'}`}>
                {!isSent && (
                  <div className="message-avatar-container">
                    <div className="message-avatar">{msg.sender ? msg.sender.charAt(0).toUpperCase() : '?'}</div>
                  </div>
                )}
                <div id={`msg-${msg.messageId}`} className={`message broadcast ${isSent ? 'broadcast-sent' : 'broadcast-received'}`}>
                  {!isSent && <div className="message-sender">{msg.sender}</div>}
                  <div className="message-content">{msg.content}</div>
                  <div className="message-time">{formatTime(msg.timestamp)}</div>
                </div>
              </div>
            ) : (
              <div className={`message-wrapper ${isSent ? 'sent' : 'received'}`}>
                {!isSent && (
                  <div className="message-avatar-container">
                    <div className="message-avatar">{msg.sender ? msg.sender.charAt(0).toUpperCase() : '?'}</div>
                    {msg.sender === ownerUsername && <span className="avatar-crown" title="Room Owner">👑</span>}
                  </div>
                )}
                <div id={`msg-${msg.messageId}`} className={`message ${isSent ? 'sent' : 'received'} ${msg.type === 'FILE_OFFER' ? 'file-message' : ''}`}>
                  {!isSent && msg.sender && (
                    <div className="message-sender">{msg.sender}</div>
                  )}
                  <div className="message-bubble">
                    {msg.type === 'FILE_OFFER' ? (
                      <div className="file-attachment">
                        {(() => {
                          try {
                            const meta = JSON.parse(msg.content);
                            const transfer = (transfers || []).find(t => t.transferId === meta.transferId);
                            const status = transfer ? transfer.status : 'PENDING';
                            const percent = transfer ? transfer.percent : 0;

                            return (
                              <div className="file-bubble-content">
                                <div className="file-icon-large">
                                  {getFileIcon(meta.filename)}
                                </div>
                                <div className="file-bubble-info">
                                  <div className="file-bubble-name" title={meta.filename}>
                                    {meta.filename}
                                  </div>
                                  <div className="file-bubble-size">
                                    {Math.round(meta.fileSize / 1024)} KB
                                  </div>

                                  {status === 'ACTIVE' && (
                                    <div className="file-bubble-progress">
                                      <div className="file-progress-bar">
                                        <div className="file-progress-fill" style={{ width: `${percent}%` }}></div>
                                      </div>
                                      <div className="file-progress-text">{percent}%</div>
                                    </div>
                                  )}

                                  {!isSent && status !== 'ACTIVE' && status !== 'DONE' && (
                                    <button className="btn-download-bubble" onClick={() => onDownload && onDownload(meta.transferId)}>
                                      <svg viewBox="0 0 24 24" width="16" height="16" stroke="currentColor" strokeWidth="2" fill="none" strokeLinecap="round" strokeLinejoin="round">
                                        <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"></path>
                                        <polyline points="7 10 12 15 17 10"></polyline>
                                        <line x1="12" y1="15" x2="12" y2="3"></line>
                                      </svg>
                                      Download
                                    </button>
                                  )}

                                  {status === 'DONE' && (
                                    <div className="file-bubble-done">
                                      ✅ Downloaded
                                      <a href={`/api/file/download/${meta.transferId}`} download={meta.filename} className="btn-save-again" title="Save to device again">
                                        ⬇️ Save
                                      </a>
                                    </div>
                                  )}

                                  {isSent && (
                                    <div className="file-bubble-status">
                                      {status === 'DONE' ? '✅ Delivered' : (status === 'ACTIVE' ? 'Uploading...' : 'Sent offer')}
                                    </div>
                                  )}
                                </div>
                              </div>
                            );
                          } catch (e) {
                            return <div className="message-content">{msg.content}</div>;
                          }
                        })()}
                      </div>
                    ) : (
                      <div className="message-content">{msg.content}</div>
                    )}
                    <div className="message-time">
                      {formatTime(msg.timestamp)}
                      {isSent && msg.deliveryState && (
                        <span className={`delivery-state ds-${msg.deliveryState}`}>
                          {renderDeliveryState(msg.deliveryState, msg)}
                        </span>
                      )}
                    </div>
                  </div>
                </div>
              </div>
            )}
          </React.Fragment>
        );
      })}
      <div ref={messagesEndRef} />
    </div>
  );
}
