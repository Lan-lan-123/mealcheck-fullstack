import { Users, ClipboardList, ScanLine, TriangleAlert, BookOpen, LayoutDashboard } from 'lucide-react'
export function MetricCard({ value, label }) {
  const Icon =
    {
      注册用户: Users,
      饮食记录: ClipboardList,
      今日正常上传: ScanLine,
      今日异常上传: TriangleAlert,
      'RAG 片段': BookOpen,
    }[label] || LayoutDashboard
  return (
    <div className="admin-card">
      <div className="admin-metric-heading">
        <span className="admin-label">{label}</span>
        <Icon size={19} strokeWidth={1.6} />
      </div>
      <div className="admin-number">{value}</div>
    </div>
  )
}

export function Info({ label, value }) {
  return (
    <div>
      <span>{label}</span>
      <strong>{value}</strong>
    </div>
  )
}
