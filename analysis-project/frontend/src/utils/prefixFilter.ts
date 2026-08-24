/**
 * 下拉模糊匹配:按名称、编码、描述等文本做包含匹配。
 * Element Plus el-select 的 filter-method 只接收 query,不能当成 option predicate 使用。
 */
export function prefixFilter(query: string, item: unknown): boolean {
  if (!query) return true;
  const q = normalizeSearchText(query);
  if (!q) return true;
  const label = optionSearchText(item);
  return label.includes(q);
}

export function normalizeSearchText(value: unknown): string {
  return String(value ?? '').trim().toLowerCase();
}

export function optionSearchText(item: unknown): string {
  const raw = (item as { label?: unknown } | null)?.label ?? item ?? '';
  const label = String(raw);
  const match = label.match(/^(.*?)\s*\(([^)]*)\)\s*$/);
  const name = match ? match[1].trim() : label.trim();
  const code = match ? match[2].trim() : '';
  return normalizeSearchText([label, name, code].filter(Boolean).join(' '));
}

export function matchesFuzzyQuery(query: string, values: unknown[]): boolean {
  const q = normalizeSearchText(query);
  if (!q) return true;
  return values.some(value => normalizeSearchText(value).includes(q));
}
