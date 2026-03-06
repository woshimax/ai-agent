import { useState, useEffect, useRef } from 'react'

export default function Sidebar({ chats, activeChatId, onSelectChat, onNewChat, onDeleteChat, onRenameChat, onPinChat, user, onLogout }) {
  const [menuChatId, setMenuChatId] = useState(null)
  const [renamingChatId, setRenamingChatId] = useState(null)
  const [renameValue, setRenameValue] = useState('')
  const menuRef = useRef(null)
  const renameInputRef = useRef(null)

  // 点击外部关闭菜单
  useEffect(() => {
    const handleClick = (e) => {
      if (menuRef.current && !menuRef.current.contains(e.target)) {
        setMenuChatId(null)
      }
    }
    document.addEventListener('mousedown', handleClick)
    return () => document.removeEventListener('mousedown', handleClick)
  }, [])

  // 重命名输入框自动聚焦
  useEffect(() => {
    if (renamingChatId && renameInputRef.current) {
      renameInputRef.current.focus()
      renameInputRef.current.select()
    }
  }, [renamingChatId])

  const handleRenameSubmit = (chatId) => {
    const trimmed = renameValue.trim()
    if (trimmed) {
      onRenameChat(chatId, trimmed)
    }
    setRenamingChatId(null)
  }

  return (
    <div className="sidebar">
      <div className="sidebar-header">
        <h2>{user.username}</h2>
        <button className="logout-btn" onClick={onLogout}>退出</button>
      </div>

      <button className="new-chat-btn" onClick={onNewChat}>
        + 新建对话
      </button>

      <div className="chat-list">
        {chats.map((chat) => (
          <div
            key={chat.chatId}
            className={`chat-item ${chat.chatId === activeChatId ? 'active' : ''}`}
            onClick={() => onSelectChat(chat.chatId)}
          >
            {chat.pinned && <span className="chat-pin-icon">&#x1F4CC;</span>}
            {renamingChatId === chat.chatId ? (
              <input
                ref={renameInputRef}
                className="chat-rename-input"
                value={renameValue}
                onChange={(e) => setRenameValue(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter') handleRenameSubmit(chat.chatId)
                  if (e.key === 'Escape') setRenamingChatId(null)
                }}
                onBlur={() => handleRenameSubmit(chat.chatId)}
                onClick={(e) => e.stopPropagation()}
              />
            ) : (
              <span className="chat-item-name">{chat.chatName || '新对话'}</span>
            )}
            <div className="chat-menu-wrapper" ref={menuChatId === chat.chatId ? menuRef : null}>
              <button
                className="chat-more-btn"
                onClick={(e) => {
                  e.stopPropagation()
                  setMenuChatId(menuChatId === chat.chatId ? null : chat.chatId)
                }}
              >
                &#x22EF;
              </button>
              {menuChatId === chat.chatId && (
                <div className="chat-context-menu">
                  <button onClick={(e) => {
                    e.stopPropagation()
                    onPinChat(chat.chatId, !chat.pinned)
                    setMenuChatId(null)
                  }}>
                    {chat.pinned ? '取消置顶' : '置顶聊天'}
                  </button>
                  <button onClick={(e) => {
                    e.stopPropagation()
                    setRenameValue(chat.chatName || '')
                    setRenamingChatId(chat.chatId)
                    setMenuChatId(null)
                  }}>
                    重命名
                  </button>
                  <button className="chat-menu-delete" onClick={(e) => {
                    e.stopPropagation()
                    onDeleteChat(chat.chatId)
                    setMenuChatId(null)
                  }}>
                    删除会话
                  </button>
                </div>
              )}
            </div>
          </div>
        ))}
      </div>
    </div>
  )
}
