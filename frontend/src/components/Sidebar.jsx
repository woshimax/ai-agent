export default function Sidebar({ chats, activeChatId, onSelectChat, onNewChat, user, onLogout }) {
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
        {chats.map((chat, index) => (
          <div
            key={chat.chatId}
            className={`chat-item ${chat.chatId === activeChatId ? 'active' : ''}`}
            onClick={() => onSelectChat(chat.chatId)}
          >
            {chat.chatName || '新对话'}
          </div>
        ))}
      </div>
    </div>
  )
}
