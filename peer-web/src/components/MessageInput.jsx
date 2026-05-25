import React, { useState, useRef, useEffect } from 'react';

export default function MessageInput({ onSend, onSendFile, disabled, activeChat }) {
  const [text, setText] = useState('');
  const [images, setImages] = useState([]);
  const [dragOver, setDragOver] = useState(false);
  const inputRef = useRef(null);
  const fileInputRef = useRef(null);
  const imageInputRef = useRef(null);
  
  // Realistic limits
  const MAX_INLINE_SIZE = 15 * 1024 * 1024; // 15 MB for inline Base64 images to prevent JVM OOM
  const MAX_FILE_SIZE = 500 * 1024 * 1024;  // 500 MB for actual file transfers

  const handleSendAction = async () => {
    if ((!text.trim() && images.length === 0) || disabled) return;
    
    if (images.length > 0) {
      const base64Images = await Promise.all(images.map(file => {
        return new Promise((resolve) => {
          const reader = new FileReader();
          reader.onload = (e) => resolve(e.target.result);
          reader.readAsDataURL(file);
        });
      }));
      
      const payload = {
        __rich__: true,
        text: text.trim(),
        images: base64Images
      };
      onSend(JSON.stringify(payload));
    } else {
      onSend(text.trim());
    }
    
    setText('');
    setImages([]);
    inputRef.current?.focus();
  };

  const handleSubmit = (e) => {
    e.preventDefault();
    handleSendAction();
  };

  const handleChange = (e) => {
    setText(e.target.value);
  };

  const handleKeyDown = (e) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSendAction();
    }
  };

  const handleFileChange = (e) => {
    const file = e.target.files[0];
    if (!file || disabled) return;
    if (file.size > MAX_FILE_SIZE) {
      alert(`Dung lượng file vượt quá ${MAX_FILE_SIZE / (1024 * 1024)}MB. Giới hạn truyền file là 500MB.`);
      e.target.value = '';
      return;
    }
    onSendFile(file);
    e.target.value = '';
  };

  const handleImageSelect = (e) => {
    if (disabled) return;
    const files = Array.from(e.target.files).filter(f => f.type.startsWith('image/'));
    addImages(files);
    e.target.value = '';
  };

  const addImages = (files) => {
    setImages(prev => {
      const allFiles = [...prev, ...files];
      if (allFiles.length > 10) {
        alert('Giới hạn hiển thị: tối đa 10 ảnh cùng lúc.');
        return prev;
      }
      const totalSize = allFiles.reduce((sum, img) => sum + img.size, 0);
      if (totalSize > MAX_INLINE_SIZE) {
        alert(`Tổng dung lượng ảnh gửi trực tiếp vượt quá ${MAX_INLINE_SIZE / (1024 * 1024)}MB. Để gửi ảnh gốc dung lượng lớn, vui lòng dùng tính năng đính kèm File.`);
        return prev;
      }
      return allFiles;
    });
  };

  const removeImage = (index) => {
    setImages(prev => prev.filter((_, i) => i !== index));
  };

  useEffect(() => {
    const handlePaste = (e) => {
      if (disabled) return;
      const items = e.clipboardData?.items;
      if (!items) return;
      const pastedImages = [];
      let hasText = false;
      for (let i = 0; i < items.length; i++) {
        if (items[i].type.startsWith('image/')) {
          pastedImages.push(items[i].getAsFile());
        }
        if (items[i].type === 'text/plain') hasText = true;
      }
      if (pastedImages.length > 0) {
        if (!hasText) e.preventDefault(); 
        addImages(pastedImages);
      }
    };
    
    const inputEl = inputRef.current;
    if (inputEl) {
      inputEl.addEventListener('paste', handlePaste);
    }
    return () => {
      if (inputEl) {
        inputEl.removeEventListener('paste', handlePaste);
      }
    };
  }, [disabled]);

  const handleDragOver = (e) => {
    e.preventDefault();
    setDragOver(true);
  };

  const handleDragLeave = (e) => {
    e.preventDefault();
    setDragOver(false);
  };

  const handleDrop = (e) => {
    e.preventDefault();
    setDragOver(false);
    if (disabled) return;
    const files = Array.from(e.dataTransfer.files).filter(f => f.type.startsWith('image/'));
    if (files.length > 0) {
      addImages(files);
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
    <div 
      className={`message-input-wrapper ${dragOver ? 'drag-over' : ''}`}
      onDragOver={handleDragOver}
      onDragLeave={handleDragLeave}
      onDrop={handleDrop}
    >
      {images.length > 0 && (
        <div className="image-preview-container">
          <div className="image-preview-header">
            <span>{images.length} ảnh</span>
          </div>
          <div className="image-preview-list">
            {images.map((img, i) => (
              <div key={i} className="image-preview-item">
                <img src={URL.createObjectURL(img)} alt={`preview-${i}`} />
                <button type="button" className="image-remove-btn" onClick={() => removeImage(i)}>
                  &times;
                </button>
              </div>
            ))}
            <button type="button" className="image-add-more-btn" onClick={() => imageInputRef.current?.click()}>
               <svg viewBox="0 0 24 24" width="24" height="24" stroke="#a8a8a8" strokeWidth="2" fill="none" strokeLinecap="round" strokeLinejoin="round">
                <line x1="12" y1="5" x2="12" y2="19"></line>
                <line x1="5" y1="12" x2="19" y2="12"></line>
              </svg>
            </button>
          </div>
        </div>
      )}
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
        type="file"
        style={{ display: 'none' }}
        ref={imageInputRef}
        onChange={handleImageSelect}
        accept="image/*"
        multiple
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
      <button type="submit" disabled={disabled || (!text.trim() && images.length === 0)} className="send-btn">
        Send ↵
      </button>
      </form>
    </div>
  );
}
