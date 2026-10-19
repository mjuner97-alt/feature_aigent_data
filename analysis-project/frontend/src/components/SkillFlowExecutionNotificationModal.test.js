import test from 'node:test';
import assert from 'node:assert/strict';
import * as Vue from 'vue';
import { loadModule } from '../../test/loadModule.js';
import { renderer, container, find, text } from '../../test/memoryRenderer.js';

globalThis.Document ??= class Document {};
globalThis.ShadowRoot ??= class ShadowRoot {};
globalThis.localStorage = { getItem: () => 'owner' };
const flush = async () => { await new Promise(resolve => setImmediate(resolve)); await Vue.nextTick(); };
const button = (root, label) => find(root, n => n.type === 'button' && text(n) === label);
const sendButton = root => find(root, n => n.type === 'button' && text(n).startsWith('确认发送'));
const defaults = () => ({ notifyReceivers: ['legacy'], ccReceivers: [], notifyReceiversByTrigger: { AUTO_METRIC: ['b', 'a', 'b'] }, ccReceiversByTrigger: { AUTO_METRIC: ['c', 'b'] } });
async function setup(t, api = {}, people = {}) {
  const calls = [], events = [], loads = [];
  const props = Vue.reactive({ open: true, executionId: 42 });
  const { default: Modal } = await loadModule(new URL('./SkillFlowExecutionNotificationModal.vue', import.meta.url), {
    vue: { ...Vue, Teleport: Vue.Fragment },
    '../api/skillFlow': {
      getSkillFlowExecutionNotifySettings: async id => { loads.push(id); return defaults(); },
      resendSkillFlowExecutionNotification: async (...args) => calls.push(args), ...api,
    },
    '../api/skill': {
      batchUserNames: async ids => Object.fromEntries(ids.filter(id => id !== 'bad').map(id => [id, `姓名${id}`])),
      searchSkillUsers: async () => [{ userId: 'search', name: '搜索人员' }], ...people,
    },
  });
  const root = container(), app = renderer.createApp({ setup: () => () => Vue.h(Modal, {
    ...props, onSent: () => events.push('sent'), 'onUpdate:open': value => { events.push(value); props.open = value; },
  }) });
  app.mount(root); t.after(() => app.unmount()); await flush(); await flush();
  return { root, calls, events, props, loads };
}

test('loads latest saved ordered TO/CC and sends temporary batch changes without updating defaults', async t => {
  const { root, calls, events, loads } = await setup(t);
  assert.deepEqual(loads, [42]);
  assert.match(text(root), /收件人 TO（2）/); assert.match(text(root), /抄送 CC（1）/);
  assert.ok(text(root).includes('姓名b(b)')); assert.ok(text(root).includes('姓名c(c)'));
  const query = find(root, n => n.type === 'textarea');
  query.props['onUpdate:modelValue']('d，bad; e\nd'); await Vue.nextTick();
  await button(root, '批量加入抄送').props.onClick(); await flush();
  assert.match(text(root), /未匹配：bad/);
  await sendButton(root).props.onClick(); await flush();
  assert.deepEqual(calls, [[42, ['b', 'a'], ['c', 'd', 'e'], '']]);
  assert.deepEqual(events, ['sent', false]);
  assert.match(text(root), /已提交/); assert.doesNotMatch(text(root), /发送成功/);
});

test('clear all blocks send and restore resets both groups; reopen reloads current saved defaults', async t => {
  let settings = defaults();
  const { root, calls, props } = await setup(t, { getSkillFlowExecutionNotifySettings: async () => settings });
  await button(root, '全部清空').props.onClick(); await flush();
  assert.equal(sendButton(root).props.disabled, true);
  await sendButton(root).props.onClick(); assert.deepEqual(calls, []);
  await button(root, '恢复已保存名单').props.onClick(); await flush();
  assert.equal(sendButton(root).props.disabled, false);
  await button(root, '取消').props.onClick(); await flush();
  settings = { notifyReceivers: ['new'], ccReceivers: ['new-cc'] };
  props.open = true; await flush(); await flush();
  assert.ok(text(root).includes('姓名new(new)')); assert.doesNotMatch(text(root), /姓名b\(b\)/);
  await sendButton(root).props.onClick(); await flush();
  assert.deepEqual(calls, [[42, ['new'], ['new-cc'], '']]);
});

test('defaults failure offers retry and cannot silently send empty or stale lists', async t => {
  let fail = true;
  const { root } = await setup(t, { getSkillFlowExecutionNotifySettings: async () => { if (fail) throw new Error('读取默认名单失败'); return defaults(); } });
  assert.match(text(root), /读取默认名单失败/); assert.equal(sendButton(root), null);
  fail = false; await button(root, '重试').props.onClick(); await flush(); await flush();
  assert.match(text(root), /收件人 TO（2）/);
});

test('failed send retains temporary recipients and permits retry; duplicate submits are blocked', async t => {
  let reject, count = 0;
  const { root, events } = await setup(t, { resendSkillFlowExecutionNotification: () => { count++; return new Promise((_, r) => { reject = r; }); } });
  await button(root, '清空抄送').props.onClick(); await flush();
  const pending = sendButton(root).props.onClick(); await Vue.nextTick();
  const submit = find(root, n => n.type === 'button' && text(n) === '提交中…');
  assert.equal(submit.props.disabled, true);
  submit.props.onClick(); assert.equal(count, 1);
  reject(new Error('提交失败')); await pending; await flush();
  assert.match(text(root), /提交失败/); assert.match(text(root), /抄送 CC（0）/);
  assert.deepEqual(events, []); assert.equal(sendButton(root).props.disabled, false);
});

