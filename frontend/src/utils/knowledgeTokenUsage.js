const reported = value => Number.isSafeInteger(value) && value >= 0

export function embeddingUsageLabel(inputTokens, totalTokens) {
  if (reported(inputTokens)) return `实际输入 ${inputTokens} tokens`
  if (reported(totalTokens)) return `接口总用量 ${totalTokens} tokens`
  return '接口用量未记录'
}
