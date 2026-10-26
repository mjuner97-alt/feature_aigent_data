import test from 'node:test';
import assert from 'node:assert/strict';
import * as Vue from 'vue';
import { loadModule } from '../../test/loadModule.js';
import { renderer, container, find, text } from '../../test/memoryRenderer.js';
globalThis.localStorage = { getItem: () => 'owner' };
globalThis.Document ??= class Document {};
globalThis.ShadowRoot ??= class ShadowRoot {};
const button = (root, label) => find(root, n => n.type === 'button' && text(n) === label);
const flush = async () => { await new Promise(r => setImmediate(r)); await Vue.nextTick(); };
function mount(t, Component, props) {
  const root = container(), app = renderer.createApp(Component, props);
  for (const name of ['el-select', 'el-option', 'el-icon', 'el-button', 'el-dialog', 'el-tooltip', 'el-pagination']) app.component(name, { setup: (_, { slots }) => () => Vue.h('span', slots.default?.()) });
  const instance = app.mount(root); t.after(() => app.unmount()); return { root, instance };
}
const source = { id: 7, name: '测试', createdBy: 'owner', executors: ['runner'], enabled: true, notifyEnabled: true, triggers: [{ keyword: '测试', enabled: true }], nodes: [{ nodeKey: 'n1', nodeName: '节点', nodeType: 'PYTHON', scriptId: 'demo', scriptParams: {}, metricIds: [], required: true, sortOrder: 1 }] };
for (const copy of [false, true]) test(`executor editor ${copy ? 'copy clears grants' : 'loads, searches valid people, deduplicates and saves dirty grants'}`, async t => {
  const saved = [];
  t.mock.method(globalThis, 'fetch', async () => new Response('[]'));
  const { default: Editor } = await loadModule(new URL('./SkillFlowEditorDrawer.vue', import.meta.url), {
    vue: { ...Vue, Teleport: Vue.Fragment },
    'element-plus': { ElMessage: { info() {} }, ElMessageBox: { alert: async message => assert.fail(message) } },
    '../api/skillFlow': { getSkillFlow: async () => source, getFlowNotifySettings: async () => ({ notifyReceivers: [], ccReceivers: [] }), updateSkillFlow: async (_, p) => saved.push(JSON.parse(JSON.stringify(p))), createSkillFlow: async p => saved.push(JSON.parse(JSON.stringify(p))) },
    '../api/skill': { listSkills: async () => [], getSkill: async () => ({}), batchUserNames: async () => ({ runner: '执行人' }), searchSkillUsers: async () => [{ userId: 'owner', name: '创建人' }, { userId: 'runner', name: '执行人' }, { userId: 'other', name: '其他人' }] },
  });
  const { root, instance } = mount(t, Editor, { open: true, page: true, editId: copy ? null : 7, copyFromId: copy ? 7 : null, knownFlows: [] });
  await flush(); await flush();
  assert.equal(instance.isDirty, false); assert.match(text(root), /创建人始终可以执行/);
  if (copy) { await button(root, '保存流程').props.onClick(); assert.deepEqual(saved[0].executors, []); return; }
  assert.match(text(root), /执行人\(runner\)/);
  const input = find(root, n => n.type === 'input' && n.props.placeholder === '搜索可执行人姓名或统一认证号'); assert.ok(input);
  input.props['onUpdate:modelValue']('other'); await Vue.nextTick();
  await button(root, '搜索可执行人').props.onClick(); await flush();
  assert.equal(find(root, n => n.type === 'button' && n.props['aria-label'] === '添加可执行人 owner'), null);
  assert.equal(find(root, n => n.type === 'button' && n.props['aria-label'] === '添加可执行人 runner'), null);
  await find(root, n => n.type === 'button' && n.props['aria-label'] === '添加可执行人 other').props.onClick(); await flush();
  assert.equal(instance.isDirty, true);
  await button(root, '保存流程').props.onClick(); assert.deepEqual(saved[0].executors, ['runner', 'other']);
  await find(root, n => n.type === 'button' && n.props['aria-label'] === '移除可执行人 runner').props.onClick(); await flush();
  await find(root, n => n.type === 'button' && n.props['aria-label'] === '移除可执行人 other').props.onClick(); await flush();
  await button(root, '保存流程').props.onClick(); assert.deepEqual(saved[1].executors, []);
});
for (const [owner, executors, run, edit] of [['owner', [], true, true], ['someone', ['owner'], true, true], ['someone', [], false, false]]) test(`list permissions owner=${owner} grants=${executors.length}`, async t => {
  t.mock.method(globalThis, 'fetch', async () => new Response(JSON.stringify({items:[{ ...source, createdBy: owner, nodeCount:1,metricCount:0,canExecute:run }],page:1,pageSize:20,total:1})));
  const { default: List } = await loadModule(new URL('./SkillFlowList.vue', import.meta.url), { 'vue-router': { useRouter: () => ({ push() {} }) } });
  const { root } = mount(t, List, { scope: 'mine' }); await flush(); await flush();
  assert.equal(!!button(root, '执行'), run); assert.equal(!!button(root, '编辑'), edit); assert.equal(!!button(root, '删除'), edit); assert.equal(!!button(root, '禁用'), edit);
});
for (const triggerType of ['AUTO_METRIC', 'MANUAL', 'CHAT']) test(`${triggerType} single-node retry visibility preserves batch retry`, async t => {
  t.mock.method(globalThis, 'fetch', async url => {
    if (String(url).endsWith('/nodes')) return new Response(JSON.stringify([{ id: 1, nodeKey: 'n1', nodeName: '节点', status: 'FAILED', attemptCount: 1, attempts: [] }]));
    if (String(url).endsWith('/metrics') || String(url).endsWith('/notifications')) return new Response('[]');
    return new Response(JSON.stringify({ id: 7, status: 'FAILED', triggerType, triggerUserId: 'owner' }));
  });
  const { default: Drawer } = await loadModule(new URL('./SkillFlowExecutionDrawer.vue', import.meta.url), { vue: { ...Vue, Teleport: Vue.Fragment }, 'vue-router': { useRouter: () => ({ push() {} }) }, 'element-plus': { ElMessageBox: {} } });
  const open = Vue.ref(false);
  const { root } = mount(t, { setup: () => () => Vue.h(Drawer, { open: open.value, executionId: 7 }) }, {}); open.value = true; await Vue.nextTick(); await flush(); await flush();
  assert.equal(!!button(root, '重跑此任务'), triggerType !== 'AUTO_METRIC');
  assert.match(text(root), /批量重跑失败/);
});


test('definition API retains executor grants through actual create/update JSON and detail response', async t => {
  const requests = [];
  t.mock.method(globalThis, 'fetch', async (_, options) => { if (options?.body) requests.push(JSON.parse(options.body)); return new Response(JSON.stringify(source)); });
  const api = await loadModule(new URL('../api/skillFlow.ts', import.meta.url));
  for (const result of [await api.createSkillFlow(source), await api.updateSkillFlow(7, source), await api.getSkillFlow(7)]) assert.deepEqual(result.executors, ['runner']);
  for (const request of requests) assert.deepEqual(request.executors, ['runner']);
});

