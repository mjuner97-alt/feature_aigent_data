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
async function setup(t, overrides = {}, copy = false, userNames = { owner: '创建人姓名' }) {
  const saved = [], alerts = [];
  t.mock.method(globalThis, 'fetch', async () => new Response('[]'));
  const { default: Editor } = await loadModule(new URL('./SkillFlowEditorDrawer.vue', import.meta.url), {
    vue: { ...Vue, Teleport: Vue.Fragment },
    'element-plus': { ElMessage: { info() {} }, ElMessageBox: { alert: async message => alerts.push(message) } },
    '../api/skillFlow': {
      getSkillFlow: async () => ({ id: 7, name: '测试', enabled: true, notifyEnabled: true, createdBy: 'owner', notifyContactName: '', notifyContactPhone: 'abcdefghijk', triggers: [{ keyword: '测试', enabled: true }], nodes: [{ nodeKey: 'n1', nodeName: '节点', nodeType: 'PYTHON', scriptId: 'demo', scriptParams: {}, metricIds: [], required: true, sortOrder: 1 }], ...overrides }),
      getFlowNotifySettings: async () => ({ notifyReceivers: ['b', 'a'], ccReceivers: ['c'] }),
      updateSkillFlow: async (_, payload) => saved.push(JSON.parse(JSON.stringify(payload))),
      createSkillFlow: async payload => saved.push(JSON.parse(JSON.stringify(payload))),
    },
    '../api/skill': {
      listSkills: async () => [], getSkill: async () => ({}), searchSkillUsers: async () => [],
      batchUserNames: async ids => Object.fromEntries((ids || []).filter(id => userNames[id] != null).map(id => [id, userNames[id]])),
    },
  });
  const root = container(), app = renderer.createApp(Editor, { open: true, page: true, editId: copy ? null : 7, copyFromId: copy ? 7 : null, knownFlows: [] });
  for (const name of ['el-select', 'el-option', 'el-icon', 'el-button', 'el-dialog']) app.component(name, { setup: (_, { slots }) => () => Vue.h('span', slots.default?.()) });
  const instance = app.mount(root); t.after(() => app.unmount()); await flush(); await flush();
  const name = find(root, n => n.type === 'input' && n.props.placeholder === '填写负责解答此任务相关问题的人');
  const phone = find(root, n => n.type === 'input' && n.props.placeholder === '填写 11 位手机号');
  const enter = async (contactName, contactPhone) => { assert.ok(name, 'contact name field'); assert.ok(phone, 'contact phone field'); name.props['onUpdate:modelValue'](contactName); phone.props['onUpdate:modelValue'](contactPhone); await Vue.nextTick(); };
  return { root, instance, saved, alerts, name, phone, enter, save: () => button(root, '保存流程').props.onClick() };
}
test('contact info is required, shows no creator placeholder, and auto-backfills creator real name', async t => {
  const { root, name, phone, saved, alerts, save, enter } = await setup(t);
  assert.match(text(root), /通知中的联系信息（必填）/);
  assert.match(text(root), /联系人和联系电话均为必填项；未填写联系人姓名时会自动回填创建人姓名/);
  assert.ok(name, 'creator placeholder must be removed');
  assert.equal(name.value, '创建人姓名'); assert.equal(phone.value, 'abcdefghijk');
  assert.match(text(root), /保存后，任务通知邮件将展示上述联系信息/);
  await save(); assert.equal(saved.length, 1);
  assert.equal(saved[0].notifyContactName, '创建人姓名'); assert.equal(saved[0].notifyContactPhone, 'abcdefghijk');
  await enter('创建人姓名', ' '); assert.match(text(root), /请填写联系电话/);
  await save(); assert.equal(alerts.at(-1), '请填写联系电话'); assert.equal(saved.length, 1);
});
test('unresolvable creator name stays empty and required validation blocks save', async t => {
  const { name, saved, alerts, save } = await setup(t, {}, false, {});
  assert.equal(name.value, '');
  await save(); assert.equal(alerts.at(-1), '请填写联系人姓名'); assert.equal(saved.length, 0);
});
test('incomplete contact blocks save with exact message and no input-time popup', async t => {
  const { root, enter, alerts, saved, save } = await setup(t);
  for (const [name, phone, message] of [['甲', ' ', '请填写联系电话'], [' ', 'abcdefghijk', '请填写联系人姓名']]) {
    const before = alerts.length; await enter(name, phone); assert.equal(alerts.length, before); assert.ok(text(root).includes(message));
    await save(); assert.equal(alerts.at(-1), message); assert.equal(saved.length, 0);
  }
});
test('phone validates trimmed length 10/11/12 only and saves trimmed custom text', async t => {
  const { root, enter, saved, alerts, save, instance } = await setup(t);
  assert.equal(instance.isDirty, false);
  for (const phone of ['abcdefghij', 'abcdefghijkl']) { await enter('甲', phone); await save(); assert.equal(alerts.at(-1), '请输入 11 位手机号'); assert.equal(saved.length, 0); }
  await enter('  甲  ', '  abcdefghijk  ');
  assert.equal(instance.isDirty, true); assert.match(text(root), /保存后，任务通知邮件将展示上述联系信息/);
  await save(); assert.equal(saved.length, 1); assert.equal(saved[0].notifyContactName, '甲'); assert.equal(saved[0].notifyContactPhone, 'abcdefghijk');
});
test('loaded custom contact is editable, name-only changes are dirty, and clearing blocks save', async t => {
  const { enter, saved, save, alerts, instance } = await setup(t, { notifyContactName: '原联系人', notifyContactPhone: 'abcdefghijk' });
  assert.equal(instance.isDirty, false);
  await enter('新联系人', 'abcdefghijk'); assert.equal(instance.isDirty, true);
  await enter(' ', ' ');
  await save(); assert.equal(alerts.at(-1), '请填写联系人姓名'); assert.equal(saved.length, 0);
});
test('copied flow clears source custom contact and backfills current user as new creator', async t => {
  const { name, phone, enter, saved, save } = await setup(t, { notifyContactName: '原联系人', notifyContactPhone: '12345678901' }, true);
  assert.equal(name.value, '创建人姓名'); assert.equal(phone.value, '');
  await enter('创建人姓名', 'abcdefghijk');
  await save(); assert.equal(saved[0].notifyContactName, '创建人姓名'); assert.equal(saved[0].notifyContactPhone, 'abcdefghijk');
});

