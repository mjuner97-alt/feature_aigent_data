/**
 * 返回任务有效执行时间的安全起点。
 * 历史数据可能在父记录落库前写入 startedAt，因此执行起点不能早于 createdAt。
 */
export function activeDurationStart(createdAt, startedAt) {
  if (!createdAt) return startedAt ?? null;
  if (!startedAt) return null;
  return new Date(startedAt).getTime() < new Date(createdAt).getTime() ? createdAt : startedAt;
}

/**
 * 把秒数格式化为可读时长:秒 < 1分钟 < 1小时 < 1天,如 45s / 5m30s / 30m / 2h15m / 1h / 3d4h。
 * 低级单位为 0 时省略(30m0s 显示为 30m);无效输入(空/负数/非有限数)返回 null,由调用方决定占位符。
 */
export function formatDuration(seconds) {
  if (seconds == null || seconds === '') return null;
  const n = Number(seconds);
  if (!Number.isFinite(n) || n < 0) return null;
  const s = Math.round(n);
  if (s < 60) return `${s}s`;
  const m = Math.floor(s / 60);
  if (m < 60) return s % 60 ? `${m}m${s % 60}s` : `${m}m`;
  const h = Math.floor(m / 60);
  if (h < 24) return m % 60 ? `${h}h${m % 60}m` : `${h}h`;
  return h % 24 ? `${Math.floor(h / 24)}d${h % 24}h` : `${Math.floor(h / 24)}d`;
}
