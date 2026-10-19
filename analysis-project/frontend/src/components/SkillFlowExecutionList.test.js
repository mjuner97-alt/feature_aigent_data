import test from 'node:test';
import assert from 'node:assert/strict';
import * as Vue from 'vue';
import { loadModule } from '../../test/loadModule.js';
import { renderer, container, find, text } from '../../test/memoryRenderer.js';
globalThis.localStorage = { getItem: () => 'u1' };
const { default: List } = await loadModule(new URL('./SkillFlowExecutionList.vue', import.meta.url), {
  'element-plus': { ElMessageBox: {} }, 'vue-router': { useRouter: () => ({ push() {} }) },
});
const { default: ReportList } = await loadModule(new URL('./ReportProcessExecutionList.vue', import.meta.url), {
  'element-plus': { ElMessageBox: {} }, 'vue-router': { useRouter: () => ({ push() {} }) },
});
async function flush() { await new Promise(resolve => setImmediate(resolve)); await Vue.nextTick(); }
for (const [label, Component] of [['long task', List], ['report', ReportList]]) test(`${label} execution filter changes reset server pagination to page one`, async t => {
  const requests = [];
  t.mock.method(globalThis, 'fetch', async url => {
    const params = new URL(String(url), 'http://local').searchParams; requests.push(params);
    return new Response(JSON.stringify({ items: [{ id: 1, flowName: '任务', status: 'SUCCESS' }], total: 100, page: Number(params.get('page')), pageSize: 20 }));
  });
  const owner = Vue.ref('u1');
  const root = container();
  const app = renderer.createApp({ render: () => Vue.h(Component, { createdBy: owner.value }) });
  app.component('el-pagination', { emits: ['update:currentPage', 'current-change'], setup: (_, { emit }) =>
    () => Vue.h('button', { onClick: () => { emit('update:currentPage', 3); emit('current-change', 3); } }, '第三页') });
  for (const name of ['el-icon', 'el-tooltip']) app.component(name, { setup: (_, { slots }) => () => Vue.h('span', slots.default?.()) });
  app.mount(root); t.after(() => app.unmount()); await flush();
  find(root, node => node.type === 'button' && text(node) === '第三页').props.onClick(); await flush();
  assert.equal(requests.at(-1).get('page'), '3');
  owner.value = 'u2'; await flush(); await flush();
  assert.equal(requests.at(-1).get('page'), '1');
  assert.equal(requests.at(-1).get('createdBy'), 'u2');
});

test('late execution requests cannot overwrite the newly selected filter', async t => {
  const pending = {};
  const response = name => new Response(JSON.stringify({ items: [{ id: 1, flowName: name, status: 'SUCCESS' }], total: 1 }));
  t.mock.method(globalThis, 'fetch', url => {
    const status = new URL(String(url), 'http://local').searchParams.get('status');
    return status ? new Promise(resolve => { pending[status] = resolve; }) : Promise.resolve(response('初始'));
  });
  const root = container();
  const app = renderer.createApp(List);
  for (const name of ['el-icon', 'el-tooltip', 'el-pagination']) app.component(name, { setup: (_, { slots }) => () => Vue.h('span', slots.default?.()) });
  const instance = app.mount(root); t.after(() => app.unmount()); await flush();
  const oldRequest = instance.load('FAILED');
  const newRequest = instance.load('SUCCESS');
  pending.SUCCESS(response('最新筛选')); await newRequest; await flush();
  pending.FAILED(response('过期筛选')); await oldRequest; await flush();
  assert.match(text(root), /最新筛选/);
  assert.doesNotMatch(text(root), /过期筛选/);
});
