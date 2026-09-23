import type { ParamSchemaItem } from '../types/scriptRegistry';

/** Build an editable JSON object from a saved script parameter schema. */
export function paramsFromSchema(schema: ParamSchemaItem[]): Record<string, unknown> {
  return Object.fromEntries(schema.map(item => [item.name, 'default' in item ? (item as ParamSchemaItem & { default?: unknown }).default : defaultValueForType(item.type)]));
}

function defaultValueForType(type: string): unknown {
  const normalized = type.trim().toLowerCase();
  if (normalized.endsWith('[]')) return [];
  if (normalized === 'boolean' || normalized === 'bool') return false;
  if (normalized === 'int' || normalized === 'integer' || normalized === 'number' || normalized === 'float' || normalized === 'double') return null;
  return '';
}
