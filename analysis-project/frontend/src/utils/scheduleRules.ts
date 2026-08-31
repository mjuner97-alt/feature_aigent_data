export type ScheduleRules = Record<string, string[]>;

export function parseScheduleRules(value?: string | null): ScheduleRules {
  if (!value) return {};
  try {
    const parsed = JSON.parse(value) as unknown;
    if (Array.isArray(parsed)) return Object.fromEntries(parsed.filter(day => typeof day === 'string').map(day => [day, []]));
    if (!parsed || typeof parsed !== 'object') return {};
    return Object.fromEntries(Object.entries(parsed).filter(([, times]) => Array.isArray(times))
      .map(([day, times]) => [day, (times as unknown[]).filter(t => /^\d{2}:\d{2}$/.test(String(t))).map(String)]));
  } catch { return {}; }
}

export function stringifyScheduleRules(rules: ScheduleRules): string | null {
  const days = Object.keys(rules);
  return days.length ? JSON.stringify(days) : null;
}
