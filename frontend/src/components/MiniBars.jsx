import { scaleBars } from '../utils/barScale'

export default function MiniBars({ items = [], emptyText }) {
  const bars = scaleBars(items)

  if (!items.length) {
    return <p className="empty-mini">{emptyText}</p>
  }

  return (
    <div className="mini-bars">
      {items.map((item, index) => (
        <div className="mini-bar-row" key={`${item.label}-${index}`}>
          <span>{item.label}</span>
          <div className="mini-bar-track" aria-hidden="true">
            {bars[index].baseline > 0 && <b className="mini-bar-baseline" style={{ left: `${bars[index].baseline}%` }} />}
            <i style={{ width: `${bars[index].width}%`, marginLeft: `${bars[index].left}%` }} />
          </div>
          <strong>{bars[index].value === null ? '暂无' : item.value}</strong>
        </div>
      ))}
    </div>
  )
}
