import { useState } from 'react'
import Login from './pages/Login'
import Chat from './pages/Chat'
import ManusChat from './pages/ManusChat'
import AppSelector from './components/AppSelector'
import './App.css'

function App() {
  const [user, setUser] = useState(() => {
    const saved = localStorage.getItem('user')
    return saved ? JSON.parse(saved) : null
  })

  const [selectedApp, setSelectedApp] = useState(() => {
    return localStorage.getItem('selectedApp') || null
  })

  const handleLogin = (userData) => {
    localStorage.setItem('user', JSON.stringify(userData))
    setUser(userData)
  }

  const handleLogout = () => {
    localStorage.removeItem('user')
    localStorage.removeItem('activeChatId')
    localStorage.removeItem('activeManusChatId')
    localStorage.removeItem('selectedApp')
    setUser(null)
    setSelectedApp(null)
  }

  const handleSelectApp = (appId) => {
    localStorage.setItem('selectedApp', appId)
    setSelectedApp(appId)
  }

  const handleBackToSelector = () => {
    localStorage.removeItem('selectedApp')
    localStorage.removeItem('activeChatId')
    localStorage.removeItem('activeManusChatId')
    setSelectedApp(null)
  }

  if (!user) {
    return <Login onLogin={handleLogin} />
  }

  if (!selectedApp) {
    return <AppSelector selectedApp={selectedApp} onSelectApp={handleSelectApp} user={user} onLogout={handleLogout} />
  }

  // 根据选择的应用渲染不同的页面
  if (selectedApp === 'manus') {
    return <ManusChat user={user} onLogout={handleLogout} onBackToSelector={handleBackToSelector} />
  }

  // 默认：情感大师
  return <Chat user={user} onLogout={handleLogout} onBackToSelector={handleBackToSelector} />
}

export default App
