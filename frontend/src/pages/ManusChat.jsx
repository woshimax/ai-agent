import { useEffect, useRef, useState } from 'react'
import Sidebar from '../components/Sidebar'
import MessageList from '../components/MessageList'
import MessageInput from '../components/MessageInput'
import { clearManusHistory, getManusHistory, streamManusChat } from '../api'
import './ManusChat.css'

function getManusChatsKey(userId) {
  return `manusChats:${userId}`
}

function getActiveManusChatKey(userId) {
  return `activeManusChatId:${userId}`
}

function createManusChatId() {
  if (globalThis.crypto?.randomUUID) {
    return globalThis.crypto.randomUUID().replaceAll('-', '')
  }
  return `manus_${Date.now()}_${Math.random().toString(36).slice(2, 10)}`
}

function sortChats(chats) {
  return [...chats].sort((a, b) => {
    const pinDiff = Number(Boolean(b.pinned)) - Number(Boolean(a.pinned))
    if (pinDiff !== 0) return pinDiff
    return new Date(b.updateTime || 0).getTime() - new Date(a.updateTime || 0).getTime()
  })
}

function loadStoredChats(userId) {
  try {
    const raw = localStorage.getItem(getManusChatsKey(userId))
    if (!raw) return []
    const parsed = JSON.parse(raw)
    return Array.isArray(parsed) ? sortChats(parsed) : []
  } catch {
    return []
  }
}

function saveStoredChats(userId, chats) {
  localStorage.setItem(getManusChatsKey(userId), JSON.stringify(chats))
}

function buildNewChat() {
  const now = new Date().toISOString()
  return {
    chatId: createManusChatId(),
    chatName: null,
    pinned: false,
    createTime: now,
    updateTime: now,
  }
}

function generateChatName(text) {
  const trimmed = text.trim()
  if (!trimmed) return '新对话'
  return trimmed.length > 18 ? `${trimmed.slice(0, 18)}...` : trimmed
}

