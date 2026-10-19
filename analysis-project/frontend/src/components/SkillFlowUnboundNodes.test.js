import test from 'node:test';
import assert from 'node:assert/strict';
import { h, nextTick } from 'vue';
import { loadModule } from '../../test/loadModule.js';
import { renderer, container, find, text } from '../../test/memoryRenderer.js';

globalThis.localStorage = { getItem: () => 'owner' };
globalThis.Document ??= class Document {};
globalThis.ShadowRoot ??= class ShadowRoot {};
const button = (root, label) => find(root, n => n.type === 'button' && text(n) === label);

test('an orphan left by removing a node from a chapter can be deleted and the flow saved', async t => {
  const saved = [], alerts = [];
  const source = {
    id: 7, name: '月报', enabled: true, notifyEnabled: false,
    triggers: [{ keyword: '月报', enabled: true }],
    nodes: ['keep', 'orphan'].map((key, index) => ({ nodeKey: key, nodeName: key, nodeType: 'PYTHON', scriptId: 'demo', scriptParams: {}, sortOrder: index + 1 })),
    reportOutline: { items: [{ id: 'c1', title: '第一章', level: 1, nodeKeys: ['keep'], children: [] }] },
  };
  t.mock.method(globalThis, 'fetch', async () => new Response('[]'));
  const { default: Editor } = await loadModule(new URL('./SkillFlowEditorDrawer.vue', import.meta.url), {
    '../api/skillFlow': {
      getSkillFlow: async () => source,
      getFlowNotifySettings: async () => ({}),
      updateSkillFlow: async (_, payload) => saved.push(JSON.parse(JSON.stringify(payload))),
    },
    'element-plus': { ElMessage: { info() {}, error() {}, warning() {} }, ElMessageBox: { alert: async message => alerts.push(message) } },
  });
  const root = container(), app = renderer.createApp(Editor, { open: true, page: true, editId: 7, knownFlows: [] });
  for (const name of ['el-select', 'el-option', 'el-tooltip', 'el-icon', 'el-button']) app.component(name, { setup: (_, { slots }) => () => h('span', slots.default?.()) });
  app.component('el-dialog', { setup: () => () => h('span') });
  app.mount(root); t.after(() => app.unmount());
  for (let i = 0; i < 3; i++) { await new Promise(resolve => setImmediate(resolve)); await nextTick(); }
  assert.match(text(root), /节点 orphan 未绑定到报告大纲/);
  const remove = find(root, n => n.type === 'button' && n.props['aria-label'] === '删除未绑定节点 orphan');
  assert.ok(remove, 'orphan nodes need a direct delete action');
  remove.props.onClick(); await nextTick();
  assert.doesNotMatch(text(root), /未绑定到报告大纲/);
  await button(root, '保存流程').props.onClick();
  assert.deepEqual(alerts, []);
  assert.equal(saved.length, 1);
  assert.deepEqual(saved[0].nodes.map(n => n.nodeKey), ['keep']);
  assert.deepEqual(saved[0].reportOutline.items[0].nodeKeys, ['keep']);
});

