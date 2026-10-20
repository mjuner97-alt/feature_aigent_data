import type { ParamSchemaItem } from '../types/scriptRegistry';

export type ParamValueMode = 'LITERAL' | 'DYNAMIC_PERIOD' | 'BUILDING_VERSIONS';

export function isArrayParam(type: string): boolean {
  return normalizedParamType(type).array;
}

export function isRuleValue(value: unknown): value is { $rule: string } {
  return !!value && typeof value === 'object' && !Array.isArray(value)
    && '$rule' in value;
}

/** Build an editable JSON object from a saved script parameter schema. */
export function paramsFromSchema(schema: ParamSchemaItem[]): Record<string, unknown> {
  return Object.fromEntries(schema.map(item => [item.name, 'default' in item ? (item as ParamSchemaItem & { default?: unknown }).default : defaultParamValue(item.type)]));
}

/** Show saved legacy parameters as fields when no registry schema is available. */
export function inferParamSchema(params: Record<string, unknown>): ParamSchemaItem[] {
  return Object.entries(params).map(([name, value]) => {
    if (isDynamicValue(value)) return { name, type: value.$dynamic?.kind === 'building_versions' || (value.$dynamic && 'output' in value.$dynamic && value.$dynamic.output === 'list') ? 'string[]' : 'string', required: false, description: '' };
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
  if (isParamMarker(raw)) return raw;
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
  const result = { ...paramsFromSchema(schema), ...params };
  for (const item of schema) {
    if (!(item.name in result)) continue;
    let value = result[item.name];
    // 保留动态配置和非法的旧标记，避免转换成字符串后绕过校验。
    if (isParamMarker(value)) continue;
    if (normalizedParamType(item.type).array && !Array.isArray(value)) {
      if (typeof value === 'string') {
        const text = value;
        try {
          const parsed: unknown = JSON.parse(text);
          value = Array.isArray(parsed) ? parsed : text.trim() ? [text] : [];
        } catch { value = text.trim() ? [text] : []; }
      } else value = value == null ? [] : [value];
    }
    result[item.name] = coerceParamValue(value, item.type);
  }
  return result;
}

export function validateParamValue(value: unknown, type: string): string {
  if (isDynamicValue(value)) return value.$dynamic?.kind === 'building_versions' ? validateBuildingVersionsValue(value, type) : validatePeriodValue(value as PeriodValue, type);
  if (isRuleValue(value)) return '旧预设规则已停用，请重新配置动态时间';
  if (value && typeof value === 'object' && Object.values(value).some(containsParamMarker)) return '动态时间配置必须直接绑定参数，不能嵌套在数组或对象内';
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

export interface PeriodWindow { v: 1; kind: 'period_window'; unit: 'month' | 'quarter' | 'year'; startOffset: number; endOffset: number; format: string; order: 'asc' | 'desc'; output: 'list' | 'single' }
export interface PeriodValue { $dynamic: PeriodWindow }
export interface BuildingVersionsValue { $dynamic: { v: 1; kind: 'building_versions' } }
export function buildingVersionsValue(): BuildingVersionsValue { return { $dynamic: { v: 1, kind: 'building_versions' } }; }
export function isBuildingVersionsValue(value: unknown): value is BuildingVersionsValue {
 return isDynamicValue(value) && value.$dynamic?.kind === 'building_versions';
}
export function supportsBuildingVersionsParam(type: string): boolean {
  const normalized = normalizedParamType(type);
  return normalized.array && normalized.base === 'string';
}
export function validateBuildingVersionsValue(value: unknown, type: string): string {
 const marker = value as BuildingVersionsValue;
 const config = marker?.$dynamic;
 if (!config || config.v !== 1 || config.kind !== 'building_versions') return '在建版本配置无效';
 if (Object.keys(marker).length !== 1 || Object.keys(config).length !== 2 || Object.keys(config).some(key => !['v','kind'].includes(key))) return '在建版本配置包含未知或缺失字段';
 return supportsBuildingVersionsParam(type) ? '' : '在建版本仅支持字符串数组参数';
}
export function isParamMarker(value: unknown): boolean { return !!value && typeof value === 'object' && !Array.isArray(value) && ('$rule' in value || '$dynamic' in value); }
function containsParamMarker(value: unknown): boolean {
  return isParamMarker(value) || !!value && typeof value === 'object' && Object.values(value).some(containsParamMarker);
}
export function isDynamicValue(value: unknown): value is { $dynamic: Partial<PeriodWindow> | BuildingVersionsValue['$dynamic'] | null } { return !!value && typeof value === 'object' && !Array.isArray(value) && '$dynamic' in value; }
export function supportsPeriodParam(type: string): boolean {
 return ['string','date','array','string[]','date[]','array<string>','array<date>'].includes((type || '').trim().toLowerCase());
}
export function defaultPeriodValue(type: string): PeriodValue {
 const t = normalizedParamType(type);
 return {$dynamic:{v:1,kind:'period_window',unit:'month',startOffset:t.array ? -3 : -1,endOffset:-1,format:t.base === 'date' ? '{year}-{month:02}-01' : '{year}-{month:02}',order:'asc',output:t.array ? 'list' : 'single'}};
}
export function validatePeriodValue(value: PeriodValue, type: string, maxPeriods = 120): string {
 const c = value.$dynamic;
 if (!c || c.v !== 1 || c.kind !== 'period_window') return '时间配置无效';
 const fields = ['v','kind','unit','startOffset','endOffset','format','order','output'];
 if (Object.keys(value).length !== 1 || Object.keys(c).length !== fields.length || Object.keys(c).some(key => !fields.includes(key))) return '时间配置包含未知或缺失字段';
 if (!supportsPeriodParam(type)) return '此参数不支持时间规则';
 if (!['month','quarter','year'].includes(c.unit) || !['asc','desc'].includes(c.order)) return '周期或排序无效';
 const count = c.endOffset - c.startOffset + 1;
 if (!Number.isInteger(c.startOffset) || !Number.isInteger(c.endOffset) || count < 1 || count > maxPeriods) return `周期数量必须为 1 至 ${maxPeriods} 的整数`;
 if (!['list','single'].includes(c.output) || (c.output === 'single' && count !== 1)) return '单个周期只能生成一个值';
 if ((c.output === 'list') !== isArrayParam(type)) return '输出类型与参数不兼容';
 if (typeof c.format !== 'string' || !c.format || c.format.length > 200 || /[{}]/.test(c.format.replace(/\{(?:year|month(?::02)?|quarter)\}/g,''))) return '格式仅支持 year、month、month:02、quarter 占位符';
 if (normalizedParamType(type).base === 'date' && !(c.unit === 'month' && c.format === '{year}年{month}月份版本') && !/^(?:\{year\}|\d{4})-(?:\{month:02\}|\d{2})-\d{2}$/.test(c.format)) return '日期格式必须为 YYYY-MM-DD 或 xxxx年x月份版本';
 return '';
}
