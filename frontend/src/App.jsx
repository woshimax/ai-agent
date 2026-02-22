import { useState } from 'react'
import Login from './pages/Login'
import Chat from './pages/Chat'
import './App.css'

function App() {
  const [user, setUser] = useState(() => {
    const saved = localStorage.getItem('user')
    return saved ? JSON.parse(saved) : null
  })

  const handleLogin = (userData) => {
    localStorage.setItem('user', JSON.stringify(userData))
    setUser(userData)
  }

  const handleLogout = () => {
    localStorage.removeItem('user')
    localStorage.removeItem('activeChatId')
    setUser(null)
  }

  if (!user) {
    return <Login onLogin={handleLogin} />
  }

  return <Chat user={user} onLogout={handleLogout} />
}

export default App
