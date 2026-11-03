import test from 'node:test';
import assert from 'node:assert/strict';
import * as Vue from 'vue';
const { nextTick, h } = Vue;
import { loadModule } from '../../../test/loadModule.js';
import { renderer, container, find } from '../../../test/memoryRenderer.js';

test('task type filter resets pagination and stays selected across pages', async () => {
  const requests = [];
  const { default: Page } = await loadModule(new URL('./LongTaskConfigPage.vue', import.meta.url), {
    vue: { ...Vue, vModelSelect: {}, vModelText: {} },
    'element-plus': { ElMessage: { error(message) { throw new Error(message); } }, ElMessageBox: {} },
    '../../api/skillFlow': { listSkillFlowPage: async options => {
      requests.push(options);
      return { items: [{ id: 1, name: 'demo' }], total: 60, page: options.page };
    } },
    '../../api/longTaskConfig': {
      listLongTaskTypes: async () => ({ items: [{ id: 7, name: '审计', enabled: true }], total: 1 }),
      createLongTaskType() {}, deleteLongTaskType() {}, updateLongTaskType() {},
    },
    '../../api/skill': { batchUserNames: async () => ({}), searchSkillUsers: async () => [] },
  });
  const root = container(), app = renderer.createApp(Page);
  app.config.warnHandler = () => {};
  app.component('el-pagination', { setup: (_, { attrs }) => () => h('pager', attrs) });
  app.mount(root);
  const flush = async () => { await new Promise(resolve => setImmediate(resolve)); await nextTick(); };
  try {
    await flush();
    const select = find(root, node => node.type === 'select' && node.props['aria-label'] === '任务类型筛选');
    assert.ok(select);
    find(root, node => node.type === 'pager').props.onCurrentChange(3);
    await flush();
    assert.equal(requests.at(-1).page, 3);
    select.props['onUpdate:modelValue'](7);
    select.props.onChange();
    await flush();
    assert.equal(requests.at(-1).typeId, 7);
    assert.equal(requests.at(-1).page, 1);
    find(root, node => node.type === 'pager').props.onCurrentChange(2);
    await flush();
    assert.equal(requests.at(-1).page, 2);
    assert.equal(requests.at(-1).typeId, 7);
    select.props['onUpdate:modelValue'](undefined);
    select.props.onChange();
    await flush();
    assert.equal(requests.at(-1).typeId, undefined);
  } finally { app.unmount(); }
});
