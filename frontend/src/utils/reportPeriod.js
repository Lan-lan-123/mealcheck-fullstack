export function reportPeriod(report) {
  const from = report?.periodStart
  const to = report?.periodEnd
  if (!/^\d{4}-\d{2}-\d{2}$/.test(from || '') || !/^\d{4}-\d{2}-\d{2}$/.test(to || '') || from > to) {
    throw new Error('报告缺少有效统计日期，请重新生成周报。')
  }
  return { from, to }
}
