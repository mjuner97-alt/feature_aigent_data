/**
 * 返回任务有效执行时间的安全起点。
 * 历史数据可能在父记录落库前写入 startedAt，因此执行起点不能早于 createdAt。
 */
export function activeDurationStart(createdAt, startedAt) {
  if (!createdAt) return startedAt ?? null;
  if (!startedAt) return null;
  return new Date(startedAt).getTime() < new Date(createdAt).getTime() ? createdAt : startedAt;
}
