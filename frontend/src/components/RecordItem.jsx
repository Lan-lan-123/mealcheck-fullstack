import { formatTime } from '../utils/time'
export default function RecordItem({ record, onDelete, onCorrect, onView }) {
  return (
    <article className="record">
      <div className="record-head">
        <div>
          <strong>{record.score} 分</strong>
          <span>{formatTime(record.createdAt)}</span>
        </div>

        <div>{onView && <button className="secondary-btn" type="button" onClick={() => onView(record)}>查看餐食详情</button>}<button className="secondary-btn" type="button" onClick={() => onCorrect(record)}>纠正识别</button>
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
