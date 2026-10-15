import test from 'node:test';
import assert from 'node:assert/strict';
import { h, nextTick } from 'vue';
import { loadModule } from '../../test/loadModule.js';
import { renderer, container, find, text } from '../../test/memoryRenderer.js';
const { default: List } = await loadModule(new URL('./SkillFlowExecutionList.vue', import.meta.url), {
  'vue-router': { useRouter: () => ({ push() {} }) },
  'element-plus': { ElMessageBox: {} },
});
globalThis.localStorage = { getItem: () => 'u1' };
test('long task records retain original columns and detail, notify, cancel actions', async t => {
  const requests = [];
  t.mock.method(globalThis, 'fetch', async url => {
    requests.push(String(url));
    if (String(url).includes('?')) return new Response(JSON.stringify({ items: [
      { id: 42, flowName: '长任务', status: 'SUCCESS', triggerUserId: 'u1' },
      { id: 43, flowName: '运行中任务', status: 'RUNNING', triggerUserId: 'u1' },
    ], total: 2 }));
    if (String(url).endsWith('/42')) return new Response(JSON.stringify({ id: 42, flowName: '长任务', status: 'SUCCESS', triggerUserId: 'u1' }));
    return new Response('[]');
  });
  const root = container();
  const app = renderer.createApp(List);
  for (const name of ['el-icon', 'el-tooltip', 'el-pagination']) app.component(name, { setup: (_, { slots }) => () => h('span', slots.default?.()) });
  app.mount(root); t.after(() => app.unmount());
  const flush = async () => { await new Promise(resolve => setImmediate(resolve)); await nextTick(); };
  await flush();
  for (const label of ['任务名称', '触发方式', '总消耗时间', '总历时', '操作', '#42', '通知', '终止']) assert.ok(text(root).includes(label), label);
  const detail = find(root, n => n.type === 'button' && text(n) === '查看详情');
  assert.ok(detail);
  detail.props.onClick(); await flush(); await flush();
  assert.ok(requests.includes('/api/skill-flow-executions/42'));
  assert.ok(requests.some(url => url.includes('source=long-task')));
});
