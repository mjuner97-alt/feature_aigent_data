import test from 'node:test';
import assert from 'node:assert/strict';
import { h, nextTick } from 'vue';
import { loadModule } from '../../test/loadModule.js';
import { renderer, container, find, text } from '../../test/memoryRenderer.js';

const { default: List } = await loadModule(new URL('./ReportProcessExecutionList.vue', import.meta.url), {
  'vue-router': { useRouter: () => ({ push() {} }) },
  'element-plus': { ElMessageBox: {} },
});
globalThis.localStorage = { getItem: () => 'u1' };
async function flush() { await new Promise(resolve => setImmediate(resolve)); await nextTick(); }

test('record name opens the matching detail after list actions are removed', async t => {
  const urls = [];
  t.mock.method(globalThis, 'fetch', async url => {
    urls.push(String(url));
    if (String(url).includes('?')) return new Response(JSON.stringify({ items: [
      { id: 42, flowName: '经营报告', status: 'SUCCESS', triggerUserName: '张三', triggerUserId: 'u1' },
    ], total: 1 }));
    if (String(url).endsWith('/42')) return new Response(JSON.stringify({ id: 42, flowName: '经营报告', status: 'SUCCESS', triggerUserId: 'u1' }));
    return new Response('[]');
  });
  const root = container();
  const app = renderer.createApp(List, { source: 'report-template' });
  for (const name of ['el-icon', 'el-tooltip', 'el-pagination']) {
    app.component(name, { setup: (_, { slots }) => () => h('span', slots.default?.()) });
  }
  app.mount(root); t.after(() => app.unmount()); await flush();
  assert.match(text(root), /张三\(u1\)/);
  const title = find(root, node => node.type === 'button' && text(node) === '经营报告');
  assert.ok(title, 'name must be keyboard accessible');
  title.props.onClick(); await flush(); await flush();
  assert.ok(urls.includes('/api/skill-flow-executions/42'));
});

test('report link opens the report through the existing authenticated endpoint', async t => {
  const reportWindow = { location: { href: '' }, opener: {}, close() {} };
  const previousWindow = globalThis.window;
  globalThis.window = { open: () => reportWindow };
  t.after(() => { if (previousWindow === undefined) delete globalThis.window; else globalThis.window = previousWindow; });
  const reportRequests = [];
  t.mock.method(globalThis, 'fetch', async (url, options) => {
    if (String(url).endsWith('/report')) {
      reportRequests.push({ url, options });
      return new Response('<html><head></head><body>报告</body></html>', { headers: { 'Content-Type': 'text/html' } });
    }
    return new Response(JSON.stringify({ items: [
      { id: 43, flowName: '月度报告', status: 'SUCCESS', triggerUserId: 'u2', reportPath: '/internal/report.html' },
    ], total: 1 }));
  });
  const root = container();
  const app = renderer.createApp(List, { source: 'report-template' });
  for (const name of ['el-icon', 'el-tooltip', 'el-pagination']) {
    app.component(name, { setup: (_, { slots }) => () => h('span', slots.default?.()) });
  }
  app.mount(root); t.after(() => app.unmount()); await flush();
  assert.match(text(root), /u2/);
  const report = find(root, node => node.type === 'button' && text(node) === '查看报告');
  assert.ok(report);
  await report.props.onClick();
  assert.equal(reportRequests[0].url, '/api/skill-flow-executions/43/report');
  assert.ok(reportRequests[0].options.headers);
  assert.match(reportWindow.location.href, /^blob:/);
  assert.equal(reportWindow.opener, null);
  URL.revokeObjectURL(reportWindow.location.href);
});

test('resolves names when execution API returns only user IDs', async t => {
  const lookups = [];
  t.mock.method(globalThis, 'fetch', async url => {
    if (String(url).startsWith('/api/skills/users?')) {
      lookups.push(String(url));
      return new Response(JSON.stringify([{ userId: 'skill_demo_u001', name: '李四' }]));
    }
    return new Response(JSON.stringify({ items: [
      { id: 9, flowName: '周报演示模板', status: 'SUCCESS', triggerUserId: 'skill_demo_u001' },
      { id: 8, flowName: '周报演示模板', status: 'SUCCESS', triggerUserId: 'skill_demo_u001' },
    ], total: 2 }));
  });
  const root = container();
  const app = renderer.createApp(List, { source: 'report-template' });
  for (const name of ['el-icon', 'el-tooltip', 'el-pagination']) {
    app.component(name, { setup: (_, { slots }) => () => h('span', slots.default?.()) });
  }
  app.mount(root); t.after(() => app.unmount());
  await flush(); await flush();
  assert.match(text(root), /李四\(skill_demo_u001\)/);
  assert.equal(lookups.length, 1, 'duplicate trigger IDs must share one name lookup');
});
