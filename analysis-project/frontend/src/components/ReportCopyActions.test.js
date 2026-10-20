import test from 'node:test';
import assert from 'node:assert/strict';
import * as Vue from 'vue';
import { loadModule } from '../../test/loadModule.js';
import { renderer, container, find, text } from '../../test/memoryRenderer.js';

globalThis.localStorage = { getItem: () => 'owner' };
const flush = async () => { await new Promise(resolve => setImmediate(resolve)); await Vue.nextTick(); };
const button = (root, label) => find(root, node => node.type === 'button' && text(node) === label);

for (const kind of ['flow', 'job']) {
  test(`${kind} detail copies its report, disables repeats, and shows success only after completion`, async t => {
    t.mock.method(globalThis, 'setInterval', () => 0);
    const requests = [], messages = [], copied = [];
    let finish;
    const gate = new Promise(resolve => { finish = resolve; });
    t.mock.method(globalThis, 'fetch', async (url, options) => {
      requests.push({ url: String(url), options });
      if (String(url).startsWith('blob:') || /\/report$|\/download$/.test(String(url))) return new Response('<div class="report">实际报告正文</div>', { headers: { 'Content-Type': 'text/html' } });
      if (/\/(nodes|metrics|notifications)$/.test(String(url))) return new Response('[]');
      return new Response(JSON.stringify(kind === 'flow' ? { id: 42, status: 'SUCCESS', triggerUserId: 'owner', reportPath: 'report.html' } : [{ id: 42, status: 'SUCCESS', mdFileExists: true }]));
    });
    const filename = kind === 'flow' ? 'SkillFlowExecutionDrawer.vue' : 'SkillJobExecutionDrawer.vue';
    const { default: Drawer } = await loadModule(new URL(`./${filename}`, import.meta.url), {
      'vue': { ...Vue, vModelSelect: {}, Transition: (_, { slots }) => slots.default?.() },
      'vue-router': { useRouter: () => ({ push() {} }) },
      'element-plus': { ElMessage: { success: message => messages.push(message), error: message => messages.push(`ERROR:${message}`) }, ElMessageBox: {} },
      '../utils/reportClipboard': { copyReportContent: async load => { copied.push(await load()); await gate; } },
    });
    const open = Vue.ref(false);
    let instance;
    const app = renderer.createApp({ render: () => Vue.h(Drawer, { ref: value => { instance = value; }, open: open.value, executionId: 42, jobId: 8, canDownload: true, canEdit: true }) });
    app.config.warnHandler = () => {};
    app.mount(container()); t.after(() => app.unmount());
    open.value = true; await flush(); await flush();
    const root = instance.$.subTree.target;
    if (kind === 'job') { find(root, node => node.props.class === 'exec-row').props.onClick(); await flush(); }
    const copy = button(root, '复制内容'); assert.ok(copy);
    const pending = copy.props.onClick({ stopPropagation() {} }); await flush(); await flush();
    assert.ok(button(root, '复制中…').props.disabled);
    assert.equal(messages.length, 0);
    assert.equal(copied.length, 1);
    assert.match(copied[0], /实际报告正文/);
    const authenticated = requests.find(request => /\/report$|\/download$/.test(request.url));
    assert.equal(authenticated.options.headers['X-User-Id'], 'owner');
    finish(); await pending; await flush();
    assert.ok(button(root, '复制内容'));
    assert.equal(messages.length, 1);
    assert.match(messages[0], /内容已复制/);
  });
}
