export default function JournalPagination({ data, onPage, loading, label }) {
  if (!data?.totalElements) return null
  return <nav className="pagination compact-pagination" aria-label={label}>
    <button type="button" disabled={loading || data.page <= 0} onClick={() => onPage(data.page - 1)}>上一页</button>
    <span>第 {data.page + 1} / {Math.max(1, data.totalPages)} 页，共 {data.totalElements} 条</span>
    <button type="button" disabled={loading || data.page + 1 >= data.totalPages} onClick={() => onPage(data.page + 1)}>下一页</button>
  </nav>
}
