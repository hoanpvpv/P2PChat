import React, { useState, useRef } from 'react';

export default function MessageInput({ onSend, disabled, activeChat }) {
  const [text, setText] = useState('');
  const inputRef = useRef(null);

  const handleSubmit = (e) => {
    e.preventDefault();
    if (!text.trim() || disabled) return;
    onSend(text.trim());
    setText('');
    inputRef.current?.focus();
  };

  const handleKeyDown = (e) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSubmit(e);
    }
  };

  if (!activeChat) return null;

  const placeholder = disabled
    ? 'Bạn đã bị xóa khỏi nhóm này'
    : activeChat.type === 'broadcast'
      ? 'Broadcast to all peers...'
      : activeChat.type === 'group'
        ? `Message # ${activeChat.name}...`
        : `Message @ ${activeChat.name}...`;

  return (
    <form className="message-input" onSubmit={handleSubmit}>
      <input
        ref={inputRef}
        type="text"
        placeholder={placeholder}
        value={text}
        onChange={e => setText(e.target.value)}
        onKeyDown={handleKeyDown}
        disabled={disabled}
        autoFocus
      />
      <button type="submit" disabled={disabled || !text.trim()} className="send-btn">
        Send ↵
      </button>
    </form>
  );
}