test('late defaults for previous execution cannot overwrite newer recipients or template', async t => {
  let resolve;
  const { root, props } = await setup(t, { getSkillFlowExecutionNotifySettings: id => id === 42 ? new Promise(r => { resolve = r; }) : Promise.resolve({ notifyReceivers: ['fresh'], ccReceivers: [], notifyBodyTemplate: '新执行模板' }) });
  assert.match(text(root), /正在加载已保存名单/);
  props.executionId = 43; await flush(); await flush();
  assert.ok(text(root).includes('姓名fresh(fresh)'));
  resolve({ ...defaults(), notifyBodyTemplate: '过期模板' }); await flush(); await flush();
  assert.ok(text(root).includes('姓名fresh(fresh)')); assert.doesNotMatch(text(root), /姓名b\(b\)/);
  assert.equal(bodyInput(root).value, '新执行模板');
});

test('missing names never discard saved recipients', async t => {
  const { root, calls } = await setup(t, {}, { batchUserNames: async () => { throw new Error('姓名查询失败'); } });
  assert.match(text(root), /b\(b\)/);
  await sendButton(root).props.onClick(); await flush();
  assert.deepEqual(calls, [[42, ['b', 'a'], ['c'], '']]);
});

test('resend API serializes explicit ordered TO and CC including an empty CC', async t => {
  const requests = [];
  t.mock.method(globalThis, 'fetch', async (_, options) => { requests.push(JSON.parse(options.body)); return new Response(null, { status: 204 }); });
  const api = await loadModule(new URL('../api/skillFlow.ts', import.meta.url));
  await api.resendSkillFlowExecutionNotification(42, ['b', 'a'], ['c']);
  await api.resendSkillFlowExecutionNotification(42, ['b'], []);
  assert.deepEqual(requests, [{ confirmed: true, notifyReceivers: ['b', 'a'], ccReceivers: ['c'] }, { confirmed: true, notifyReceivers: ['b'], ccReceivers: [] }]);
});

test('execution detail opens the same notification picker instead of a separate recipient prompt', async t => {
  const modalProps = [];
  const { default: Drawer } = await loadModule(new URL('./SkillFlowExecutionDrawer.vue', import.meta.url), {
    vue: { ...Vue, Teleport: Vue.Fragment },
    'vue-router': { useRouter: () => ({ push() {} }) },
    'element-plus': { ElMessageBox: { prompt: () => assert.fail('must use shared notification picker') } },
    './SkillFlowExecutionNotificationModal.vue': { props: ['open', 'executionId'], setup: props => () => { modalProps.push({ ...props }); return null; } },
  });
  t.mock.method(globalThis, 'fetch', async url => new Response(/\/(nodes|metrics|notifications)$/.test(String(url)) ? '[]' : JSON.stringify({ id: 42, status: 'SUCCESS', reportPath: 'report.html', triggerUserId: 'owner' })));
  const props = Vue.reactive({ open: false, executionId: 42 });
  const root = container(), app = renderer.createApp({ setup: () => () => Vue.h(Drawer, props) });
  app.mount(root); t.after(() => app.unmount()); props.open = true; await flush(); await flush();
  await button(root, '发送通知').props.onClick(); await flush();
  assert.deepEqual(modalProps.at(-1), { open: true, executionId: 42 });
});

const bodyInput = root => find(root, n => n.type === 'textarea' && n.props['aria-label'] === '通知邮件正文模板');
test('manual notification loads the latest saved template, sends an exact temporary edit and reloads on reopen', async t => {
  let settings = { ...defaults(), flowName: '改名后的月报', reportLinkTitle: '原月报 (2026年09月01日)', notifyBodyTemplate: '  已保存\n\t{链接}  ' };
  const original = structuredClone(settings);
  const { root, calls, props } = await setup(t, { getSkillFlowExecutionNotifySettings: async () => settings });
  assert.equal(bodyInput(root)?.value, original.notifyBodyTemplate);
  assert.match(find(root, n => !!n.props?.innerHTML).props.innerHTML, /原月报 \(2026年09月01日\)/);
  bodyInput(root).props['onUpdate:modelValue']('  临时   内容\n\t{链接}  '); await flush();
  await sendButton(root).props.onClick(); await flush();
  assert.equal(calls[0][3], '  临时   内容\n\t{链接}  ');
  assert.deepEqual(settings, original);
  settings = { ...defaults(), notifyBodyTemplate: '最新保存' };
  props.open = true; await flush(); await flush();
  assert.equal(bodyInput(root).value, '最新保存');
});
test('editing only the template prevents accidental dismissal and failed sends retain it', async t => {
  const { root, events } = await setup(t, { resendSkillFlowExecutionNotification: async () => { throw new Error('发送失败'); } });
  bodyInput(root).props['onUpdate:modelValue']('临时正文'); await flush();
  const close = find(root, n => n.type === 'button' && n.props['aria-label'] === '关闭');
  close.props.onClick(); await flush();
  assert.deepEqual(events, []);
  await sendButton(root).props.onClick(); await flush();
  assert.equal(bodyInput(root).value, '临时正文');
  assert.match(text(root), /发送失败/);
});
test('resend API preserves template whitespace and sends an explicit empty template', async t => {
  const requests = [];
  t.mock.method(globalThis, 'fetch', async (_, options) => { requests.push(JSON.parse(options.body)); return new Response(null, { status: 204 }); });
  const api = await loadModule(new URL('../api/skillFlow.ts', import.meta.url));
  await api.resendSkillFlowExecutionNotification(42, ['b'], [], '  正文\n\t{链接}  ');
  await api.resendSkillFlowExecutionNotification(42, ['b'], [], '');
  assert.equal(requests[0].notifyBodyTemplate, '  正文\n\t{链接}  ');
  assert.equal(requests[1].notifyBodyTemplate, '');
});
