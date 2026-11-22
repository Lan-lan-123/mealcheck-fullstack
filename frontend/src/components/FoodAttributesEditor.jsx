/** @param {{attributes?: import('../api/contracts').FoodAttributes | null; onChange: (value: import('../api/contracts').FoodAttributes) => void; disabled?: boolean; labelPrefix: string}} props */
export default function FoodAttributesEditor({ attributes, onChange, disabled = false, labelPrefix }) {
  function field(key, label) {
    const value = attributes?.[key]
    return <label>{label}<select aria-label={`${labelPrefix} ${label}`} disabled={disabled}
      value={value == null ? 'unknown' : String(value)}
      onChange={event => onChange({ fried: attributes?.fried ?? null, highOil: attributes?.highOil ?? null,
        [key]: event.target.value === 'unknown' ? null : event.target.value === 'true' })}>
      <option value="unknown">待核对</option><option value="true">是</option><option value="false">否</option>
    </select></label>
  }
  return <div className="food-attributes-editor">{field('fried', '油炸做法')}{field('highOil', '高油风险')}</div>
}
