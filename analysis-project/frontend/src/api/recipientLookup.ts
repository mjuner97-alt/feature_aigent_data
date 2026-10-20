type Person = { userId: string; name: string; department?: string | null };
export async function lookupRecipients<T extends Person>(input: string, search: (keyword: string) => Promise<T[]>, batch = false) {
  const tokens = [...new Set(input.split(/[\s,，;；、]+/).filter(Boolean))];
  const rows: { token: string; people: T[]; failed: boolean }[] = [];
  for (let i = 0; i < tokens.length; i += 8) {
    rows.push(...await Promise.all(tokens.slice(i, i + 8).map(async token => {
      try { return { token, people: await search(token), failed: false }; }
      catch { return { token, people: [] as T[], failed: true }; }
    })));
  }
  const items = new Map<string, T>(), matched = new Map<string, T>();
  const missing: string[] = [], failed: string[] = [], ambiguous: string[] = [];
  for (const row of rows) {
    if (row.failed) { failed.push(row.token); continue; }
    const people = [...new Map(row.people.map(p => [p.userId, p])).values()];
    if (!people.length) { missing.push(row.token); continue; }
    if (batch) {
      const id = people.find(p => p.userId === row.token);
      const names = people.filter(p => p.name === row.token);
      // The search API returns at most five rows. A full page cannot prove uniqueness.
      const hit = id ?? (row.people.length < 5 && names.length === 1 ? names[0] : undefined);
      if (hit) { matched.set(hit.userId, hit); continue; }
      ambiguous.push(row.token);
    }
    for (const person of people) items.set(person.userId, person);
  }
  return { items: [...items.values()], matched: [...matched.values()], missing, failed, ambiguous };
}

export function lookupMessage(result: { items: unknown[]; matched: unknown[]; missing: string[]; failed: string[]; ambiguous: string[] }, batch = false) {
  return [batch ? `已匹配 ${result.matched.length} 人` : `找到 ${result.items.length} 人`,
    result.missing.length ? `未匹配：${result.missing.join('、')}` : '',
    result.ambiguous.length ? `需从候选中选择（同名、非完整姓名或结果较多）：${result.ambiguous.join('、')}` : '',
    result.failed.length ? `查询失败，请重试：${result.failed.join('、')}` : '',
  ].filter(Boolean).join('；');
}
