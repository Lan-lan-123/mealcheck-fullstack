import { goals } from '../userWorkspaceConfig'

export default function DietGoalsPanel({ goalLabel, goalDraft, setGoalDraft, saveGoal, loading }) {
  return (
    <section className="card goal-settings"><h2>我的饮食计划</h2><p className="hint">分析餐食时选择“使用当前目标”，将按照这里保存的目标提供建议。</p>
                <form className="goal-form" onSubmit={saveGoal}>
                  <div>
                    <strong>当前目标</strong>
                    <span>{goalLabel}</span>
                  </div>
                  <label className="form-field">饮食目标<select aria-label="当前饮食目标" value={goalDraft.goalType} onChange={e => setGoalDraft({ ...goalDraft, goalType: e.target.value })}>
                    {goals.filter(item => item.value !== 'current').map(g => (
                      <option key={g.value} value={g.value}>{g.label}</option>
                    ))}
                  </select></label>
                  <label className="form-field">结束日期（可选）<input type="date" aria-label="目标结束日期" value={goalDraft.endDate} onChange={e => setGoalDraft({ ...goalDraft, endDate: e.target.value })} /></label>
                  <label className="form-field form-field-wide">目标说明<input aria-label="目标说明" value={goalDraft.note} onChange={e => setGoalDraft({ ...goalDraft, note: e.target.value })} placeholder="目标说明" /></label>
                  <button className="secondary-btn" type="submit" disabled={loading}>保存目标</button>
                </form>
        </section>
  )
}
