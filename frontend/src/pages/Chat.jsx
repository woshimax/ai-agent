import { useState, useEffect, useCallback, useRef } from 'react'
import Sidebar from '../components/Sidebar'
import MessageList from '../components/MessageList'
import MessageInput from '../components/MessageInput'
import { listChats, createChat, streamChat, getChatHistory, generateTitle } from '../api'

export default function Chat({ user, onLogout }) {
  const [chats, setChats] = useState([])
  const [activeChatId, setActiveChatId] = useState(
    () => localStorage.getItem('activeChatId') || null
  )
  const [messagesByChat, setMessagesByChat] = useState({})
  const [streaming, setStreaming] = useState(false)
  const controllerRef = useRef(null)

  const messages = activeChatId ? (messagesByChat[activeChatId] || []) : []

  const fetchChats = useCallback(async () => {
    try {
      const res = await listChats(user.id)
      if (res.code === 0 && res.data) {
        setChats(res.data)
      }
    } catch {
      // ignore
    }
  }, [user.id])

  useEffect(() => {
    fetchChats().then(() => {
      if (activeChatId && !messagesByChat[activeChatId]) {
        handleSelectChat(activeChatId)
      }
    })
  }, [fetchChats])

  const handleSelectChat = async (chatId) => {
    setActiveChatId(chatId)
    localStorage.setItem('activeChatId', chatId)
    // 已经加载过的就不重复请求
    if (messagesByChat[chatId]) return
    try {
      const res = await getChatHistory(chatId)
      if (res.code === 0 && res.data) {
        setMessagesByChat((prev) => ({
          ...prev,
          [chatId]: res.data,
        }))
      }
    } catch {
      // ignore
    }
  }

  const handleNewChat = async () => {
    try {
      const res = await createChat(user.id)
      if (res.code === 0 && res.data) {
        await fetchChats()
        setActiveChatId(res.data.chatId)
        localStorage.setItem('activeChatId', res.data.chatId)
      }
    } catch {
      // ignore
    }
  }

  const handleSend = (text) => {
    if (!activeChatId || streaming) return

    const userMsg = { role: 'user', content: text }
    const aiMsg = { role: 'ai', content: '', streaming: true }

    setMessagesByChat((prev) => ({
      ...prev,
      [activeChatId]: [...(prev[activeChatId] || []), userMsg, aiMsg],
    }))
    setStreaming(true)

    const chatId = activeChatId
    const controller = streamChat(text, chatId, {
      onChunk(chunk) {
        setMessagesByChat((prev) => {
          const msgs = [...(prev[chatId] || [])]
          const last = { ...msgs[msgs.length - 1] }
          last.content += chunk
          msgs[msgs.length - 1] = last
          return { ...prev, [chatId]: msgs }
        })
      },
      onDone() {
        setMessagesByChat((prev) => {
          const msgs = [...(prev[chatId] || [])]
          const last = { ...msgs[msgs.length - 1] }
          last.streaming = false
          msgs[msgs.length - 1] = last
          return { ...prev, [chatId]: msgs }
        })
        setStreaming(false)
        controllerRef.current = null
        // 如果对话还没有标题，尝试生成
        const chat = chats.find((c) => c.chatId === chatId)
        if (chat && !chat.chatName) {
          generateTitle(text, chatId).then((res) => {
            if (res.code === 0 && res.data) {
              fetchChats()
            }
          }).catch(() => {})
        }
      },
      onError() {
        setMessagesByChat((prev) => {
          const msgs = [...(prev[chatId] || [])]
          const last = { ...msgs[msgs.length - 1] }
          last.content = last.content || ''
          last.content += last.content ? '\n[网络异常，请重试]' : '网络异常，请稍后重试'
          last.streaming = false
          last.error = true
          msgs[msgs.length - 1] = last
          return { ...prev, [chatId]: msgs }
        })
        setStreaming(false)
        controllerRef.current = null
      },
    })
    controllerRef.current = controller
  }

  return (
    <div className="chat-page">
      <Sidebar
        chats={chats}
        activeChatId={activeChatId}
        onSelectChat={handleSelectChat}
        onNewChat={handleNewChat}
        user={user}
        onLogout={onLogout}
      />
      <div className="chat-main">
        {activeChatId ? (
          <>
            <MessageList messages={messages} />
            <MessageInput onSend={handleSend} disabled={streaming} />
          </>
        ) : (
          <div className="chat-main-empty">
            请选择一个对话或新建对话开始聊天
          </div>
        )}
      </div>
    </div>
  )
}
