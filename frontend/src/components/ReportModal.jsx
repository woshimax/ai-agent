import { useRef } from 'react'

export default function ReportModal({ report, onClose }) {
  const contentRef = useRef(null)

  const handleDownloadPDF = async () => {
    const html2pdf = (await import('html2pdf.js')).default
    html2pdf()
      .set({
        margin: [20, 20, 20, 20],
        filename: `${report.title || '心理分析报告'}.pdf`,
        html2canvas: { scale: 2 },
        jsPDF: { unit: 'mm', format: 'a4', orientation: 'portrait' },
      })
      .from(contentRef.current)
      .save()
  }

  return (
    <div className="report-overlay" onClick={onClose}>
      <div className="report-card" onClick={(e) => e.stopPropagation()}>
        <div ref={contentRef} className="report-body">
          <h2 className="report-title">{report.title}</h2>

          <div className="report-section">
            <h3 className="report-section-title">识别问题</h3>
            <ul className="report-list">
              {report.problems?.map((s, i) => (
                <li key={i}>{s}</li>
              ))}
            </ul>
          </div>

          <div className="report-section">
            <h3 className="report-section-title">情绪状态评估</h3>
            <p className="report-state">{report.emotionState}</p>
          </div>

          <div className="report-section">
            <h3 className="report-section-title">短期调适建议</h3>
            <ul className="report-list">
              {report.shortTermAdvice?.map((s, i) => (
                <li key={i}>{s}</li>
              ))}
            </ul>
          </div>

          <div className="report-section">
            <h3 className="report-section-title">长期改善建议</h3>
            <ul className="report-list">
              {report.longTermAdvice?.map((s, i) => (
                <li key={i}>{s}</li>
              ))}
            </ul>
          </div>
        </div>
        <div className="report-actions">
          <button className="report-btn report-btn-download" onClick={handleDownloadPDF}>
            下载 PDF
          </button>
          <button className="report-btn report-btn-close" onClick={onClose}>
            关闭
          </button>
        </div>
      </div>
    </div>
  )
}
