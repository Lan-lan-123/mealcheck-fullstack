import { formatTime } from '../utils/time'
import { useEffect, useRef, useState } from 'react'
import { adminMealCorrections, adminReviewMealCorrection, adminMealCorrectionEvaluation, adminRunMealEvaluation,
  adminMealEvaluationHistory, adminMealEvaluationDetail, adminCompareMealEvaluations } from '../api/client'
import { categoryName } from '../utils/mealLabels'
import SectionSwitcher from './SectionSwitcher'

const categories = ['staple', 'protein', 'vegetable', 'fruit', 'dairy', 'soup', 'drink', 'dessert', 'fried', 'oily', 'other']
export default function MealCorrectionEvaluationPanel() {
  const [section, setSection] = useState('results')
  const [page, setPage] = useState(0), [historyPage, setHistoryPage] = useState(0)
  const [items, setItems] = useState([]), [history, setHistory] = useState([])
  const [evaluation, setEvaluation] = useState(null), [viewedRun, setViewedRun] = useState(null)
  const [selected, setSelected] = useState(null), [foods, setFoods] = useState([])
  const [expectedScore, setExpectedScore] = useState(''), [confirmed, setConfirmed] = useState(false)
  const [busy, setBusy] = useState(false), [loading, setLoading] = useState(false), [error, setError] = useState('')
  const [base, setBase] = useState(''), [candidate, setCandidate] = useState(''), [comparison, setComparison] = useState(null)
  const current = useRef(null)
  async function load() {
    current.current?.abort()
    const controller = new AbortController(); current.current = controller
    setLoading(true); setError('')
    try {
      const [rows, result, runs] = await Promise.all([adminMealCorrections(page, controller.signal),
        adminMealCorrectionEvaluation(controller.signal), adminMealEvaluationHistory(historyPage, controller.signal)])
      if (controller.signal.aborted) return
      setItems(rows); setEvaluation(result); setHistory(runs); setViewedRun(null)
    } catch (err) { if (!controller.signal.aborted) setError(err.message) }
    finally { if (current.current === controller) setLoading(false) }
  }
  useEffect(() => { load(); return () => current.current?.abort() }, [page, historyPage])
  function select(item) {
    setSelected(item); setExpectedScore(item.expectedScore ?? ''); setConfirmed(false)
    setFoods(JSON.parse(item.afterFoods).map(({ name, category }) => ({ name, category })))
  }
  function change(index, field, value) { setFoods(rows => rows.map((food, i) => i === index ? { ...food, [field]: value } : food)) }
  async function action(work) {
    if (busy) return
    setBusy(true); setError('')
    try { await work() } catch (err) { if (err.name !== 'AbortError') setError(err.message) } finally { setBusy(false) }
  }
  async function review(status) {
    await adminReviewMealCorrection(selected.id, { status, foods, expectedScore: Number(expectedScore), sanitizedConfirmed: confirmed })
    setSelected(null); setSection('results'); await load()
  }
  async function view(id) {
    current.current?.abort()
    const controller = new AbortController(); current.current = controller
    const detail = await adminMealEvaluationDetail(id, controller.signal)
    if (!controller.signal.aborted) { setEvaluation(detail); setViewedRun(id); setSection('results') }
  }
  function download() {
    const artifact = { datasetVersion: evaluation.datasetVersion, datasetFingerprint: evaluation.datasetFingerprint,
      scoringVersion: evaluation.scoringVersion,
      cases: evaluation.cases.map(({ id, goal, foods, expectedScore }) => ({ id, goal, foods, expectedScore })) }
    const url = URL.createObjectURL(new Blob([JSON.stringify(artifact, null, 2)], { type: 'application/json' }))
    const link = document.createElement('a'); link.href = url; link.download = 'meal-correction-regression.json'; link.click(); URL.revokeObjectURL(url)
  }
  const disabled = busy || loading
  return <section className="admin-section">
    <h2>识别纠错与评分回归</h2>
    <p>编辑脱敏食物名称并独立标注期望分数。审核后执行评测，保存固定数据集与历史结果。</p>
    {error && <p className="error" role="alert">{error}</p>}{loading && <p role="status">加载中…</p>}
    <SectionSwitcher label="识别纠错评测工作区" value={section} onChange={setSection} items={[["results", "评分回归结果"], ["history", "评测历史与对比"], ["review", "纠错审核"]]} />
    <div className="section-panel" hidden={section !== 'results'}>
    {evaluation && <>
      <p>{viewedRun ? `历史评测 #${viewedRun}` : '当前审核集'} · {evaluation.scoringVersion} · {evaluation.caseCount} 题 · 失败 {evaluation.failedCount} 题 ·
        {evaluation.caseCount ? (evaluation.passed ? '回归通过' : '回归失败') : '尚无审核样本'}</p>
      <p className="dataset-fingerprint">数据集指纹：{evaluation.datasetFingerprint}</p>
      <div className="evaluation-actions">
        <button type="button" disabled={!evaluation.caseCount || disabled} onClick={download}>导出脱敏回归集</button>
        <button type="button" disabled={disabled} onClick={() => action(async () => { await adminRunMealEvaluation(); await load() })}>运行并保存评测</button>
        <button type="button" disabled={disabled} onClick={load}>查看当前审核集</button>
      </div>
      <div className="evaluation-table" tabIndex={0} aria-label="逐题评分结果"><table>
        <thead><tr><th>案例</th><th>目标</th><th>期望</th><th>实际</th><th>结果</th></tr></thead>
        <tbody>{evaluation.cases.map(row => <tr key={row.id}><td>{row.id}</td><td>{row.goal}</td><td>{row.expectedScore}</td><td>{row.actualScore}</td><td>{row.passed ? '通过' : '失败'}</td></tr>)}</tbody>
      </table></div>
    </>}
    </div>
    <div className="section-panel" hidden={section !== 'history'}>
    <h3>评测历史</h3>
    <div className="evaluation-actions">{history.map(run => <button type="button" key={run.id} disabled={disabled}
      onClick={() => action(() => view(run.id))}>#{run.id} · {formatTime(run.createdAt)} · 失败 {run.failedCount}/{run.caseCount}</button>)}</div>
    <button type="button" disabled={disabled || historyPage === 0} onClick={() => setHistoryPage(value => value - 1)}>历史上一页</button>
    <button type="button" disabled={disabled || history.length < 20} onClick={() => setHistoryPage(value => value + 1)}>历史下一页</button>
    <div className="evaluation-actions">
      <label>基线评测<select value={base} onChange={event => { setBase(event.target.value); setComparison(null) }}><option value="">请选择</option>{history.map(run => <option key={run.id} value={run.id}>#{run.id}</option>)}</select></label>
      <label>候选评测<select value={candidate} onChange={event => { setCandidate(event.target.value); setComparison(null) }}><option value="">请选择</option>{history.map(run => <option key={run.id} value={run.id}>#{run.id}</option>)}</select></label>
      <button type="button" disabled={!base || !candidate || disabled} onClick={() => action(async () => setComparison(await adminCompareMealEvaluations(base, candidate)))}>比较评测</button>
    </div>
    {comparison && <div><p>{comparison.comparable ? '同一数据集，可比较评分变化。' : '数据集输入或标签不同，请勿直接比较通过率。'}</p>
      <ul>{comparison.differences.map(row => <li key={row.id}>{row.id}：{row.before ?? '未包含'} → {row.after ?? '未包含'} · {row.beforePassed ? '通过' : '失败或未包含'} → {row.afterPassed ? '通过' : '失败或未包含'}</li>)}</ul></div>}
    </div>
    <div className="section-panel" hidden={section !== 'review'}>
    <h3>纠错审核</h3>
    <div className="record-list">{items.map(item => <article className="record" key={item.id}>
      <strong>纠错 #{item.id} · 第 {item.revision} 次修改 · {item.status}</strong>
      <p>原识别：{JSON.parse(item.beforeFoods).map(food => food.name).join('、')}</p>
      <p>纠正后：{JSON.parse(item.afterFoods).map(food => food.name).join('、')}</p>
      <p>修改原因：{item.reason}</p><button type="button" disabled={disabled} onClick={() => select(item)}>审核样本</button>
    </article>)}</div>
    <button type="button" disabled={page === 0 || disabled} onClick={() => setPage(value => value - 1)}>上一页</button>
    <span>第 {page + 1} 页</span><button type="button" disabled={items.length < 50 || disabled} onClick={() => setPage(value => value + 1)}>下一页</button>
    {selected && <form className="evaluation-review" onSubmit={event => { event.preventDefault(); action(() => review('APPROVED')) }}>
      <h3>审核 #{selected.id}</h3>
      {foods.map((food, index) => <div className="correction-row" key={index}>
        <input aria-label={`脱敏食物名称 ${index + 1}`} required maxLength={100} disabled={busy} value={food.name} onChange={event => change(index, 'name', event.target.value)} />
        <select aria-label={`脱敏食物分类 ${index + 1}`} disabled={busy} value={food.category} onChange={event => change(index, 'category', event.target.value)}>{categories.map(category => <option key={category} value={category}>{categoryName(category)}</option>)}</select>
        <button type="button" disabled={busy} onClick={() => setFoods(rows => rows.filter((_, i) => i !== index))}>移除</button>
      </div>)}
      <button type="button" disabled={busy || foods.length >= 30} onClick={() => setFoods(rows => [...rows, { name: '', category: 'other' }])}>添加脱敏食物</button>
      <label>人工期望评分<input type="number" min="0" max="100" required disabled={busy} value={expectedScore} onChange={event => setExpectedScore(event.target.value)} /></label>
      <label><input type="checkbox" required disabled={busy} checked={confirmed} onChange={event => setConfirmed(event.target.checked)} />已核对评分，并移除姓名、联系方式等个人信息</label>
      <button disabled={busy || !confirmed || expectedScore === '' || !foods.length}>审核通过并加入回归集</button>
      <button type="button" disabled={busy || !confirmed || expectedScore === '' || !foods.length} onClick={() => action(() => review('REJECTED'))}>拒绝</button>
      <button type="button" disabled={busy} onClick={() => setSelected(null)}>取消</button>
    </form>}
    </div>
  </section>
}
