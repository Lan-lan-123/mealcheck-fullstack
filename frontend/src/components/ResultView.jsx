import { categoryName } from '../utils/mealLabels'

export default function ResultView({ result, compact = false, onViewDetails }) {
  const evaluation = result?.evaluation || {}
  const recognition = result?.recognition || {}
  const foods = Array.isArray(recognition.foods) ? recognition.foods : []
  const riskTags = Array.isArray(evaluation.riskTags) ? evaluation.riskTags : []
  const references = Array.isArray(result?.references) ? result.references : []

  return (
    <div className={compact ? 'result-summary' : 'result-detail'}>
      <div className="score-circle">
        <strong>{evaluation.score ?? '--'}</strong>
        <span>分</span>
      </div>

      <p className="summary">{evaluation.summary || '暂无分析摘要'}</p>

      {recognition.demoMode && <span className="badge warn">演示识别模式</span>}

      <h3>识别食物</h3>
      <div className="chips">
        {(compact ? foods.slice(0, 4) : foods).map((f, i) => (
          <span key={i}>
            {f.name} · {categoryName(f.category)}
          </span>
        ))}
        {compact && foods.length > 4 && <span>另有 {foods.length - 4} 项</span>}
      </div>

      <h3>风险标签</h3>
      <div className="chips risk">
        {riskTags.length ? (
          (compact ? riskTags.slice(0, 3) : riskTags).map(x => <span key={x}>{x}</span>)
        ) : (
          <span>暂无明显风险</span>
        )}
      </div>

      {compact && <><h3>饮食建议摘要</h3><p className="advice-summary">{result?.advice || '暂无建议'}</p></>}
      {!compact && <>
      <h3>RAG 饮食建议</h3>
      <pre className="advice">{result?.advice || '暂无建议'}</pre>

      <h3>参考知识片段</h3>
      {references.map(ref => (
        <details key={ref.id}>
          <summary>
            {ref.title} · 相似度 {Number(ref.score).toFixed(2)}
          </summary>
          <p>{ref.content}</p>
        </details>
      ))}
      </>}
      {compact && <button className="secondary-btn" type="button" onClick={onViewDetails}>查看完整分析与建议</button>}
    </div>
  )
}
