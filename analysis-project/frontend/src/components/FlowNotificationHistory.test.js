import test from 'node:test';
import assert from 'node:assert/strict';
import * as Vue from 'vue';
import { loadModule } from '../../test/loadModule.js';
import { renderer, container, text, find } from '../../test/memoryRenderer.js';

const { default: History } = await loadModule(new URL('./FlowNotificationHistory.vue', import.meta.url));
function mount(t, notifications) {
  const root = container();
  const app = renderer.createApp({ render: () => Vue.h(History, { notifications }) });
  app.mount(root); t.after(() => app.unmount()); return root;
}
async function click(root, label) {
  const button = find(root, n => n.type === 'button' && text(n) === label);
  assert.ok(button, label); button.props.onClick(); await Vue.nextTick();
}
test('shows latest three independent attempts by sending time and can expand and collapse', async t => {
  const root = mount(t, [1, 4, 2, 3].map(n => ({ id: n, status: 'ACCEPTED', recipient: `user${n}`, createdAt: `2026-10-0${n}T12:00:00` })));
  assert.match(text(root), /通知记录.*4/);
  assert.ok(!text(root).includes('user1'));
  assert.ok(text(root).indexOf('user4') < text(root).indexOf('user3'));
  await click(root, '查看全部'); assert.match(text(root), /user1/);
  await click(root, '收起记录'); assert.ok(!text(root).includes('user1'));
});
test('content is lazy, recipients complete, body collapsed and preview sandboxed', async t => {
  const root = mount(t, [{ status: 'SIMULATED', requestJson: JSON.stringify({ title: '日报', toUserList: ['u1', 'u2'], ccUserList: ['u3'], content: '<p>正文</p>' }) }]);
  assert.match(text(root), /u1等 2 人/);
  assert.ok(!text(root).includes('日报')); assert.equal(find(root, n => n.type === 'iframe'), null);
  await click(root, '查看内容');
  for (const value of ['主送：u1、u2', '抄送：u3', '日报']) assert.ok(text(root).includes(value), value);
  assert.equal(find(root, n => n.type === 'iframe'), null);
  await click(root, '展开正文');
  const frame = find(root, n => n.type === 'iframe');
  assert.equal(frame.props.sandbox, ''); assert.equal(frame.props.referrerpolicy, 'no-referrer');
  assert.match(frame.props.srcdoc, /default-src 'none'/); assert.match(frame.props.srcdoc, /正文/);
});
test('legacy and malformed records use safe fallbacks without exposing diagnostics', async t => {
  const root = mount(t, [{ status: 'FAILED', recipient: 'old-user', requestJson: '{', errorMessage: 'GatewayException secret', responseJson: '{"reportUrl":"internal"}' }]);
  assert.match(text(root), /old-user/); assert.ok(!text(root).includes('GatewayException'));
  await click(root, '查看原因'); assert.match(text(root), /发送未成功，请稍后重试/);
  await click(root, '查看内容'); assert.ok(!text(root).includes('抄送'));
  for (const value of ['网关', 'GatewayException', 'internal', '通知 ID', '执行 ID', '最终请求', '原始响应']) assert.ok(!text(root).includes(value), value);
});
test('status labels never overstate delivery and unknown values do not leak', async t => {
  const root = mount(t, ['ACCEPTED', 'SENT', 'SIMULATED', 'DELIVERED', 'InternalException'].map(status => ({ status })));
  await click(root, '查看全部');
  assert.match(text(root), /已提交发送/); assert.match(text(root), /发送结果未确认/); assert.match(text(root), /模拟完成/);
  assert.ok(!text(root).includes('已送达'));
  assert.ok(!text(root).includes('已发送'));
  assert.ok(!text(root).includes('InternalException'));
});
