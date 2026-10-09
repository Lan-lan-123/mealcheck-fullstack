import FilterDisclosure from '../../components/FilterDisclosure'
import { formatTime, MetricSmall, Chart, FoodTags, Pagination } from './AdminUi'

export default function MealManagementPanel({ applyMealFilters, defaultMealFilters, deletingMealId, handleDeleteMeal, handleDeleteSelectedMeals, loading, mealAnalytics, mealFiltersDraft, mealPageData, mealSize, meals, selectedMealIds, setMealFilters, setMealFiltersDraft, setMealPage, setMealSize, setShowMealManager, showMealManager, toggleAllMealSelection, toggleMealSelection }) {
  return (
        <section className="admin-section">
          <div className="admin-section-head">
            <div>
              <h2>全站饮食记录</h2>
              <p>按用户、日期和评分筛选记录，并查看整体可视化分析。</p>
            </div>
            <button className={showMealManager ? 'primary-btn' : 'secondary-btn'} type="button" onClick={() => setShowMealManager(value => !value)}>{showMealManager ? '收起管理' : '管理全体饮食记录'}</button>
          </div>

          {showMealManager && (
            <div className="meal-manager">
              <FilterDisclosure activeCount={Object.values(mealFiltersDraft).filter(Boolean).length}><form className="filter-grid" onSubmit={applyMealFilters}>
                <input value={mealFiltersDraft.username} onChange={e => setMealFiltersDraft({ ...mealFiltersDraft, username: e.target.value })} placeholder="用户名" />
                <label className="form-field">开始日期<input type="date" max={mealFiltersDraft.to || undefined} value={mealFiltersDraft.from} onChange={e => setMealFiltersDraft({ ...mealFiltersDraft, from: e.target.value })} /></label>
                <label className="form-field">结束日期<input type="date" min={mealFiltersDraft.from || undefined} value={mealFiltersDraft.to} onChange={e => setMealFiltersDraft({ ...mealFiltersDraft, to: e.target.value })} /></label>
                <label className="form-field">最低评分<input type="number" min="0" max="100" value={mealFiltersDraft.minScore} onChange={e => setMealFiltersDraft({ ...mealFiltersDraft, minScore: e.target.value })} placeholder="最低评分" /></label>
                <label className="form-field">最高评分<input type="number" min="0" max="100" value={mealFiltersDraft.maxScore} onChange={e => setMealFiltersDraft({ ...mealFiltersDraft, maxScore: e.target.value })} placeholder="最高评分" /></label>
                <button className="secondary-btn" type="submit">筛选</button>
                <button className="secondary-btn" type="button" onClick={() => { setMealFiltersDraft(defaultMealFilters); setMealFilters(defaultMealFilters); setMealPage(0) }}>重置</button>
              </form></FilterDisclosure>
              <div className="meal-manager-actions">
                <button className="secondary-btn" type="button" onClick={toggleAllMealSelection}>{selectedMealIds.length === meals.length && meals.length > 0 ? '取消全选' : '全选本页'}</button>
                <button className="table-danger-btn" type="button" onClick={handleDeleteSelectedMeals} disabled={!selectedMealIds.length || loading}>批量删除{selectedMealIds.length ? ` (${selectedMealIds.length})` : ''}</button>
              </div>
            </div>
          )}

          <div className="meal-analytics-grid">
            <MetricSmall label="筛选记录数" value={mealAnalytics?.total ?? 0} />
            <MetricSmall label="平均评分" value={mealAnalytics?.averageScore ?? 0} />
            <Chart title="评分分布" items={mealAnalytics?.scoreBands} empty="暂无评分数据" />
            <Chart title="用户提交量" items={mealAnalytics?.userCounts} empty="暂无用户数据" />
            <Chart title="常见食物" items={mealAnalytics?.foodCounts} empty="暂无食物数据" wide />
            <Chart title="低分原因/风险" items={mealAnalytics?.riskTagCounts} empty="暂无风险数据" wide />
            <Chart title="平均分趋势" items={mealAnalytics?.dailyAverageScores} empty="暂无趋势数据" wide trend />
          </div>

          <div className="admin-table-wrap">
            <table className="admin-table">
              <thead>
                <tr>{showMealManager && <th>选择</th>}<th>ID</th><th>用户</th><th>评分</th><th>识别食物</th><th>摘要</th><th>提交时间</th>{showMealManager && <th>操作</th>}</tr>
              </thead>
              <tbody>
                {meals.length ? meals.map(meal => (
                  <tr key={meal.id}>
                    {showMealManager && <td><input type="checkbox" checked={selectedMealIds.includes(meal.id)} onChange={() => toggleMealSelection(meal.id)} /></td>}
                    <td>{meal.id}</td>
                    <td>{meal.username || '未知用户'}</td>
                    <td><strong>{meal.score ?? '暂无'}</strong></td>
                    <td><FoodTags names={meal.foodNames} /></td>
                    <td className="summary-cell">{meal.summary || '暂无'}</td>
                    <td>{formatTime(meal.createdAt)}</td>
                    {showMealManager && <td><button className="table-danger-btn" type="button" onClick={() => handleDeleteMeal(meal)} disabled={deletingMealId === meal.id || loading}>{deletingMealId === meal.id ? '删除中...' : '删除'}</button></td>}
                  </tr>
                )) : <tr><td colSpan={showMealManager ? 8 : 6} className="empty-cell">暂无饮食记录</td></tr>}
              </tbody>
            </table>
          </div>
          <Pagination pageData={mealPageData} pageSize={mealSize} setPage={setMealPage} setPageSize={setMealSize} loading={loading} />
        </section>
  )
}
