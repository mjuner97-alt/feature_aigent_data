import test from 'node:test';
import assert from 'node:assert/strict';
import * as Vue from 'vue';
import { loadModule } from '../../test/loadModule.js';
import { renderer, container, find, text } from '../../test/memoryRenderer.js';
globalThis.Document ??= class Document {};
globalThis.ShadowRoot ??= class ShadowRoot {};

test('enqueue confirmation does not claim the notification has been sent', async t => {
  const { default: Modal } = await loadModule(new URL('./SkillFlowExecutionNotificationModal.vue', import.meta.url), {
    vue: { ...Vue, Teleport: Vue.Fragment },
    '../api/skillFlow': { resendSkillFlowExecutionNotification: async () => {} },
    '../api/skill': { searchSkillUsers: async () => [{ userId: 'u1', name: '测试人员', department: null }] },
  });
  const root = container();
  const app = renderer.createApp(Modal, { open: true, executionId: 42 });
  app.mount(root); t.after(() => app.unmount());
  find(root, n => n.type === 'input').props['onUpdate:modelValue']('u1');
  await Vue.nextTick();
  await find(root, n => n.type === 'button' && text(n) === '搜索').props.onClick();
  await Vue.nextTick();
  await find(root, n => n.type === 'button' && text(n).includes('测试人员')).props.onClick();
  await Vue.nextTick();
  await find(root, n => n.type === 'button' && text(n) === '确认发送').props.onClick();
  await Vue.nextTick();
  assert.match(text(root), /已提交/);
  assert.doesNotMatch(text(root), /发送成功/);
});
