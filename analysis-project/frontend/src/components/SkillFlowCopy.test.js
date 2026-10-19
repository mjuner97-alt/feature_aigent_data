import test from 'node:test';
import assert from 'node:assert/strict';
import { h, nextTick } from 'vue';
import { loadModule } from '../../test/loadModule.js';
import { renderer, container, find, text } from '../../test/memoryRenderer.js';

globalThis.localStorage = { getItem: () => 'new-owner' };
globalThis.Document ??= class Document {};
globalThis.ShadowRoot ??= class ShadowRoot {};
const button = (root, label) => find(root, node => node.type === 'button' && text(node) === label);
const input = (root, placeholder) => find(root, node => node.type === 'input' && node.props.placeholder === placeholder);
async function flush() { await new Promise(resolve => setImmediate(resolve)); await nextTick(); }
function mount(component, props = {}) {
  const root = container(), app = renderer.createApp(component, props);
  for (const name of ['el-select', 'el-option', 'el-pagination', 'el-tooltip', 'el-icon', 'el-button']) {
    app.component(name, { setup: (_, { slots }) => () => h('span', slots.default?.()) });
  }
  app.component('el-dialog', { props: ['modelValue'], setup: (props, { slots }) => () => h('div', props.modelValue ? slots.default?.() : []) });
  app.mount(root);
  return { root, app };
}
const period = { $dynamic: { v: 1, kind: 'period_window', unit: 'month', startOffset: -1, endOffset: -1, format: '{year}-{month:02}', order: 'asc', output: 'single' } };
function sourceFlow() {
  return {
    id: 7, code: 'original-code', name: '月报', description: '月度分析', createdBy: 'other-owner', enabled: true, chatPublic: true,
    taskQuestion: '分析', summaryQuestionTemplate: '汇总', notifyEnabled: true, scheduleRules: null,
    triggers: [{ id: 8, keyword: '原关键词', priority: 100, enabled: true }],
    nodes: [{ id: 9, nodeKey: 'n1', nodeName: '数据', nodeType: 'PYTHON', scriptId: 'demo', scriptParams: { period: structuredClone(period) }, metricIds: [13], required: true, sortOrder: 1 }],
    reportOutline: { title: '月度报告', numbering: { level1: 'chinese' }, items: [{ id: 'c1', title: '第一章', nodeKeys: ['n1'], children: [] }] },
    executions: [{ id: 42 }],
  };
}

test('long task list allows copying a readable flow without owner-only actions', async t => {
  const navigations = [];
  t.mock.method(globalThis, 'fetch', async () => new Response(JSON.stringify({items:[{...sourceFlow(),nodeCount:1,metricCount:1,canExecute:false}],page:1,pageSize:20,total:1})));
  const { default: List } = await loadModule(new URL('./SkillFlowList.vue', import.meta.url), {
    'vue-router': { useRouter: () => ({ push: route => navigations.push(route) }) },
  });
  const { root, app } = mount(List, { scope: 'all' }); t.after(() => app.unmount()); await flush();
  assert.equal(button(root, '编辑'), null);
  assert.ok(button(root, '复制'));
  assert.equal(button(root, '查看'), null);
  assert.ok(button(root, '记录'));
  assert.deepEqual(navigations, []);
  await button(root, '复制').props.onClick();
  assert.deepEqual(navigations, [{ path: '/skills/jobs/flows/new', query: { copyFrom: '7' } }]);
});

test('new-flow page passes copy source to the editor without treating it as an edit', async t => {
  const source = sourceFlow();
  let requested = false;
  const urls = [];
  t.mock.method(globalThis, 'fetch', async url => {
    urls.push(String(url));
    if (String(url) === '/api/skill-flows/7') { requested = true; return new Response(JSON.stringify(source)); }
    return new Response('[]');
  });
  const { default: Page } = await loadModule(new URL('../pages/skill/SkillFlowFormPage.vue', import.meta.url), {
    'vue-router': { useRoute: () => ({ params: {}, query: { copyFrom: '7' } }), useRouter: () => ({ push() {} }), onBeforeRouteLeave() {} },
    'element-plus': { ElMessage: {}, ElMessageBox: {} },
  });
  const { root, app } = mount(Page); t.after(() => app.unmount()); await flush(); await flush();
  assert.ok(requested, 'copy source must be loaded through the readable detail API');
  assert.equal(urls.some(url => url.startsWith('/api/skill-flows?')), false, 'editor must not prefetch all definitions');
  assert.match(text(root), /复制长任务流程/);
  assert.equal(input(root, '如 每日质量综合分析').value, '月报-副本');
});

