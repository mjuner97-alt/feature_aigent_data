import test from 'node:test';
import assert from 'node:assert/strict';
import * as Vue from 'vue';
import { loadModule } from '../../test/loadModule.js';
import { renderer, container, text } from '../../test/memoryRenderer.js';

test('notification history distinguishes simulation and gateway acceptance and shows final recipients', async t => {
  const { default: History } = await loadModule(new URL('./FlowNotificationHistory.vue', import.meta.url));
  const root = container();
  const app = renderer.createApp({ render: () => Vue.h(History, { notifications: [{
    id: 1, status: 'SIMULATED', channel: 'SIMULATE', recipient: 'u1',
    requestJson: JSON.stringify({ title: '日报', toUserList: ['u1'], ccUserList: ['u2'], content: '<p>邮件正文</p>' }),
    responseJson: JSON.stringify({ durationMs: 12, response: null }),
  }, { id: 2, status: 'ACCEPTED', channel: 'REAL', recipient: 'u3', requestJson: '{}', responseJson: '{}' }] }) });
  app.mount(root); t.after(() => app.unmount());
  const output = text(root);
  for (const expected of ['模拟完成', '网关已接受', '主送', '抄送', 'u1', 'u2', '日报', '12']) assert.ok(output.includes(expected), expected);
  assert.ok(!output.includes('已送达'));
  const nodes = [];
  function walk(node) { nodes.push(node); for (const child of node.children || []) walk(child); }
  walk(root);
  const preview = nodes.find(node => node.type === 'iframe');
  assert.ok(preview);
  assert.equal(preview.props.sandbox, '');
  assert.ok(preview.props.srcdoc.includes('邮件正文'));
});

test('legacy summary JSON and malformed rows remain readable', async t => {
  const { default: History } = await loadModule(new URL('./FlowNotificationHistory.vue', import.meta.url));
  const root = container();
  const app = renderer.createApp({ render: () => Vue.h(History, { notifications: [
    { id: 1, status: 'SENT', recipient: 'old-user', requestJson: '{"results":[]}' },
    { id: 2, status: 'FAILED', requestJson: '{', errorMessage: '连接失败' },
  ] }) });
  app.mount(root); t.after(() => app.unmount());
  assert.match(text(root), /old-user/);
  assert.match(text(root), /历史记录/);
  assert.match(text(root), /连接失败/);
});
