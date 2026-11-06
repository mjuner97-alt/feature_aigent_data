import test from 'node:test';
import assert from 'node:assert/strict';
import * as Vue from 'vue';
const { nextTick, h } = Vue;
import { loadModule } from '../../../test/loadModule.js';
import { renderer, container, find, text } from '../../../test/memoryRenderer.js';

test('task type filter resets pagination and stays selected across pages', async () => {
  const requests = [];
  const { default: Page } = await loadModule(new URL('./LongTaskConfigPage.vue', import.meta.url), {
    vue: { ...Vue, vModelSelect: {}, vModelText: {} },
    'element-plus': { ElMessage: { error(message) { throw new Error(message); } }, ElMessageBox: {} },
    '../../api/skillFlow': { updateSkillFlowConfig() {}, listSkillFlowPage: async options => {
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

for (const fails of [false, true]) {
  test(`config save ${fails ? 'shows backend error and keeps dialog open' : 'sends only type and enabled'}`, async () => {
    const writes = [], errors = [], successes = [];
    const { default: Page } = await loadModule(new URL('./LongTaskConfigPage.vue', import.meta.url), {
      vue: { ...Vue, vModelSelect: {}, vModelText: {} },
      'element-plus': { ElMessage: { error: m => errors.push(m), success: m => successes.push(m) }, ElMessageBox: {} },
      '../../api/skillFlow': {
        listSkillFlowPage: async () => ({ items: [{ id: 9, name: 'task', typeId: 1, enabled: true }], total: 1, page: 1 }),
        updateSkillFlowConfig: async (id, input) => { writes.push({ id, input }); if (fails) throw new Error('backend failure'); },
      },
      '../../api/longTaskConfig': {
        listLongTaskTypes: async () => ({ items: [{ id: 7, name: 'type', enabled: true }], total: 1 }),
        createLongTaskType() {}, deleteLongTaskType() {}, updateLongTaskType() {},
      },
      '../../api/skill': { batchUserNames: async () => ({}), searchSkillUsers: async () => [] },
    });
    const root = container(), app = renderer.createApp(Page);
    app.config.warnHandler = () => {};
    app.component('el-dialog', { props: ['modelValue', 'title'], setup: (props, { slots }) => () => props.modelValue ? h('dialog', { title: props.title }, [slots.default?.(), slots.footer?.()]) : null });
    app.mount(root);
    const flush = async () => { await new Promise(resolve => setImmediate(resolve)); await nextTick(); };
    try {
      await flush();
      await find(root, n => n.type === 'button' && text(n) === '编辑').props.onClick();
      await flush();
      const dialog = find(root, n => n.type === 'dialog');
      find(dialog, n => n.type === 'el-select').props['onUpdate:modelValue'](7);
      await find(dialog, n => n.type === 'button' && text(n) === '保存').props.onClick();
      await flush();
      assert.deepEqual(writes, [{ id: 9, input: { typeId: 7, enabled: true } }]);
      assert.equal(Boolean(find(root, n => n.type === 'dialog')), fails);
      assert.deepEqual(errors, fails ? ['backend failure'] : []);
      assert.equal(successes.length, fails ? 0 : 1);
    } finally { app.unmount(); }
  });
}
