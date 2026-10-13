import { formatTime, Pagination } from './AdminUi'

export default function AuditLogPanel({ auditLogs, auditPageData, auditSize, loading, setAuditPage, setAuditSize }) {
  return (
        <section className="admin-section">
          <div className="admin-section-head">
            <div>
              <h2>管理员审计日志</h2>
              <p>记录删除用户、删除饮食记录、编辑知识库等高风险后台操作。</p>
            </div>
          </div>
          <div className="admin-table-wrap">
            <table className="admin-table">
              <thead>
                <tr><th>ID</th><th>管理员</th><th>操作</th><th>对象</th><th>详情</th><th>时间</th></tr>
              </thead>
              <tbody>
                {auditLogs.length ? auditLogs.map(log => (
                  <tr key={log.id}>
                    <td>{log.id}</td>
                    <td>{log.adminUsername || 'unknown'}</td>
                    <td>{log.action}</td>
                    <td>{log.targetType || '-'} {log.targetId ? `#${log.targetId}` : ''}</td>
                    <td>{log.detail || '暂无'}</td>
                    <td>{formatTime(log.createdAt)}</td>
                  </tr>
                )) : <tr><td colSpan="6" className="empty-cell">暂无审计日志</td></tr>}
              </tbody>
            </table>
          </div>
          <Pagination pageData={auditPageData} pageSize={auditSize} setPage={setAuditPage} setPageSize={setAuditSize} loading={loading} sizes={[10, 20, 50]} />
        </section>
  )
}
