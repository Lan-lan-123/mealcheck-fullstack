import { formatTime, mealTypes } from '../utils/time'
export default function RecordItem({ record, onDelete, onCorrect, onView, onEdit }) {
  return (
    <article className="record">
      <div className="record-head">
        <div>
          <strong>{record.score} 分</strong>
          <span>{mealTypes[record.mealType] || "未指定餐次"} · {record.recordSource === "MANUAL" ? "手动" : "图片"}{!record.timeConfirmed && " · 时间待确认"}</span>
          <span>{formatTime(record.occurredAt || record.createdAt)}</span>
        </div>

        <div>{onEdit && <button className="secondary-btn" type="button" onClick={() => onEdit(record)}>修改用餐信息</button>}{onView && <button className="secondary-btn" type="button" onClick={() => onView(record)}>查看餐食详情</button>}<button className="secondary-btn" type="button" onClick={() => onCorrect(record)}>纠正识别</button>
        <button className="delete-btn" onClick={() => onDelete(record.id)} type="button">
          删除
        </button></div>
      </div>

      <p className="record-summary">{record.summary}</p>

      <div className="chips small">
        {record.foods?.slice(0, 6).map((f, i) => (
          <span key={i}>{f.name}</span>
        ))}
      </div>
    </article>
  )
}
