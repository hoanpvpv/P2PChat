import React, { useState } from 'react';

export default function MessageInput({ onSend, onBroadcast, activeChat }) {
  const [text, setText] = useState('');

  const handleSubmit = (e) => {
    e.preventDefault();
    if (!text.trim()) return;
    onSend(text.trim());
    setText('');
  };

  const handleKeyDown = (e) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSubmit(e);
    }
  };

  if (!activeChat) return null;

  return (
    <form className="message-input" onSubmit={handleSubmit}>
      <input
        type="text"
        placeholder={`Message ${activeChat.type === 'group' ? '#' : ''}${activeChat.name}...`}
        value={text}
        onChange={(e) => setText(e.target.value)}
        onKeyDown={handleKeyDown}
        autoFocus
      />
      <button type="submit">Send</button>
    </form>
  );
}
