import { useState } from 'react'

export default function MessageInput({ onSend, disabled, placeholder = '请输入内容...' }) {
  const [text, setText] = useState('')

  const handleSubmit = (e) => {
    e.preventDefault()
    const msg = text.trim()
    if (!msg || disabled) return
    onSend(msg)
    setText('')
  }

  return (
    <form className="message-input-bar" onSubmit={handleSubmit}>
      <input
        type="text"
        placeholder={placeholder}
        value={text}
        onChange={(e) => setText(e.target.value)}
        disabled={disabled}
        autoFocus
      />
      <button type="submit" disabled={disabled || !text.trim()}>
        发送
      </button>
    </form>
  )
}
