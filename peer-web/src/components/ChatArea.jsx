import React, { useState } from 'react';

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
    case 'DELIVERED_VIA_RELAY':
    case 'DELIVERED_VIA_MAILBOX':
    case 'DELIVERED':
      return ' ✓✓ Đã giao';
    case 'STORED_MAILBOX':
      return ' 📬 Đã lưu mailbox';
    case 'STORED_RELAY':
      return ' 🔁 Đã lưu relay';
    case 'QUEUED_LOCAL':
    case 'FAILED_RETRYABLE':
    case 'RELAY_FAILED_RETRYABLE':
      if (msg.failureCode === 'ERR_MAILBOX_SEND' && /Missing E2EE public key/i.test(msg.deliveryError || '')) {
        return ' 🔑 Chờ public key để lưu mailbox';
      }
      if (msg.failureCode === 'ERR_RELAY_SEND' || state === 'RELAY_FAILED_RETRYABLE') {
        return ' 🔁 Relay lỗi, sẽ thử lại';
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
    case 'RELAY_IN_FLIGHT':
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
    if (!activeChatGroup || !activeChatGroup.owner) return null;
    return activeChatGroup.owner;
  };
  const ownerUsername = getOwnerUsername();
  const isCoord = (uname) => {
    if (!activeChatGroup || !activeChatGroup.coordinators) return false;
    return activeChatGroup.coordinators.includes(uname);
  };
  const scrollToMessage = (id) => {
    const el = document.getElementById(`msg-${id}`);
    if (el) el.scrollIntoView({ behavior: 'smooth', block: 'center' });
  };
  let lastDate = null;
  const [expandedState, setExpandedState] = useState(null);

  const renderMessageContent = (msg, isSent) => {
    let isRich = false;
    let text = msg.content;
    let images = [];
    
    try {
      const parsed = JSON.parse(msg.content);
      if (parsed && parsed.__rich__) {
        isRich = true;
        text = parsed.text;
        images = parsed.images || [];
      }
    } catch (e) {
      // not JSON, fallback to plain text
    }

    if (!isRich || images.length === 0) {
      return <div className="message-content">{text}</div>;
    }

    return (
      <div className="rich-message-content">
        <div className={`message-images grid-${Math.min(images.length, 3)}`}>
          {images.slice(0, 3).map((imgUrl, idx) => {
            const isLastVisible = idx === 2;
            const remaining = images.length - 3;
            return (
              <div 
                key={idx} 
                className="message-image-wrapper"
                onClick={() => setExpandedState({ images, index: idx })}
              >
                <img src={imgUrl} alt={`attached-${idx}`} />
                {isLastVisible && remaining > 0 && (
                  <div className="image-overlay">+{remaining}</div>
                )}
              </div>
            );
          })}
        </div>
        {text && <div className="message-text">{text}</div>}
      </div>
    );
  };

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
                  {renderMessageContent(msg, isSent)}
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
                    ) : renderMessageContent(msg, isSent)}
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
      {expandedState && (
        <div className="image-lightbox">
          <div className="lightbox-close" onClick={() => setExpandedState(null)}>&times;</div>
          
          <button 
            className="lightbox-nav lightbox-prev" 
            disabled={expandedState.index === 0}
            onClick={() => setExpandedState(prev => ({ ...prev, index: prev.index - 1 }))}
          >
            &#10094;
          </button>
          
          <div className="lightbox-content">
            <div className="lightbox-main">
              <img src={expandedState.images[expandedState.index]} alt={`expanded-main`} />
              <a href={expandedState.images[expandedState.index]} download={`image-${expandedState.index}.png`} className="lightbox-download" title="Download Image">
                <svg viewBox="0 0 24 24" width="20" height="20" stroke="currentColor" strokeWidth="2" fill="none">
                  <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"></path>
                  <polyline points="7 10 12 15 17 10"></polyline>
                  <line x1="12" y1="15" x2="12" y2="3"></line>
                </svg>
              </a>
            </div>
            
            <div className="lightbox-thumbnails">
              {expandedState.images.map((img, i) => (
                <div 
                  key={i} 
                  className={`lightbox-thumbnail ${i === expandedState.index ? 'active' : ''}`}
                  onClick={() => setExpandedState(prev => ({ ...prev, index: i }))}
                >
                  <img src={img} alt={`thumb-${i}`} />
                </div>
              ))}
            </div>
          </div>
          
          <button 
            className="lightbox-nav lightbox-next" 
            disabled={expandedState.index === expandedState.images.length - 1}
            onClick={() => setExpandedState(prev => ({ ...prev, index: prev.index + 1 }))}
          >
            &#10095;
          </button>
        </div>
      )}
    </div>
  );
}
