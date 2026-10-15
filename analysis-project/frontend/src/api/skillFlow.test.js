import test from 'node:test';
import assert from 'node:assert/strict';
import { loadModule } from '../../test/loadModule.js';

const api = await loadModule(new URL('./skillFlow.ts', import.meta.url));
globalThis.localStorage = { getItem: () => 'test-user' };

for (const operation of ['create', 'update']) {
  for (const detail of [
    'ScriptParamsInvalid: 节点 n1 的参数 year 必填',
    'invalid parameter: year',
    'FlowValidationFailed: 节点 n1 缺少脚本参数 year',
    'ReportOutlineInvalid: chapter_1 references missing node n2',
  ]) {
    test(`${operation} retains backend detail: ${detail}`, async t => {
      t.mock.method(globalThis, 'fetch', async () => new Response(JSON.stringify({ message: detail }), {
        status: 400, headers: { 'Content-Type': 'application/json' },
      }));
      await assert.rejects(
        () => operation === 'create' ? api.createSkillFlow({ nodes: [] }) : api.updateSkillFlow(1, { nodes: [] }),
        error => error.message.includes(detail),
      );
    });
  }
}

test('empty failure response includes HTTP status', async t => {
  t.mock.method(globalThis, 'fetch', async () => new Response('', { status: 502 }));
  await assert.rejects(() => api.createSkillFlow({ nodes: [] }), /HTTP 502/);
});

test('known name conflict keeps its readable translation', async t => {
  t.mock.method(globalThis, 'fetch', async () => new Response(JSON.stringify({ message: 'FlowNameConflict' }), {
    status: 409, headers: { 'Content-Type': 'application/json' },
  }));
  await assert.rejects(() => api.createSkillFlow({ nodes: [] }), /流程名称已存在/);
});
