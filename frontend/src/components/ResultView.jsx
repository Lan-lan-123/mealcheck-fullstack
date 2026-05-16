import { categoryName } from '../utils/mealLabels'

export default function ResultView({ result }) {
  const evaluation = result?.evaluation || {}
  const recognition = result?.recognition || {}
  const foods = Array.isArray(recognition.foods) ? recognition.foods : []
  const riskTags = Array.isArray(evaluation.riskTags) ? evaluation.riskTags : []
  const references = Array.isArray(result?.references) ? result.references : []

  return (
    <div>
      <div className="score-circle">
        <strong>{evaluation.score ?? '--'}</strong>
        <span>分</span>
      </div>

      <p className="summary">{evaluation.summary || '暂无分析摘要'}</p>

      {recognition.demoMode && <span className="badge warn">演示识别模式</span>}

      <h3>识别食物</h3>
      <div className="chips">
        {foods.map((f, i) => (
          <span key={i}>
            {f.name} · {categoryName(f.category)}
          </span>
        ))}
      </div>

      <h3>风险标签</h3>
      <div className="chips risk">
        {riskTags.length ? (
          riskTags.map(x => <span key={x}>{x}</span>)
        ) : (
          <span>暂无明显风险</span>
        )}
      </div>

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
    </div>
  )
}
