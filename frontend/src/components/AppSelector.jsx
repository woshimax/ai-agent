import { useState } from 'react'
import './AppSelector.css'

export default function AppSelector({ selectedApp, onSelectApp, user, onLogout }) {
  const [draftApp, setDraftApp] = useState(selectedApp || null)
  const apps = [
    {
      id: 'emotion',
      name: '情感大师',
      icon: '💭',
      description: '更温柔地倾听、回应与陪伴，适合情绪梳理与长期对话',
      features: ['情绪陪伴', '心理分析', '咨询报告', '长期记忆']
    },
    {
      id: 'manus',
      name: 'Manus',
      icon: '🤖',
      description: '擅长查资料、做规划、调用工具，帮你高效完成复杂任务',
      features: ['工具调用', '任务规划', '多步执行', '文件导出']
    }
  ]

  return (
    <div className="app-selector-page">
      <div className="app-selector-header">
        <div>
          <h1>选择今天想见的 AI 助手</h1>
          <p className="app-selector-subtitle">一个偏向温柔陪伴，一个偏向高效执行；你可以随时在它们之间切换。</p>
        </div>
        <div className="user-info">
          <span>👤 {user.username}</span>
          <button className="app-selector-logout-btn" onClick={onLogout}>退出</button>
        </div>
      </div>

      <div className="app-cards">
        {apps.map(app => (
          <div
            key={app.id}
            className={`app-card ${draftApp === app.id ? 'selected' : ''}`}
            onClick={() => setDraftApp(app.id)}
          >
            <div className="app-icon">{app.icon}</div>
            <h2>{app.name}</h2>
            <p className="app-description">{app.description}</p>
            <div className="app-features">
              {app.features.map((feature, index) => (
                <span key={index} className="feature-tag">{feature}</span>
              ))}
            </div>
            {draftApp === app.id && (
              <div className="selected-badge">✓ 已选择</div>
            )}
          </div>
        ))}
      </div>

      {draftApp && (
        <div className="action-bar">
          <button className="start-btn" onClick={() => onSelectApp(draftApp)}>
            开始对话 →
          </button>
          <p className="action-hint">进入后仍可随时切换到另一个应用</p>
        </div>
      )}
    </div>
  )
}
