import FilterDisclosure from '../../components/FilterDisclosure'
import Empty from '../../components/Empty'
import RecordItem from '../../components/RecordItem'
import { goals } from '../userWorkspaceConfig'

export default function DietRecordsPanel({ recordPageData, recordFiltersDraft, setRecordFiltersDraft, applyRecordFilters, resetRecordFilters, records, handleDeleteRecord, setCorrectionRecord, setViewingRecord, recordSize, setRecordPage, setRecordSize, loading, recordsLoading }) {
  return (
          <div className="card history-card records-page">
            <div className="section-title-row">
              <div>
                <h2>最近饮食记录</h2>
                <p>按目标、日期和评分筛选历史记录。</p>
              </div>
              <span>{recordPageData.totalElements || 0} 条</span>
            </div>
            <FilterDisclosure activeCount={Object.values(recordFiltersDraft).filter(Boolean).length}><form className="record-filter-form" onSubmit={applyRecordFilters}>
              <select aria-label="饮食目标筛选" value={recordFiltersDraft.goal} onChange={e => setRecordFiltersDraft({ ...recordFiltersDraft, goal: e.target.value })}>
                <option value="">全部目标</option>
                {goals.map(g => (
                  <option key={g.value} value={g.value}>
                    {g.label}
                  </option>
                ))}
              </select>
              <label className="form-field">开始日期<input type="date" max={recordFiltersDraft.to || undefined} value={recordFiltersDraft.from} onChange={e => setRecordFiltersDraft({ ...recordFiltersDraft, from: e.target.value })} /></label>
              <label className="form-field">结束日期<input type="date" min={recordFiltersDraft.from || undefined} value={recordFiltersDraft.to} onChange={e => setRecordFiltersDraft({ ...recordFiltersDraft, to: e.target.value })} /></label>
              <label className="form-field">最低评分<input type="number" min="0" max="100" placeholder="最低评分" value={recordFiltersDraft.minScore} onChange={e => setRecordFiltersDraft({ ...recordFiltersDraft, minScore: e.target.value })} /></label>
              <label className="form-field">最高评分<input type="number" min="0" max="100" placeholder="最高评分" value={recordFiltersDraft.maxScore} onChange={e => setRecordFiltersDraft({ ...recordFiltersDraft, maxScore: e.target.value })} /></label>
              <button className="secondary-btn" type="submit" disabled={loading || recordsLoading}>筛选</button>
              <button className="secondary-btn" type="button" onClick={resetRecordFilters} disabled={loading || recordsLoading}>重置</button>
            </form></FilterDisclosure>

            <div className="record-list">
              {records.length ? records.map(r => <RecordItem key={r.id} record={r} onDelete={handleDeleteRecord} onCorrect={setCorrectionRecord} onView={setViewingRecord} />) : <Empty text="暂无饮食记录" />}
            </div>
            <RecordPagination pageData={recordPageData} pageSize={recordSize} setPage={setRecordPage} setPageSize={setRecordSize} loading={loading || recordsLoading} />
          </div>
  )
}

function RecordPagination({ pageData, pageSize, setPage, setPageSize, loading }) {
  if ((pageData.totalElements || 0) <= 0) return null
  return (
    <div className="pagination compact-pagination">
      <button className="secondary-btn" type="button" onClick={() => setPage(page => Math.max(0, page - 1))} disabled={pageData.page <= 0 || loading}>
        上一页
      </button>
      <span>第 {(pageData.page ?? 0) + 1} / {Math.max(1, pageData.totalPages || 1)} 页，共 {pageData.totalElements || 0} 条</span>
      <button className="secondary-btn" type="button" onClick={() => setPage(page => page + 1)} disabled={(pageData.page ?? 0) + 1 >= (pageData.totalPages || 1) || loading}>
        下一页
      </button>
      <select value={pageSize} onChange={e => { setPageSize(Number(e.target.value)); setPage(0) }}>
        <option value="5">5 条/页</option>
        <option value="10">10 条/页</option>
        <option value="20">20 条/页</option>
      </select>
    </div>
  )
}
