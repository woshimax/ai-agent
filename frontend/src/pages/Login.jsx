import { useState } from 'react'
import { registerUser, loginUser } from '../api'

export default function Login({ onLogin }) {
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [isRegister, setIsRegister] = useState(false)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  const handleSubmit = async (e) => {
    e.preventDefault()
    const name = username.trim()
    const pwd = password.trim()
    if (!name || !pwd) return

    setLoading(true)
    setError('')

    try {
      const res = isRegister
        ? await registerUser(name, pwd)
        : await loginUser(name, pwd)

      if (res.code === 0 && res.data) {
        onLogin(res.data)
      } else {
        setError(res.message || (isRegister ? '注册失败' : '登录失败'))
      }
    } catch {
      setError('网络异常，请检查后端服务是否启动')
    } finally {
      setLoading(false)
    }
  }

  const toggleMode = () => {
    setIsRegister(!isRegister)
    setError('')
  }

  const valid = username.trim() && password.trim()

  return (
    <div className="login-page">
      <div className="login-ambient" aria-hidden="true">
        <span />
        <span />
        <span />
        <span />
        <span />
        <span />
      </div>
      <form className="login-card" onSubmit={handleSubmit}>
        <h1>AI 助手平台</h1>
        <p>登录后可选择「情感大师」或「Manus 智能体」开始对话</p>
        <div className="login-highlights">
          <span>🌿 温柔陪伴</span>
          <span>🕊️ 安静表达</span>
          <span>🤖 高效执行</span>
        </div>
        <div className="login-fields">
          <input
            type="text"
            placeholder="请输入用户名"
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            autoFocus
          />
          <input
            type="password"
            placeholder="请输入密码"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
          />
        </div>
        <button className="login-submit-btn" type="submit" disabled={loading || !valid}>
          {loading ? '...' : isRegister ? '注册' : '登录'}
        </button>
        {error && <div className="login-error">{error}</div>}
        <div className="login-toggle">
          {isRegister ? '已有账号？' : '没有账号？'}
          <span onClick={toggleMode}>
            {isRegister ? '去登录' : '去注册'}
          </span>
        </div>
      </form>
    </div>
  )
}