test('copy draft is independent, enabled, and saves a new definition with dynamic params and bindings', async t => {
  const source = { ...sourceFlow(), enabled: false, notifyBodyTemplate: '  保存正文\n\t{链接}  ' }, snapshot = structuredClone(source), saved = [], alerts = [], requests = [];
  const { default: Editor } = await loadModule(new URL('./SkillFlowEditorDrawer.vue', import.meta.url), {
    '../api/skillFlow': {
      getSkillFlow: async id => { assert.equal(id, 7); return source; },
      getFlowNotifySettings: async () => { throw new Error('copy must not read owner-only settings'); },
      createSkillFlow: async payload => saved.push(JSON.parse(JSON.stringify(payload))),
      updateSkillFlow: async () => assert.fail('copy must create instead of updating'),
    },
    'element-plus': { ElMessage: { info() {}, error() {}, warning() {} }, ElMessageBox: { alert: async message => alerts.push(message) } },
  });
  t.mock.method(globalThis, 'fetch', async url => {
    requests.push(String(url));
    if (String(url).startsWith('/api/script-registry')) return new Response(JSON.stringify([{ id: 1, scriptId: 'demo', name: '脚本', enabled: 1, paramsSchema: '[{"name":"period","type":"string"}]' }]));
    return new Response('[]');
  });
  const { root, app } = mount(Editor, { open: true, page: true, editId: null, copyFromId: 7, knownFlows: [source, { ...source, id: 8, name: '已存在副本', triggers: [] }] });
  t.after(() => app.unmount()); await flush(); await flush();
  assert.match(text(root), /复制长任务流程/);
  assert.equal(input(root, '如 每日质量综合分析').value, '月报-副本');
  const enabled = find(root, node => node.type === 'input' && node.props.type === 'checkbox');
  assert.equal(enabled.checked, true);
  assert.doesNotMatch(text(root), /该流程当前为公开状态/);
  input(root, '如 每日质量综合分析').props['onUpdate:modelValue']('月报');
  await button(root, '保存流程').props.onClick();
  assert.match(alerts.at(-1), /复制流程必须修改流程名称/);
  input(root, '如 每日质量综合分析').props['onUpdate:modelValue']('已存在副本');
  await button(root, '保存流程').props.onClick();
  assert.match(alerts.at(-1), /流程名称.*已存在/);
  input(root, '如 每日质量综合分析').props['onUpdate:modelValue']('月报-副本');
  await button(root, '保存流程').props.onClick();
  assert.match(alerts.at(-1), /关键词.*已属于流程/);
  input(root, '输入触发关键词').props['onUpdate:modelValue']('副本关键词');
  input(root, '例如：月度经营分析报告').props['onUpdate:modelValue']('副本标题');
  await button(root, '保存流程').props.onClick();
  assert.equal(saved.length, 1, alerts.join('\n'));
  assert.equal(saved[0].enabled, true);
  assert.equal(saved[0].notifyBodyTemplate, '  保存正文\n\t{链接}  ');
  assert.equal(saved[0].code, '');
  assert.equal(saved[0].id, undefined);
  assert.equal(saved[0].chatPublic, undefined);
  assert.equal(saved[0].createdBy, undefined);
  assert.equal(saved[0].executions, undefined);
  assert.equal(saved[0].nodes[0].id, undefined);
  assert.equal(saved[0].triggers[0].id, undefined);
  assert.deepEqual(saved[0].nodes[0].scriptParams, { period });
  assert.deepEqual(saved[0].nodes[0].metricIds, [13]);
  assert.deepEqual(saved[0].reportOutline.items[0].nodeKeys, ['n1']);
  assert.equal(saved[0].reportOutline.title, '副本标题');
  assert.deepEqual(source, snapshot, 'editing the copy cannot mutate its source');
  assert.ok(!requests.some(url => /script-param-rules|executions/.test(url)));
});
