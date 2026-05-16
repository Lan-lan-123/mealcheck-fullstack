export default function MiniBars({ items = [], emptyText }) {
  const max = Math.max(1, ...items.map(item => Number(item.value) || 0))

  if (!items.length) {
    return <p className="empty-mini">{emptyText}</p>
  }

  return (
    <div className="mini-bars">
      {items.map(item => (
        <div className="mini-bar-row" key={item.label}>
          <span>{item.label}</span>
          <div className="mini-bar-track">
            <i style={{ width: `${Math.max(8, ((Number(item.value) || 0) / max) * 100)}%` }} />
          </div>
          <strong>{item.value}</strong>
        </div>
      ))}
    </div>
  )
}