export default function ManusChat({ user, onLogout, onBackToSelector }) {
  const [chats, setChats] = useState(() => loadStoredChats(user.id))
  const [activeChatId, setActiveChatId] = useState(() => {
    const saved = localStorage.getItem(getActiveManusChatKey(user.id))
    return saved || null
  })
  const [messagesByChat, setMessagesByChat] = useState({})
  const [loading, setLoading] = useState(false)
  const [currentStep, setCurrentStep] = useState(null)
  const abortControllerRef = useRef(null)

  const messages = activeChatId ? (messagesByChat[activeChatId] || []) : []
  const activeChat = chats.find(chat => chat.chatId === activeChatId)

  useEffect(() => {
    saveStoredChats(user.id, chats)
  }, [chats, user.id])

  useEffect(() => {
    const key = getActiveManusChatKey(user.id)
    if (activeChatId) {
      localStorage.setItem(key, activeChatId)
    } else {
      localStorage.removeItem(key)
    }
  }, [activeChatId, user.id])

  useEffect(() => () => {
    abortControllerRef.current?.abort()
  }, [])

  useEffect(() => {
    if (!activeChatId || chats.some(chat => chat.chatId === activeChatId)) return
    const now = new Date().toISOString()
    setChats(prev => sortChats([{
      chatId: activeChatId,
      chatName: '未命名对话',
      pinned: false,
      createTime: now,
      updateTime: now,
    }, ...prev]))
  }, [activeChatId, chats])

  useEffect(() => {
    if (!activeChatId || messagesByChat[activeChatId]) return
    let cancelled = false
    getManusHistory(activeChatId)
      .then((res) => {
        if (cancelled) return
        if (res.code === 0 && Array.isArray(res.data)) {
          setMessagesByChat(prev => ({
            ...prev,
            [activeChatId]: res.data,
          }))
        }
      })
      .catch(() => {
        if (!cancelled) {
          setMessagesByChat(prev => ({
            ...prev,
            [activeChatId]: [],
          }))
        }
      })
    return () => {
      cancelled = true
    }
  }, [activeChatId, messagesByChat])

  const upsertChat = (chatId, updater) => {
    setChats(prev => sortChats(prev.map(chat => (
      chat.chatId === chatId ? updater(chat) : chat
    ))))
  }

  const ensureActiveChat = () => {
    if (activeChatId) return activeChatId
    const newChat = buildNewChat()
    setChats(prev => sortChats([newChat, ...prev]))
    setMessagesByChat(prev => ({ ...prev, [newChat.chatId]: [] }))
    setActiveChatId(newChat.chatId)
    return newChat.chatId
  }

  const handleSelectChat = async (chatId) => {
    setActiveChatId(chatId)
    if (messagesByChat[chatId]) return
    try {
      const res = await getManusHistory(chatId)
      if (res.code === 0 && Array.isArray(res.data)) {
        setMessagesByChat(prev => ({
          ...prev,
          [chatId]: res.data,
        }))
      }
    } catch {
      setMessagesByChat(prev => ({
        ...prev,
        [chatId]: [],
      }))
    }
  }

  const handleNewChat = () => {
    if (loading) return
    const newChat = buildNewChat()
    setChats(prev => sortChats([newChat, ...prev]))
    setMessagesByChat(prev => ({ ...prev, [newChat.chatId]: [] }))
    setActiveChatId(newChat.chatId)
    setCurrentStep(null)
  }

  const doStream = (text, chatId, baseMsgs) => {
    const now = new Date().toISOString()
    const userMsg = { role: 'user', content: text }
    const aiMsg = { role: 'ai', content: '', streaming: true, attachments: [] }

    setMessagesByChat(prev => ({
      ...prev,
      [chatId]: [...baseMsgs, userMsg, aiMsg],
    }))
    setLoading(true)
    setCurrentStep(null)

    upsertChat(chatId, chat => ({
      ...chat,
      chatName: chat.chatName || generateChatName(text),
      updateTime: now,
    }))

    let finalResponse = ''

    const controller = streamManusChat(text, chatId, {
      onStep: (stepInfo) => {
        console.log('收到步骤信息:', stepInfo)

        if (stepInfo.startsWith('STEP_START:')) {
          const stepNum = stepInfo.split(':')[1]
          setCurrentStep(`🔄 步骤 ${stepNum}`)
        } else if (stepInfo.startsWith('THINKING:')) {
          const encodedThinking = stepInfo.substring('THINKING:'.length)
          const thinking = encodedThinking.replace(/\\n/g, '\n')
          const displayThinking = thinking.length > 100 ? `${thinking.substring(0, 100)}...` : thinking
          setCurrentStep(`💭 思考: ${displayThinking}`)
        } else if (stepInfo.startsWith('TOOL_CALL:')) {
          setCurrentStep(`🔧 ${stepInfo.substring('TOOL_CALL:'.length)}`)
        } else if (stepInfo.startsWith('TOOL_RESULT:')) {
          setCurrentStep(`✅ 工具执行结果: ${stepInfo.substring('TOOL_RESULT:'.length)}`)
        } else if (stepInfo.startsWith('STEP_DONE:')) {
          const parts = stepInfo.split(':')
          setCurrentStep(`✓ 步骤 ${parts[1]} 完成`)
        } else if (stepInfo.startsWith('FINAL_RESPONSE:')) {
          const encodedResponse = stepInfo.substring('FINAL_RESPONSE:'.length)
          const response = encodedResponse.replace(/\\n/g, '\n')
          finalResponse = response
          setCurrentStep(null)
          setMessagesByChat(prev => {
            const msgs = [...(prev[chatId] || [])]
            const lastIndex = msgs.length - 1
            const lastMsg = { ...(msgs[lastIndex] || {}) }
            msgs[lastIndex] = {
              ...lastMsg,
              role: 'ai',
              content: response,
              streaming: false,
            }
            return { ...prev, [chatId]: msgs }
          })
        } else if (stepInfo.startsWith('FILE_READY:')) {
          const payload = stepInfo.substring('FILE_READY:'.length)
          try {
            const fileInfo = JSON.parse(payload)
            setMessagesByChat(prev => {
              const msgs = [...(prev[chatId] || [])]
              const lastIndex = msgs.length - 1
              const lastMsg = { ...(msgs[lastIndex] || {}) }
              const attachments = lastMsg.attachments || []
              const alreadyExists = attachments.some(item => item.downloadUrl === fileInfo.downloadUrl)
              msgs[lastIndex] = {
                ...lastMsg,
                role: 'ai',
                attachments: alreadyExists ? attachments : [...attachments, fileInfo],
              }
              return { ...prev, [chatId]: msgs }
            })
            setCurrentStep(`📄 已生成 ${fileInfo.filename}`)
          } catch (error) {
            console.error('解析文件事件失败:', error)
          }
        } else if (stepInfo === 'MAX_STEPS_REACHED') {
          setCurrentStep('⚠️ 已达到最大步数限制')
        }
      },
      onDone: () => {
        setLoading(false)
        setCurrentStep(null)
        abortControllerRef.current = null
        if (!finalResponse) {
          setMessagesByChat(prev => {
            const msgs = [...(prev[chatId] || [])]
            const lastIndex = msgs.length - 1
            const lastMsg = { ...(msgs[lastIndex] || {}) }
            msgs[lastIndex] = {
              ...lastMsg,
              role: 'ai',
              content: '任务执行完成',
              streaming: false,
              attachments: lastMsg.attachments || [],
            }
            return { ...prev, [chatId]: msgs }
          })
        }
      },
      onError: (error) => {
        console.error('Manus 执行失败:', error)
        setLoading(false)
        setCurrentStep(null)
        abortControllerRef.current = null
        setMessagesByChat(prev => {
          const msgs = [...(prev[chatId] || [])]
          const lastIndex = msgs.length - 1
          const lastMsg = { ...(msgs[lastIndex] || {}) }
          msgs[lastIndex] = {
            ...lastMsg,
            role: 'ai',
            content: `执行失败：${error.message}`,
            error: true,
            streaming: false,
            attachments: lastMsg.attachments || [],
          }
          return { ...prev, [chatId]: msgs }
        })
      },
    })

    abortControllerRef.current = controller
  }

  const handleSend = (text) => {
    if (loading) return
    const chatId = ensureActiveChat()
    const baseMsgs = messagesByChat[chatId] || []
    doStream(text, chatId, baseMsgs)
  }

  const handleRegenerate = () => {
    if (!activeChatId || loading) return
    const msgs = messagesByChat[activeChatId] || []
    let lastUserIdx = -1
    for (let i = msgs.length - 1; i >= 0; i--) {
      if (msgs[i].role === 'user') {
        lastUserIdx = i
        break
      }
    }
    if (lastUserIdx === -1) return
    const lastUserText = msgs[lastUserIdx].content
    const baseMsgs = msgs.slice(0, lastUserIdx)
    doStream(lastUserText, activeChatId, baseMsgs)
  }

  const handleEdit = (index, newText) => {
    if (!activeChatId || loading) return
    const baseMsgs = (messagesByChat[activeChatId] || []).slice(0, index)
    doStream(newText, activeChatId, baseMsgs)
  }

  const handleRenameChat = (chatId, chatName) => {
    upsertChat(chatId, chat => ({
      ...chat,
      chatName,
      updateTime: new Date().toISOString(),
    }))
  }

  const handlePinChat = (chatId, pinned) => {
    upsertChat(chatId, chat => ({
      ...chat,
      pinned,
      updateTime: new Date().toISOString(),
    }))
  }

  const handleDeleteChat = (chatId) => {
    clearManusHistory(chatId).catch(() => {})
    setChats(prev => {
      const nextChats = prev.filter(chat => chat.chatId !== chatId)
      if (activeChatId === chatId) {
        setActiveChatId(nextChats[0]?.chatId || null)
      }
      return nextChats
    })
    setMessagesByChat(prev => {
      const next = { ...prev }
      delete next[chatId]
      return next
    })
    setCurrentStep(null)
  }

  const handleClearHistory = () => {
    if (!activeChatId || !window.confirm('确定要清空当前对话吗？')) return
    clearManusHistory(activeChatId)
      .catch(() => {})
      .finally(() => {
        setMessagesByChat(prev => ({
          ...prev,
          [activeChatId]: [],
        }))
        setCurrentStep(null)
      })
  }

  return (
    <div className="chat-page manus-chat-page">
      <Sidebar
        chats={chats}
        activeChatId={activeChatId}
        onSelectChat={handleSelectChat}
        onNewChat={handleNewChat}
        onDeleteChat={handleDeleteChat}
        onRenameChat={handleRenameChat}
        onPinChat={handlePinChat}
        user={user}
        onLogout={onLogout}
        onBackToSelector={onBackToSelector}
      />
      <div className="chat-main">
        {activeChatId ? (
          <>
            <div className="chat-toolbar manus-toolbar">
              <div className="manus-toolbar-title">
                <span className="manus-toolbar-icon">🤖</span>
                <span>{activeChat?.chatName || '新对话'}</span>
              </div>
              <button className="toolbar-btn" onClick={handleClearHistory} disabled={loading || messages.length === 0}>
                清空历史
              </button>
            </div>
            <MessageList
              messages={messages}
              streaming={loading}
              onRegenerate={handleRegenerate}
              onEdit={handleEdit}
              aiName="Manus"
            />
            {currentStep && (
              <div className="manus-step-indicator">
                <span className="step-spinner">⚙️</span>
                <span className="step-text">{currentStep}</span>
              </div>
            )}
            <MessageInput
              onSend={handleSend}
              disabled={loading}
              placeholder="告诉我你想完成什么任务，我来帮你查、写或生成文件..."
            />
          </>
        ) : (
          <div className="manus-welcome">
            <div className="welcome-icon">🤖</div>
            <h2>欢迎使用 Manus</h2>
            <p>把要查的、要做的、要整理的交给我，我会一步步帮你完成。</p>
            <div className="welcome-features">
              <div className="feature-item">
                <span className="feature-icon">🔧</span>
                <span>多工具协同</span>
              </div>
              <div className="feature-item">
                <span className="feature-icon">🧠</span>
                <span>智能推理</span>
              </div>
              <div className="feature-item">
                <span className="feature-icon">📝</span>
                <span>任务分解</span>
              </div>
              <div className="feature-item">
                <span className="feature-icon">⚡</span>
                <span>高效执行</span>
              </div>
            </div>
            <button className="manus-start-btn" onClick={handleNewChat}>
              + 新建对话
            </button>
          </div>
        )}
      </div>
    </div>
  )
}
