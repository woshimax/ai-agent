import { useEffect, useRef } from 'react'

export default function MessageList({ messages }) {
  const bottomRef = useRef(null)

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages])

  if (messages.length === 0) {
    return (
      <div className="message-list" style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', color: '#999' }}>
        开始和情感大师对话吧
      </div>
    )
  }

  return (
    <div className="message-list">
      {messages.map((msg, i) => (
        <div key={i} className={`message-row ${msg.role}`}>
          <div className="message-content">
            <div className="message-label">{msg.role === 'user' ? '你' : '情感大师'}</div>
            <div className={`message-bubble${msg.error ? ' message-error' : ''}`}>
              {msg.content}
              {msg.streaming && <span className="typing-dot">|</span>}
            </div>
          </div>
        </div>
      ))}
      <div ref={bottomRef} />
    </div>
  )
}
