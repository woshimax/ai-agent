import { useEffect, useRef, useState } from 'react'

export default function MessageList({ messages, streaming, onCopy, onRegenerate, onEdit }) {
  const bottomRef = useRef(null)
  const [editingIndex, setEditingIndex] = useState(null)
  const [editText, setEditText] = useState('')

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages])

  const handleCopy = (content) => {
    navigator.clipboard.writeText(content)
    onCopy?.()
  }

  const handleStartEdit = (index, content) => {
    setEditingIndex(index)
    setEditText(content)
  }

  const handleSubmitEdit = () => {
    const text = editText.trim()
    if (!text) return
    onEdit?.(editingIndex, text)
    setEditingIndex(null)
    setEditText('')
  }

  const handleCancelEdit = () => {
    setEditingIndex(null)
    setEditText('')
  }

  if (messages.length === 0) {
    return (
      <div className="message-list" style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', color: '#999' }}>
        开始和心理咨询师对话吧
      </div>
    )
  }

  // 找到最后一条 AI 消息的 index
  let lastAiIndex = -1
  for (let i = messages.length - 1; i >= 0; i--) {
    if (messages[i].role === 'ai') { lastAiIndex = i; break }
  }

  return (
    <div className="message-list">
      {messages.map((msg, i) => (
        <div key={i} className={`message-row ${msg.role}`}>
          <div className="message-content">
            <div className="message-label">{msg.role === 'user' ? '你' : '心理咨询师'}</div>

            {editingIndex === i ? (
              <div className="message-edit-box">
                <textarea
                  value={editText}
                  onChange={(e) => setEditText(e.target.value)}
                  autoFocus
                  rows={3}
                />
                <div className="message-edit-actions">
                  <button className="edit-cancel-btn" onClick={handleCancelEdit}>取消</button>
                  <button className="edit-submit-btn" onClick={handleSubmitEdit} disabled={!editText.trim()}>提交</button>
                </div>
              </div>
            ) : (
              <>
                <div className={`message-bubble${msg.error ? ' message-error' : ''}`}>
                  {msg.content?.replace(/<ref>\[.*?\]<\/ref>/g, '')}
                  {msg.streaming && <span className="typing-dot">|</span>}
                </div>
                {!msg.streaming && !streaming && (
                  <div className="message-actions">
                    <button onClick={() => handleCopy(msg.content)}>复制</button>
                    {msg.role === 'user' && (
                      <button onClick={() => handleStartEdit(i, msg.content)}>编辑</button>
                    )}
                    {msg.role === 'ai' && i === lastAiIndex && (
                      <button onClick={() => onRegenerate?.()}>重新生成</button>
                    )}
                  </div>
                )}
              </>
            )}
          </div>
        </div>
      ))}
      <div ref={bottomRef} />
    </div>
  )
}
