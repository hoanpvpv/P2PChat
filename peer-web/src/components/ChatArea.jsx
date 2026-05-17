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

export default function ChatArea({ messages, username, messagesEndRef }) {
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

            {isBroadcast ? (
              <div className="message broadcast">
                <div className="broadcast-label">📢 Broadcast</div>
                <div className="message-sender">{msg.sender}</div>
                <div className="message-content">{msg.content}</div>
                <div className="message-time">{formatTime(msg.timestamp)}</div>
              </div>
            ) : (
              <div className={`message ${isSent ? 'sent' : 'received'}`}>
                {!isSent && msg.sender && (
                  <div className="message-sender">{msg.sender}</div>
                )}
                <div className="message-bubble">
                  <div className="message-content">{msg.content}</div>
                  <div className="message-time">{formatTime(msg.timestamp)}</div>
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