test('definition API create/update requests and detail response retain top-level contact fields and recipient settings', async t => {
  const contact = { notifyContactName: '负责人', notifyContactPhone: 'abcdefghijk' };
  const notifySettings = { notifyReceivers: ['b', 'a'], ccReceivers: ['c'], notifyReceiversByTrigger: { AUTO_METRIC: ['b', 'a'] }, ccReceiversByTrigger: { AUTO_METRIC: ['c'] } };
  const requests = [];
  t.mock.method(globalThis, 'fetch', async (_, options) => {
    if (options?.body) requests.push(JSON.parse(options.body));
    return new Response(JSON.stringify({ id: 7, ...contact }));
  });
  const api = await loadModule(new URL('../api/skillFlow.ts', import.meta.url));
  const input = { name: '测试', nodes: [], ...contact, notifySettings };
  const results = [await api.createSkillFlow(input), await api.updateSkillFlow(7, input), await api.getSkillFlow(7)];
  assert.equal(requests.length, 2);
  for (const payload of requests) { assert.equal(payload.notifyContactName, contact.notifyContactName); assert.equal(payload.notifyContactPhone, contact.notifyContactPhone); assert.deepEqual(payload.notifySettings, notifySettings); }
  for (const result of results) { assert.equal(result.notifyContactName, contact.notifyContactName); assert.equal(result.notifyContactPhone, contact.notifyContactPhone); }
});

test('mail body loads, previews escaped text and saves changes', async t => {
  const { root, saved, save } = await setup(t, { notifyBodyTemplate: '请查看{链接}<文字>' });
  const input = find(root, n => n.type === 'textarea' && String(n.props.rows) === '5');
  assert.ok(input);
  assert.equal(input.value, '请查看{链接}<文字>');
  const preview = find(root, n => n.type === 'p' && n.props.innerHTML);
  assert.match(preview.props.innerHTML, /preview-link/);
  assert.match(preview.props.innerHTML, /&lt;文字&gt;/);
  input.props['onUpdate:modelValue']('正文');
  await Vue.nextTick();
  assert.match(text(root), /报告链接将追加在正文末尾/);
  await save();
  assert.equal(saved[0].notifyBodyTemplate, '正文');
});

test('mail body longer than 2000 characters blocks saving', async t => {
  const { root, saved, alerts, save } = await setup(t);
  const input = find(root, n => n.type === 'textarea' && String(n.props.rows) === '5');
  input.props['onUpdate:modelValue']('文'.repeat(2001));
  await Vue.nextTick();
  await save();
  assert.equal(saved.length, 0);
  assert.match(alerts.at(-1), /2000/);
});
