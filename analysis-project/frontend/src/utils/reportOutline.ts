import type { OutlineNumberingStyle, ReportOutline, ReportOutlineItem, ReportOutlineNumbering } from '../types/skillFlow';

/** 编辑器使用的扁平行:文档顺序即章节顺序,支持任意正整数层级;nodeKeys 为章节绑定的节点(可多个,按序渲染)。 */
export interface OutlineRow {
  id: string;
  title: string;
  level: number;
  nodeKeys: string[];
}

export function defaultOutlineNumbering(): ReportOutlineNumbering {
  return { level1: 'chinese', level2: 'arabic', level3: 'arabic' };
}

let rowSeq = 0;

/** 新建一行,id 带时间戳保证前后端唯一。 */
export function newRow(level: number): OutlineRow {
  rowSeq += 1;
  return { id: `outline_${Date.now()}_${rowSeq}`, title: '', level, nodeKeys: [] };
}

/** 嵌套大纲 -> 编辑扁平行(先序遍历;老数据/坏数据尽量容错)。 */
export function flattenOutline(outline: ReportOutline | null | undefined): OutlineRow[] {
  const rows: OutlineRow[] = [];
  const walk = (items?: ReportOutlineItem[]) => {
    for (const item of items || []) {
      const nodeKeys = item.nodeKeys?.length ? [...item.nodeKeys] : item.nodeKey ? [item.nodeKey] : [];
      rows.push({
        id: item.id || newRow(1).id,
        title: item.title || '',
        level: Math.max(1, item.level || 1),
        nodeKeys,
      });
      walk(item.children);
    }
  };
  walk(outline?.items);
  return rows;
}

/** 编辑扁平行 -> 嵌套大纲;无行返回 null(未配置,后端走兼容拼接)。层级跳级由后端校验兜底。 */
export function buildOutline(rows: OutlineRow[], numbering: ReportOutlineNumbering, title = ''): ReportOutline | null {
  if (!rows.length) return null;
  const roots: ReportOutlineItem[] = [];
  const stack: ReportOutlineItem[] = [];
  for (const row of rows) {
    const item: ReportOutlineItem = {
      id: row.id,
      title: row.title.trim(),
      level: row.level,
      nodeKey: row.nodeKeys[0] ?? null,
      nodeKeys: [...row.nodeKeys],
      children: [],
    };
    while (stack.length >= row.level) stack.pop();
    const parent = stack[stack.length - 1];
    if (parent) parent.children?.push(item);
    else roots.push(item);
    stack.push(item);
  }
  return { title: title.trim(), numbering, items: roots };
}

const CHINESE_DIGITS = ['', '一', '二', '三', '四', '五', '六', '七', '八', '九'];

function chineseNumber(n: number): string {
  if (n <= 0 || n >= 100) return String(n);
  if (n < 10) return CHINESE_DIGITS[n];
  const tens = Math.floor(n / 10) === 1 ? '十' : CHINESE_DIGITS[Math.floor(n / 10)] + '十';
  return tens + CHINESE_DIGITS[n % 10];
}

function styleFor(numbering: ReportOutlineNumbering, level: number): OutlineNumberingStyle {
  if (level === 1) return 'chinese';
  const value = level === 1 ? numbering.level1 : level === 2 ? numbering.level2 : numbering.level3;
  return value ?? (level === 1 ? 'chinese' : 'arabic');
}

/** 每行自动编号前缀(与后端 composer 同口径):chinese=一、;arabic=完整路径 1 / 1.1(含中文样式的祖先层级);none=空。 */
export function outlineNumberingPrefixes(rows: OutlineRow[], numbering: ReportOutlineNumbering): string[] {
  const counters: number[] = [];
  return rows.map(row => {
    const level = Math.max(1, row.level);
    while (counters.length < level) counters.push(0);
    counters[level - 1] += 1;
    counters.length = level;
    const style = styleFor(numbering, level);
    if (style === 'chinese') return `${chineseNumber(counters[level - 1])}、`;
    if (style === 'arabic') return counters.slice(0, level).join('.');
    return '';
  });
}

/** 计算每行标题(含编号前缀,与后端 composer 同口径):chinese=一、;arabic=1 / 1.1。 */
export function outlineHeadings(rows: OutlineRow[], numbering: ReportOutlineNumbering): string[] {
  const prefixes = outlineNumberingPrefixes(rows, numbering);
  return rows.map((row, index) => `${prefixes[index] ? prefixes[index] + ' ' : ''}${row.title.trim() || '(未命名标题)'}`);
}

/** 编辑器侧大纲校验(与后端 ReportOutlineValidator 同口径,错误直接展示在保存区)。 */
export function validateOutlineRows(rows: OutlineRow[], nodeKeys: string[]): string[] {
  const errors: string[] = [];
  if (!rows.length) return errors;
  if (rows.length > 50) errors.push('报告大纲条目不能超过 50 个');
  const ids = new Set<string>();
  const titles = new Set<string>();
  const bound = new Set<string>();
  let prevLevel = 0;
  rows.forEach((row, index) => {
    const label = row.title.trim() || `第 ${index + 1} 项`;
    if (!row.title.trim()) errors.push(`大纲「${label}」标题不能为空`);
    if (row.level < 1) {
      errors.push(`大纲「${label}」层级必须为正整数`);
    } else if (row.level > prevLevel + 1) {
      errors.push(`大纲「${label}」不能跳级`);
    }
    if (ids.has(row.id)) errors.push(`大纲「${label}」id 重复`);
    ids.add(row.id);
    const title = row.title.trim();
    if (title && titles.has(title)) errors.push(`大纲标题「${title}」重复`);
    titles.add(title);
    if (row.nodeKeys.length) {
      row.nodeKeys.forEach(key => {
        if (!nodeKeys.includes(key)) errors.push(`大纲「${label}」绑定的节点不存在`);
        else if (bound.has(key)) errors.push(`节点 ${key} 被多个大纲条目绑定`);
        else bound.add(key);
      });
    }
    prevLevel = row.level;
  });
  nodeKeys.forEach(key => {
    if (!bound.has(key)) errors.push(`节点 ${key} 未绑定到报告大纲`);
  });
  return errors;
}
