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
test('editor roundtrips ordered TO/CC, displays counts and marks only confirmed recipients dirty', async t => {
  const saved = [];
  t.mock.method(globalThis, 'fetch', async () => new Response('[]'));
  const { default: Editor } = await loadModule(new URL('./SkillFlowEditorDrawer.vue', import.meta.url), {
    vue: { ...Vue, Teleport: Vue.Fragment },
    'element-plus': { ElMessage: { info() {} }, ElMessageBox: { alert: async message => assert.fail(message) } },
    '../api/skillFlow': {
      getSkillFlow: async () => ({ id: 7, name: '测试', enabled: true, notifyEnabled: true, triggers: [{ keyword: '测试', enabled: true }], nodes: [{ nodeKey: 'n1', nodeName: '节点', nodeType: 'PYTHON', scriptId: 'demo', scriptParams: {}, metricIds: [], required: true, sortOrder: 1 }] }),
      getFlowNotifySettings: async () => ({ notifyReceivers: ['legacy'], ccReceivers: [], notifyReceiversByTrigger: { AUTO_METRIC: ['b', 'a'] }, ccReceiversByTrigger: { AUTO_METRIC: ['c'] } }),
      updateSkillFlow: async (_, payload) => saved.push(JSON.parse(JSON.stringify(payload))), createSkillFlow: async () => assert.fail(),
    },
    '../api/skill': { listSkills: async () => [], getSkill: async () => ({}), batchUserNames: async ids => Object.fromEntries(ids.map(id => [id, `姓名${id}`])), searchSkillUsers: async () => [] },
  });
  const root = container(), app = renderer.createApp(Editor, { open: true, page: true, editId: 7, knownFlows: [] });
  for (const name of ['el-select', 'el-option', 'el-icon', 'el-button', 'el-dialog']) app.component(name, { setup: (_, { slots }) => () => Vue.h('span', slots.default?.()) });
  const instance = app.mount(root); t.after(() => app.unmount()); await flush(); await flush();
  assert.equal(instance.isDirty, false);
  assert.match(text(root), /收件人 2 人.*抄送 1 人/);
  assert.doesNotMatch(text(root), /姓名b/);
  await button(root, '管理收件人').props.onClick(); await flush();
  const query = find(root, n => n.type === 'textarea' && n.props.id === 'mail-recipient-query');
  query.props['onUpdate:modelValue']('d'); await Vue.nextTick(); await button(root, '批量加入抄送').props.onClick(); await flush();
  assert.equal(instance.isDirty, false);
  await button(root, '确认').props.onClick(); await flush();
  assert.equal(instance.isDirty, true);
  await button(root, '保存流程').props.onClick();
  assert.deepEqual(saved[0].notifySettings, { notifyReceivers: ['b', 'a'], ccReceivers: ['c', 'd'], notifyReceiverTriggers: ['AUTO_METRIC'], notifyReceiversByTrigger: { AUTO_METRIC: ['b', 'a'] }, ccReceiversByTrigger: { AUTO_METRIC: ['c', 'd'] } });
});
test('notification settings APIs preserve CC and trigger maps in both directions', async t => {
  const body = { notifyReceivers: ['b', 'a'], ccReceivers: ['d', 'c'], notifyReceiversByTrigger: { AUTO_METRIC: ['b', 'a'] }, ccReceiversByTrigger: { AUTO_METRIC: ['d', 'c'] } };
  let sent;
  t.mock.method(globalThis, 'fetch', async (_, options) => { if (options?.body) sent = JSON.parse(options.body); return new Response(JSON.stringify(body)); });
  const api = await loadModule(new URL('../api/skillFlow.ts', import.meta.url));
  for (const result of [await api.getFlowNotifySettings(7), await api.updateFlowNotifySettings(7, body)]) {
    for (const key of Object.keys(body)) assert.deepEqual(result[key], body[key]);
  }
  assert.deepEqual(sent, body);
});

