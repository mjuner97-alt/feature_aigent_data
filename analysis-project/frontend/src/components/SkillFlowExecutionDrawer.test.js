import test from 'node:test';
import assert from 'node:assert/strict';
import * as Vue from 'vue';
import { loadModule } from '../../test/loadModule.js';
import { renderer, container, text } from '../../test/memoryRenderer.js';

const { default: LongTaskDrawer } = await loadModule(new URL('./SkillFlowExecutionDrawer.vue', import.meta.url), {
  'vue-router': { useRouter: () => ({ push() {} }) },
  'element-plus': { ElMessageBox: {} },
});
const { default: ReportDrawer } = await loadModule(new URL('./ReportProcessExecutionDrawer.vue', import.meta.url), {
  'vue-router': { useRouter: () => ({ push() {} }) },
  'element-plus': { ElMessageBox: {} },
});

globalThis.localStorage = { getItem: () => 'u1' };
async function flush() { await new Promise(resolve => setImmediate(resolve)); await Vue.nextTick(); }

for (const [recordLabel, Drawer] of [['长任务', LongTaskDrawer], ['流程报告', ReportDrawer]]) {
  test(`${recordLabel} detail derives progress from loaded node statuses and refreshes after retry`, async t => {
    let statuses = ['SUCCESS', 'FAILED', 'BLOCKED', 'CANCELLED', 'RUNNING', 'QUEUED', 'PENDING', 'RETRY_WAIT'];
    t.mock.method(globalThis, 'fetch', async url => {
      if (String(url).endsWith('/nodes')) return new Response(JSON.stringify(statuses.map((status, index) => ({
        id: index + 1, nodeKey: `n${index}`, status, attempts: [],
      }))));
      if (String(url).endsWith('/metrics') || String(url).endsWith('/notifications')) return new Response('[]');
      return new Response(JSON.stringify({ id: 42, status: 'RUNNING', totalNodeCount: 0, completedNodeCount: 0 }));
    });
    const root = container();
    const open = Vue.ref(false);
    let drawer;
    const app = renderer.createApp({ setup: () => () => Vue.h(Drawer, { ref: value => { drawer = value; }, open: open.value, executionId: 42 }) });
    app.mount(root); t.after(() => app.unmount());
    open.value = true; await Vue.nextTick(); await flush(); await flush();
    assert.match(text(drawer.$.subTree.target), /4 \/ 8 已完成/);
    open.value = false; await Vue.nextTick();
    statuses = ['SUCCESS', 'RETRY_WAIT'];
    open.value = true; await Vue.nextTick(); await flush(); await flush();
    assert.match(text(drawer.$.subTree.target), /1 \/ 2 已完成/);
  });
}
