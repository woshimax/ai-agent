import { useState, useEffect, useCallback, useRef } from 'react'
import Sidebar from '../components/Sidebar'
import MessageList from '../components/MessageList'
import MessageInput from '../components/MessageInput'
import ReportModal from '../components/ReportModal'
import { listChats, createChat, streamChat, getChatHistory, generateTitle, getEmotionReport } from '../api'

export default function Chat({ user, onLogout }) {
  const [chats, setChats] = useState([])
  const [activeChatId, setActiveChatId] = useState(
    () => localStorage.getItem('activeChatId') || null
  )
  const [messagesByChat, setMessagesByChat] = useState({})
  const [streaming, setStreaming] = useState(false)
  const [reportData, setReportData] = useState(null)
  const [reportLoading, setReportLoading] = useState(false)
  const [showReportModal, setShowReportModal] = useState(false)
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

  const doStream = (text, chatId, baseMsgs) => {
    const userMsg = { role: 'user', content: text }
    const aiMsg = { role: 'ai', content: '', streaming: true }

    setMessagesByChat((prev) => ({
      ...prev,
      [chatId]: [...baseMsgs, userMsg, aiMsg],
    }))
    setStreaming(true)

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
        const chat = chats.find((c) => c.chatId === chatId)
        if (chat && !chat.chatName) {
          generateTitle(text, chatId).then((res) => {
            if (res.code === 0 && res.data) fetchChats()
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

  const handleSend = (text) => {
    if (!activeChatId || streaming) return
    const chatId = activeChatId
    const baseMsgs = messagesByChat[chatId] || []
    doStream(text, chatId, baseMsgs)
  }

  // 重新生成：删掉最后一组（user + ai），重新发送 user 消息
  const handleRegenerate = () => {
    if (!activeChatId || streaming) return
    const chatId = activeChatId
    const msgs = messagesByChat[chatId] || []
    // 找到最后一条 user 消息
    let lastUserIdx = -1
    for (let i = msgs.length - 1; i >= 0; i--) {
      if (msgs[i].role === 'user') { lastUserIdx = i; break }
    }
    if (lastUserIdx === -1) return
    const lastUserText = msgs[lastUserIdx].content
    const baseMsgs = msgs.slice(0, lastUserIdx)
    doStream(lastUserText, chatId, baseMsgs)
  }

  // 编辑：截断到该消息之前，用新文本重新发送
  const handleEdit = (index, newText) => {
    if (!activeChatId || streaming) return
    const chatId = activeChatId
    const msgs = messagesByChat[chatId] || []
    const baseMsgs = msgs.slice(0, index)
    doStream(newText, chatId, baseMsgs)
  }

  const handleGenerateReport = async () => {
    if (!activeChatId || reportLoading) return
    setReportLoading(true)
    try {
      const res = await getEmotionReport(activeChatId)
      if (res.code === 0 && res.data) {
        setReportData(res.data)
        setShowReportModal(true)
      }
    } catch {
      // ignore
    } finally {
      setReportLoading(false)
    }
  }

  const activeChat = chats.find((c) => c.chatId === activeChatId)

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
            <MessageList
              messages={messages}
              streaming={streaming}
              onRegenerate={handleRegenerate}
              onEdit={handleEdit}
            />
            <div className="chat-toolbar">
              <button
                className="toolbar-btn"
                onClick={handleGenerateReport}
                disabled={reportLoading || streaming || messages.length === 0}
              >
                {reportLoading ? '生成中...' : '生成报告'}
              </button>
            </div>
            <MessageInput onSend={handleSend} disabled={streaming} />
            {showReportModal && reportData && (
              <ReportModal
                report={reportData}
                onClose={() => setShowReportModal(false)}
              />
            )}
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
