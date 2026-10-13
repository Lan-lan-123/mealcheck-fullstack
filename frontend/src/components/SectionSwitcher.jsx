export default function SectionSwitcher({ label, items, value, onChange }) {
  return <div className="section-switcher" role="group" aria-label={label}>
    {items.map(([key, title]) => <button type="button" key={key} aria-pressed={value === key}
      className={value === key ? 'active' : ''} onClick={() => onChange(key)}>{title}</button>)}
  </div>
}
