import type { ParamSchemaItem } from '../types/scriptRegistry';

/** Build an editable JSON object from a saved script parameter schema. */
export function paramsFromSchema(schema: ParamSchemaItem[]): Record<string, unknown> {
  return Object.fromEntries(schema.map(item => [item.name, 'default' in item ? (item as ParamSchemaItem & { default?: unknown }).default : defaultValueForType(item.type)]));
}

/** Show saved legacy parameters as fields when no registry schema is available. */
export function inferParamSchema(params: Record<string, unknown>): ParamSchemaItem[] {
  return Object.entries(params).map(([name, value]) => {
    const sample = Array.isArray(value) ? value.find(item => item !== null && item !== '') : value;
    const type = typeof sample === 'number' ? (Number.isInteger(sample) ? 'int' : 'float')
      : typeof sample === 'boolean' ? 'boolean'
      : typeof sample === 'string' && /^\d{4}[-/]\d{2}[-/]\d{2}$/.test(sample) ? 'date'
      : 'string';
    return { name, type: Array.isArray(value) ? `${type}[]` : type, required: false, description: '' };
  });
}

export function normalizedParamType(type: string): { base: string; array: boolean } {
  const value = (type || 'string').trim().toLowerCase();
  const array = value.endsWith('[]') || value.startsWith('array<') || value === 'array';
  const inner = value.endsWith('[]') ? value.slice(0, -2) : value.replace(/^array<|>$/g, '');
  if (array && (!inner || inner === 'array')) return { base: 'string', array: true };
  if (['int', 'integer', 'long'].includes(inner)) return { base: 'int', array };
  if (['float', 'double', 'number', 'decimal'].includes(inner)) return { base: 'float', array };
  if (['boolean', 'bool'].includes(inner)) return { base: 'boolean', array };
  if (['date', 'datetime'].includes(inner)) return { base: inner, array };
  return { base: 'string', array };
}

export function defaultParamValue(type: string): unknown {
  const t = normalizedParamType(type);
  if (t.array) return [];
  if (t.base === 'boolean') return false;
  if (t.base === 'int' || t.base === 'float') return null;
  return '';
}

export function coerceParamValue(raw: unknown, type: string): unknown {
  const t = normalizedParamType(type);
  if (t.array) return Array.isArray(raw) ? raw.map(v => coerceParamValue(v, t.base)) : [];
  if (raw === '' || raw === null || raw === undefined) return t.base === 'string' ? '' : null;
  if (t.base === 'int') return Number.isInteger(Number(raw)) ? Number(raw) : raw;
  if (t.base === 'float') return Number.isFinite(Number(raw)) ? Number(raw) : raw;
  if (t.base === 'boolean') return raw === true || raw === 'true';
  return String(raw);
}

/** Normalize legacy values before both debugging and saving a flow. */
export function normalizeScriptParams(params: Record<string, unknown>, schema: ParamSchemaItem[]): Record<string, unknown> {
  const result = { ...params };
  for (const item of schema) {
    if (!(item.name in result)) continue;
    let value = result[item.name];
    if (normalizedParamType(item.type).array && !Array.isArray(value)) {
      if (typeof value === 'string') {
        try {
          const parsed: unknown = JSON.parse(value);
          value = Array.isArray(parsed) ? parsed : value.trim() ? [value] : [];
        } catch { value = value.trim() ? [value] : []; }
      } else value = value == null ? [] : [value];
    }
    result[item.name] = coerceParamValue(value, item.type);
  }
  return result;
}

export function validateParamValue(value: unknown, type: string): string {
  const t = normalizedParamType(type);
  if (t.array) {
    if (!Array.isArray(value)) return '必须是数组';
    for (let i = 0; i < value.length; i++) {
      const error = validateParamValue(value[i], t.base);
      if (error) return `第 ${i + 1} 项${error}`;
    }
    return '';
  }
  if (value === '' || value === null || value === undefined) return '';
  if (t.base === 'int' && (!Number.isInteger(Number(value)))) return '必须是整数';
  if (t.base === 'float' && !Number.isFinite(Number(value))) return '必须是数字';
  if (t.base === 'boolean' && typeof value !== 'boolean') return '必须是布尔值';
  if (t.base === 'date' && !/^\d{4}-\d{2}-\d{2}$/.test(String(value))) return '格式必须为 YYYY-MM-DD';
  if (t.base === 'datetime' && !/^\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}(:\d{2})?$/.test(String(value))) return '格式必须为 YYYY-MM-DD HH:mm';
  return '';
}

function defaultValueForType(type: string): unknown {
  const normalized = type.trim().toLowerCase();
  if (normalized.endsWith('[]')) return [];
  if (normalized === 'boolean' || normalized === 'bool') return false;
  if (normalized === 'int' || normalized === 'integer' || normalized === 'number' || normalized === 'float' || normalized === 'double') return null;
  return '';
}
