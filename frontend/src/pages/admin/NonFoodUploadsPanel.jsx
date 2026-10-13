import { formatTime, Pagination } from './AdminUi'

export default function NonFoodUploadsPanel({ applyNonFoodFilters, defaultNonFoodFilters, loading, nonFoodFiltersDraft, nonFoodPageData, nonFoodSize, nonFoodUploads, setNonFoodFilters, setNonFoodFiltersDraft, setNonFoodPage, setNonFoodSize }) {
  return (
        <section className="admin-section">
          <div className="admin-section-head">
            <div>
              <h2>异常上传记录</h2>
              <p>查看用户上传非饮食图片的历史记录、窗口内次数、频率和是否触发管理员预警。</p>
            </div>
          </div>

          <form className="filter-grid compact" onSubmit={applyNonFoodFilters}>
            <input value={nonFoodFiltersDraft.username} onChange={e => setNonFoodFiltersDraft({ ...nonFoodFiltersDraft, username: e.target.value })} placeholder="按用户名筛选" />
            <label className="inline-check">
              <input type="checkbox" checked={nonFoodFiltersDraft.blockedOnly} onChange={e => setNonFoodFiltersDraft({ ...nonFoodFiltersDraft, blockedOnly: e.target.checked })} />
              只看限制中用户
            </label>
            <button className="secondary-btn" type="submit">筛选</button>
            <button className="secondary-btn" type="button" onClick={() => { setNonFoodFiltersDraft(defaultNonFoodFilters); setNonFoodFilters(defaultNonFoodFilters); setNonFoodPage(0) }}>重置</button>
          </form>

          <div className="admin-table-wrap">
            <table className="admin-table">
              <thead>
                <tr><th>ID</th><th>用户</th><th>昵称</th><th>窗口内次数</th><th>频率</th><th>当前状态</th><th>是否预警</th><th>原因</th><th>时间</th></tr>
              </thead>
              <tbody>
                {nonFoodUploads.length ? nonFoodUploads.map(item => (
                  <tr key={item.id}>
                    <td>{item.id}</td>
                    <td>{item.username}</td>
                    <td>{item.displayName || '暂无'}</td>
                    <td>{item.windowMinutes} 分钟内 {item.windowCount} 次</td>
                    <td>{item.windowCount} 次 / {item.observedMinutes || 1} 分钟（{item.perMinuteRate} 次/分钟）</td>
                    <td><span className={item.currentlyBlocked ? 'status-bad' : 'status-ok'}>{item.currentlyBlocked ? '限制中' : '可上传'}</span></td>
                    <td><span className={item.thresholdReached ? 'status-bad' : 'status-ok'}>{item.thresholdReached ? '已预警' : '未达阈值'}</span></td>
                    <td className="summary-cell">{item.reason || '暂无'}</td>
                    <td>{formatTime(item.createdAt)}</td>
                  </tr>
                )) : <tr><td colSpan="9" className="empty-cell">暂无异常上传记录</td></tr>}
              </tbody>
            </table>
          </div>
          <Pagination pageData={nonFoodPageData} pageSize={nonFoodSize} setPage={setNonFoodPage} setPageSize={setNonFoodSize} loading={loading} />
        </section>
  )
}
