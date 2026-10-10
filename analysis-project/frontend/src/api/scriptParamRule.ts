import type { ScriptParamRule } from '../types/scriptRegistry';

const BASE = '/api/script-param-rules';

export async function listScriptParamRules(): Promise<ScriptParamRule[]> {
  const res = await fetch(BASE, { headers: { 'X-User-Id': localStorage.getItem('skill-user-id') || 'demo-user' } });
  if (!res.ok) throw new Error(`查询参数规则失败 (HTTP ${res.status})`);
  const body: unknown = await res.json();
  return Array.isArray(body) ? body as ScriptParamRule[] : [];
}
