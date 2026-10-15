import { readFile } from 'node:fs/promises';

const base = process.env.SKILL_API_BASE || 'http://localhost:8081/api';
const userId = process.env.SKILL_USER_ID || 'skill_demo_u001';
const scriptId = 'parameter_input_demo';
const headers = { 'Content-Type': 'application/json', 'X-User-Id': userId };
async function request(path, options = {}) {
  const response = await fetch(`${base}${path}`, { headers, ...options });
  if (!response.ok) throw new Error(`${path}: HTTP ${response.status} ${await response.text()}`);
  return response.json();
}
const source = await readFile(new URL('../../../scripts/parameter_input_demo.py', import.meta.url), 'utf8');
const schema = JSON.parse(await readFile(new URL('../../../scripts/parameter_input_demo.schema.json', import.meta.url), 'utf8'));
let script = (await request('/script-registry')).find(item => item.scriptId === scriptId);
if (!script) {
  script = await request('/script-registry', {
    method: 'POST', body: JSON.stringify({ scriptId, name: '[演示] 脚本参数输入',
      description: '填写报告日期、地区数组、数量，输出模拟表格；无需数据库。',
      datasources: '["gauss"]', paramsSchema: JSON.stringify(schema), timeoutSeconds: 30, enabled: 1 }),
  });
  await request(`/script-registry/${script.id}/source`, {
    method: 'PUT', body: JSON.stringify({ content: source, expectedContentHash: null }),
  });
} else {
  if (script.datasources === '[]') script = await request(`/script-registry?id=${script.id}`, {
    method: 'PUT', body: JSON.stringify({ datasources: '["gauss"]' }),
  });
  // Retry an interrupted registration without overwriting an edited demo.
  const saved = await request(`/script-registry/${script.id}/source`);
  if (!saved.content?.trim()) await request(`/script-registry/${script.id}/source`, {
    method: 'PUT', body: JSON.stringify({ content: source, expectedContentHash: saved.contentHash }),
  });
}
const saved = await request(`/script-registry/${script.id}/source`);
if (!saved.content?.trim() || JSON.parse(script.paramsSchema || '[]').length !== 3) {
  throw new Error('演示脚本源码或参数定义不完整');
}
console.log(JSON.stringify({ id: script.id, scriptId, name: script.name, paramsSchema: JSON.parse(script.paramsSchema) }, null, 2));
