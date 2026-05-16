export default function RecordItem({ record, onDelete }) {
  return (
    <article className="record">
      <div className="record-head">
        <div>
          <strong>{record.score} 分</strong>
          <span>{new Date(record.createdAt).toLocaleString()}</span>
        </div>

        <button className="delete-btn" onClick={() => onDelete(record.id)} type="button">
          删除
        </button>
      </div>

      <p>{record.summary}</p>

      <div className="chips small">
        {record.foods?.slice(0, 6).map((f, i) => (
          <span key={i}>{f.name}</span>
        ))}
      </div>
    </article>
  )
}
