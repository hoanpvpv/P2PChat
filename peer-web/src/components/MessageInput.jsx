import React, { useState, useRef, useEffect } from 'react';

export default function MessageInput({ onSend, onSendFile, disabled, activeChat }) {
  const [text, setText] = useState('');
  const inputRef = useRef(null);
  const fileInputRef = useRef(null);
  
  const handleSubmit = (e) => {
    e.preventDefault();
    if (!text.trim() || disabled) return;
    onSend(text.trim());
    setText('');
    inputRef.current?.focus();
  };

  const handleChange = (e) => {
    setText(e.target.value);
  };

  const handleKeyDown = (e) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSubmit(e);
    }
  };

  const handleFileChange = (e) => {
    const file = e.target.files[0];
    if (!file || disabled) return;
    onSendFile(file);
    e.target.value = ''; // reset
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
    <div className="message-input-wrapper">
      <form className="message-input" onSubmit={handleSubmit}>
      {activeChat.type !== 'broadcast' && (
        <button
          type="button"
          className="attach-btn"
          onClick={() => fileInputRef.current?.click()}
          disabled={disabled}
          title="Send file"
        >
          <svg viewBox="0 0 24 24" width="20" height="20" stroke="currentColor" strokeWidth="2" fill="none" strokeLinecap="round" strokeLinejoin="round">
            <path d="M21.44 11.05l-9.19 9.19a6 6 0 0 1-8.49-8.49l9.19-9.19a4 4 0 0 1 5.66 5.66l-9.2 9.19a2 2 0 0 1-2.83-2.83l8.49-8.48"></path>
          </svg>
        </button>
      )}
      <input
        type="file"
        style={{ display: 'none' }}
        ref={fileInputRef}
        onChange={handleFileChange}
      />
      <input
        ref={inputRef}
        type="text"
        placeholder={placeholder}
        value={text}
        onChange={handleChange}
        onKeyDown={handleKeyDown}
        disabled={disabled}
        autoFocus
      />
      <button type="submit" disabled={disabled || !text.trim()} className="send-btn">
        Send ↵
      </button>
      </form>
    </div>
  );
}
