import test from 'node:test';
import assert from 'node:assert/strict';
import * as Vue from 'vue';
const { nextTick } = Vue;
globalThis.Document ??= class Document {};
globalThis.ShadowRoot ??= class ShadowRoot {};
import { loadModule } from '../../test/loadModule.js';
import { renderer, container, find, text } from '../../test/memoryRenderer.js';
const button = (root, label) => find(root, n => n.type === 'button' && text(n) === label);
const flush = async () => { await new Promise(r => setImmediate(r)); await nextTick(); };
async function setup(t) {
  const events = [], lookups = [];
  const { default: Dialog } = await loadModule(new URL('./MailRecipientDialog.vue', import.meta.url), {
    vue: { ...Vue, Teleport: Vue.Fragment },
    '../api/skill': {
      batchUserNames: async ids => { lookups.push(ids); return Object.fromEntries(ids.filter(id => id !== 'bad').reverse().map(id => [id, `姓名${id}`])); },
      searchSkillUsers: async () => [{ userId: 'c', name: '丙' }],
    },
  });
  const root = container();
  const app = renderer.createApp(Dialog, { open: true, to: ['a'], cc: ['b'], names: { a: '甲', b: '乙' }, onConfirm: v => events.push(v), 'onUpdate:open': v => events.push(v) });
  app.mount(root); t.after(() => app.unmount()); await flush();
  const input = find(root, n => n.type === 'textarea');
  const enter = async value => { input.props['onUpdate:modelValue'](value); await nextTick(); };
  return { root, events, lookups, enter };
}

test('send mode shares picker, supports clear and restore, and confirms without closing', async t => {
  const events = [];
  const { default: Dialog } = await loadModule(new URL('./MailRecipientDialog.vue', import.meta.url), {
    vue: { ...Vue, Teleport: Vue.Fragment },
    '../api/skill': { batchUserNames: async () => ({}), searchSkillUsers: async () => [] },
  });
  const root = container(), app = renderer.createApp(Dialog, {
    open: true, mode: 'send', to: ['a'], cc: ['b'], names: { a: '甲', b: '乙' },
    onConfirm: value => events.push(value), 'onUpdate:open': value => events.push(value),
  });
  app.mount(root); t.after(() => app.unmount()); await flush();
  assert.match(text(root), /本次调整仅用于此次通知，不修改长任务配置/);
  await button(root, '清空收件人').props.onClick(); await flush();
  assert.ok(find(root, n => n.type === 'button' && text(n).startsWith('确认发送')).props.disabled);
  assert.doesNotMatch(text(root), /未配置时通知流程触发人/);
  await button(root, '全部清空').props.onClick(); await flush();
  assert.match(text(root), /抄送 CC（0）/);
  await button(root, '恢复已保存名单').props.onClick(); await flush();
  await button(root, '清空抄送').props.onClick(); await flush();
  await button(root, '确认发送（收件人 1 人，抄送 0 人）').props.onClick(); await flush();
  assert.deepEqual(events, [{ to: ['a'], cc: [], names: { a: '甲', b: '乙' } }]);
});
test('ordered batch matching preserves successful input order, reports partial failures and moves across groups', async t => {
  const { root, events, lookups, enter } = await setup(t);
  await enter('c，bad; a\nc c');
  await button(root, '批量加入抄送').props.onClick(); await flush();
  assert.deepEqual(lookups, [['c', 'bad', 'a']]);
  assert.match(text(root), /未匹配：bad/);
  await button(root, '确认').props.onClick();
  assert.deepEqual(events[0], { to: [], cc: ['b', 'c', 'a'], names: { a: '姓名a', b: '乙', c: '姓名c' } });
});
test('cancel discards draft and confirm returns independent recipient arrays', async t => {
  const { root, events, enter } = await setup(t);
  await enter('c'); await button(root, '批量加入收件人').props.onClick(); await flush();
  await button(root, '取消').props.onClick();
  assert.deepEqual(events, [false]);
});
test('single search result can be added to either group with name(userId) preview', async t => {
  const { root, events, enter } = await setup(t);
  await enter('丙'); await button(root, '搜索').props.onClick(); await flush();
  await button(root, '加入收件人').props.onClick(); await flush();
  assert.match(text(root), /丙\(c\)/);
  await button(root, '确认').props.onClick();
  assert.deepEqual(events[0].to, ['a', 'c']);
});

test('accidental dismissals keep added recipients until confirmed or explicitly cancelled', async t => {
  const { root, events, enter } = await setup(t);
  await enter('c'); await button(root, '批量加入抄送').props.onClick(); await flush();
  const mask = find(root, n => n.props.class === 'mail-mask');
  mask.props.onClick({ target: mask, currentTarget: mask }); await flush();
  const dialog = find(root, n => n.props.role === 'dialog');
  dialog.props.onKeydown({ key: 'Escape', preventDefault() {} }); await flush();
  find(root, n => n.type === 'button' && n.props['aria-label'] === '关闭').props.onClick(); await flush();
  assert.deepEqual(events, []);
  assert.match(text(root), /名单尚未确认/);
  await button(root, '确认').props.onClick();
  assert.deepEqual(events[0].cc, ['b', 'c']);
});




test('closing and reopening discards draft and ignores a late lookup response', async t => {
  let resolve;
  const props = Vue.reactive({ open: true, to: ['a'], cc: [], names: { a: '甲' } });
  const events = [];
  const { default: Dialog } = await loadModule(new URL('./MailRecipientDialog.vue', import.meta.url), {
    vue: { ...Vue, Teleport: Vue.Fragment },
    '../api/skill': { batchUserNames: () => new Promise(r => { resolve = r; }), searchSkillUsers: async () => [] },
  });
  const root = container(), app = renderer.createApp({ setup: () => () => Vue.h(Dialog, { ...props, 'onUpdate:open': value => { props.open = value; }, onConfirm: value => events.push(value) }) });
  app.mount(root); t.after(() => app.unmount()); await flush();
  find(root, n => n.type === 'textarea').props['onUpdate:modelValue']('late'); await nextTick();
  const pending = button(root, '批量加入抄送').props.onClick(); await nextTick();
  let restored = false;
  const previousDocument = globalThis.document; globalThis.document = { activeElement: { focus: () => { restored = true; } } };
  t.after(() => { globalThis.document = previousDocument; });
  await button(root, '取消').props.onClick(); await flush(); props.open = true; await flush();
  const dialog = find(root, n => n.type === 'section' && n.props.role === 'dialog');
  let focused = '', prevented = false;
  const first = { focus: () => { focused = 'first'; } }, last = { focus: () => { focused = 'last'; } };
  dialog.querySelectorAll = () => [first, last];
  globalThis.document.activeElement = last;
  dialog.props.onKeydown({ key: 'Tab', shiftKey: false, preventDefault: () => { prevented = true; } });
  assert.equal(focused, 'first'); assert.equal(prevented, true);
  globalThis.document.activeElement = first;
  dialog.props.onKeydown({ key: 'Tab', shiftKey: true, preventDefault() {} });
  assert.equal(focused, 'last');
  resolve({ late: '迟到' }); await pending; await flush();
  assert.doesNotMatch(text(root), /迟到/);
  await button(root, '确认').props.onClick();
  assert.deepEqual(events[0], { to: ['a'], cc: [], names: { a: '甲' } });
  await flush(); assert.equal(restored, true);
});


