import React from 'react';

function formatTime(timestamp) {
  const d = new Date(timestamp);
  return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
}

export default function ChatArea({ messages, username, messagesEndRef }) {
  return (
    <div className="chat-area">
      {(messages || []).map((msg, i) => {
        const isSent = msg.sender === username;
        const isBroadcast = msg.type === 'BROADCAST';

        if (isBroadcast) {
          return (
            <div key={i} className="message broadcast">
              <div className="message-sender">{msg.sender} (broadcast)</div>
              <div>{msg.content}</div>
              <div className="message-time">{formatTime(msg.timestamp)}</div>
            </div>
          );
        }

        return (
          <div key={i} className={`message ${isSent ? 'sent' : 'received'}`}>
            {!isSent && msg.sender && (
              <div className="message-sender">{msg.sender}</div>
            )}
            <div>{msg.content}</div>
            <div className="message-time">{formatTime(msg.timestamp)}</div>
          </div>
        );
      })}
      <div ref={messagesEndRef} />
    </div>
  );
}
