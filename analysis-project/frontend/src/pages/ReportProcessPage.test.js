import test from 'node:test';
import assert from 'node:assert/strict';
import { h, nextTick, provide, inject } from 'vue';
import { loadModule } from '../../test/loadModule.js';
import { renderer, container, find, text } from '../../test/memoryRenderer.js';

const messages = [];
const { default: ReportProcessPage } = await loadModule(new URL('./ReportProcessPage.vue', import.meta.url), {
  'vue-router': { useRouter: () => ({ push() {} }) },
  'element-plus': { ElMessage: { warning: message => messages.push(message), error: message => messages.push(message), success() {} }, ElMessageBox: {} },
});
globalThis.localStorage = { getItem: () => 'test-user' };

async function flush() { await new Promise(resolve => setImmediate(resolve)); await nextTick(); }
function mount() {
  const root = container();
  const app = renderer.createApp(ReportProcessPage);
  for (const name of ['el-form', 'el-form-item', 'el-radio-group', 'el-radio-button', 'el-option', 'el-tag', 'el-pagination', 'el-switch', 'el-icon', 'el-tooltip']) {
    app.component(name, { setup: (_, { attrs, slots }) => () => h('div', attrs, slots.default?.()) });
  }
  app.component('el-table', { props: ['data'], setup: (props, { slots }) => {
    provide('tableRows', () => props.data);
    return () => h('div', slots.default?.());
  } });
  app.component('el-table-column', { setup: (_, { slots }) => {
    const rows = inject('tableRows');
    return () => h('div', rows().flatMap(row => slots.default?.({ row }) || []));
  } });
  for (const [name, tag] of [['el-input', 'input'], ['el-select', 'select'], ['el-button', 'button']]) {
    app.component(name, { setup: (_, { attrs, slots }) => () => h(tag, attrs, slots.default?.()) });
  }
  app.component('el-dialog', { props: ['modelValue'], setup: (props, { slots }) => () => h('div', props.modelValue ? [slots.default?.(), slots.footer?.()] : []) });
  app.directive('loading', {});
  app.mount(root);
  return { root, app };
}
function reportResponse(url, row) {
  if(String(url).includes('/page?')) { const {nodes,reportOutline,...summary}=row||{};return new Response(JSON.stringify({items:row?[{...summary,nodeCount:nodes.length}]:[],page:1,pageSize:20,total:row?1:0})); }
  return new Response(JSON.stringify(row));
}
const button = (root, label) => find(root, node => node.type === 'button' && text(node) === label);
const input = (root, placeholder) => find(root, node => node.type === 'input' && node.props.placeholder === placeholder);

test('copy preserves page-configured dynamic parameters without requesting any legacy preset rules', async t => {
  let attempts = 0, saved;
  const period = { $dynamic: { v: 1, kind: 'period_window', unit: 'month', startOffset: -1, endOffset: -1, format: '{year}-{month:02}', order: 'asc', output: 'single' } };
  const original = {
    id: 2, name: '月报', createdBy: 'test-user', enabled: false,
    nodes: [{ nodeKey: 'n1', nodeName: '数据', nodeType: 'PYTHON', scriptId: 'demo', scriptParams: { period } }],
    reportOutline: { title: '月度报告', items: [{ id: 'c1', title: '第一章', nodeKeys: ['n1'], children: [] }] },
  };
  t.mock.method(globalThis, 'fetch', async (url, options) => {
    if (url === '/api/script-param-rules') {
      attempts++;
      return attempts === 1 ? new Response('{}', { status: 503 }) : new Response('[]');
    }
    if (url === '/api/script-registry') return new Response(JSON.stringify([{ id: 7, scriptId: 'demo', name: '脚本', enabled: 1 }]));
    if (url === '/api/script-registry/get?id=7') return new Response(JSON.stringify({ paramsSchema: '[{"name":"period","type":"string"}]' }));
    if (options?.method === 'POST') { saved = JSON.parse(options.body); return new Response(JSON.stringify({ ...saved, id: 3 })); }
    if (String(url).startsWith('/api/report-processes')) return reportResponse(url,original);
    if(String(url).startsWith('/api/report-processes/page'))return reportResponse(url);
    return new Response('[]');
  });
  const { root, app } = mount(); t.after(() => app.unmount()); await flush(); await flush();
  await button(root, '复制').props.onClick(); await nextTick();
  assert.equal(attempts, 0, 'copy must not query the retired preset endpoint');
  input(root, '请输入唯一的报告流程名称').props['onUpdate:modelValue']('月报新副本');

  assert.doesNotMatch(text(root), /预设规则列表暂不可用/);
  await button(root, '保存').props.onClick();
  assert.equal(saved.name, '月报新副本');
  assert.deepEqual(JSON.parse(saved.nodes[0].scriptParamsJson), { period });
});

test('report records live inside the report page and request only template executions', async t => {
  const urls = [];
  t.mock.method(globalThis, 'fetch', async url => {
    urls.push(String(url));
    return new Response(String(url).startsWith('/api/skill-flow-executions') ? JSON.stringify({items:[],total:0}) : JSON.stringify({items:[],page:1,pageSize:20,total:0}));
  });
  const {root,app}=mount();t.after(()=>app.unmount());await flush();
  assert.ok(button(root,'报告流程'));
  assert.ok(button(root,'流程报告记录'));
  button(root,'流程报告记录').props.onClick();await flush();
  assert.ok(urls.some(url=>url.includes('/api/skill-flow-executions?') && new URLSearchParams(url.split('?')[1]).get('source')==='report-template'));
  assert.match(text(root),/暂无流程报告记录/);
  assert.equal(!!button(root,'新建报告流程'),false);
  button(root,'报告流程').props.onClick();await flush();assert.ok(button(root,'新建报告流程'));
});

test('template editor preserves parameters typed while schema loads and prevents early save', async t => {
  let releaseSchema, saved;
  const schemaReady = new Promise(resolve => { releaseSchema = resolve; });
  const schema = [{ name: 'period', type: 'string', required: true, default: '2026Q4' }];
  t.mock.method(globalThis, 'fetch', async (url, options) => {
    if (url === '/api/script-registry/get?id=7') { await schemaReady; return new Response(JSON.stringify({ paramsSchema: JSON.stringify(schema) })); }
    if (url === '/api/script-registry') return new Response(JSON.stringify([{ id: 7, scriptId: 'demo', name: '示例脚本', enabled: 1 }]));
    if (url === '/api/script-param-rules') return new Response(JSON.stringify([{ ruleKey: 'current_quarter', ruleName: '当前季度', valueType: 'single' }]));
    if (options?.method === 'POST') { saved = JSON.parse(options.body); return new Response(JSON.stringify({ ...saved, id: 1 })); }
    if(String(url).startsWith('/api/report-processes/page'))return reportResponse(url);
    return new Response('[]');
  });
  const { root, app } = mount();
  t.after(() => app.unmount());
  await flush();
  await button(root, '新建报告流程').props.onClick();
  await flush();
  input(root, '请输入唯一的报告流程名称').props['onUpdate:modelValue']('模板');
  input(root, '例如：月度经营分析报告').props['onUpdate:modelValue']('报告');
  button(root, '添加章节').props.onClick();
  await nextTick();
  input(root, '章节标题').props['onUpdate:modelValue']('第一章');
  button(root, '＋ 添加节点').props.onClick();
  await nextTick();
  const select = find(root, node => node.type === 'select' && node.props.placeholder === '请选择脚本');
  select.props['onUpdate:modelValue']('demo');
  select.props.onChange();
  await nextTick();
  const jsonInput = find(root, node => node.type === 'textarea');
  const params = { period: {"$dynamic": {"v": 1, "kind": "period_window", "unit": "month", "startOffset": -1, "endOffset": -1, "format": "{year}年{month}月份版本", "order": "asc", "output": "single"}} };
  jsonInput.props.onInput({ target: { value: JSON.stringify(params) } });
  await button(root, '保存').props.onClick();
  assert.equal(saved, undefined, 'must wait for the selected script schema before saving');
  releaseSchema();
  await flush();
  await flush();

  await button(root, '保存').props.onClick();
  assert.deepEqual(JSON.parse(saved.nodes[0].scriptParamsJson), params);
});

test('editing an old template submits displayed defaults and preserves page-configured dynamic periods on copy', async t => {
  let saved;
  const original = {
    id: 2, name: '旧模板', createdBy: 'test-user', enabled: false,
    nodes: [{ nodeKey: 'n1', nodeName: '数据', nodeType: 'PYTHON', scriptId: 'demo', scriptParamsJson: '{}' }],
    reportOutline: { title: '报告', items: [{ id: 'c1', title: '第一章', nodeKeys: ['n1'], children: [] }] },
  };
  t.mock.method(globalThis, 'fetch', async (url, options) => {
    if (url === '/api/script-registry') return new Response(JSON.stringify([{ id: 7, scriptId: 'demo', name: '示例脚本', enabled: 1 }]));
    if (url === '/api/script-registry/get?id=7') return new Response(JSON.stringify({ paramsSchema: JSON.stringify([
      { name: 'period', type: 'string', required: true, default: '2026Q4' },
      { name: 'regions', type: 'string[]', default: ['华东'] },
    ]) }));
    if (url === '/api/script-param-rules') return new Response(JSON.stringify([{ ruleKey: 'current_quarter', ruleName: '当前季度', valueType: 'single' }]));
    if (options?.method === 'PUT' || options?.method === 'POST') {
      saved = { ...original, ...JSON.parse(options.body) };
      return new Response(JSON.stringify(saved));
    }
    if (url.startsWith('/api/report-processes')) return reportResponse(url,saved || original);
    return new Response('{}');
  });
  const { root, app } = mount();
  t.after(() => app.unmount());
  await flush(); await flush();
  await button(root, '编辑').props.onClick();
  await nextTick();
  assert.ok(find(root, node => node.type === 'input' && node.props.value === '2026Q4'));
  await button(root, '保存').props.onClick();
  assert.deepEqual(JSON.parse(saved.nodes[0].scriptParamsJson), { period: '2026Q4', regions: ['华东'] });

  await flush();
  await button(root, '编辑').props.onClick();
  await nextTick();
  const mode = find(root, node => node.type === 'select' && node.props.value === 'LITERAL');
  mode.props.onChange({ target: { value: 'DYNAMIC_PERIOD' } });
  await nextTick();
  await button(root, '确定').props.onClick();
  await nextTick();
  await button(root, '保存').props.onClick();
  const expected = { period: {"$dynamic": {"v": 1, "kind": "period_window", "unit": "month", "startOffset": -1, "endOffset": -1, "format": "{year}年{month}月份版本", "order": "asc", "output": "single"}}, regions: ['华东'] };
  assert.deepEqual(JSON.parse(saved.nodes[0].scriptParamsJson), expected);

  await flush();
  await button(root, '复制').props.onClick();
  await nextTick();

  await button(root, '保存').props.onClick();
  assert.deepEqual(JSON.parse(saved.nodes[0].scriptParamsJson), expected);
  assert.equal(saved.name, '旧模板-副本');
});

test('late initial schema response cannot add old script parameters after switching scripts', async t => {
  let releaseOldSchema, saved;
  const oldSchemaReady = new Promise(resolve => { releaseOldSchema = resolve; });
  const original = {
    id: 2, name: '模板', createdBy: 'test-user', enabled: false,
    nodes: [{ nodeKey: 'n1', nodeName: '数据', nodeType: 'PYTHON', scriptId: 'old', scriptParamsJson: '{}' }],
    reportOutline: { title: '报告', items: [{ id: 'c1', title: '第一章', nodeKeys: ['n1'], children: [] }] },
  };
  t.mock.method(globalThis, 'fetch', async (url, options) => {
    if (url === '/api/script-registry') return new Response(JSON.stringify([
      { id: 7, scriptId: 'old', name: '旧脚本', enabled: 1 }, { id: 8, scriptId: 'new', name: '新脚本', enabled: 1 },
    ]));
    if (url === '/api/script-registry/get?id=7') {
      await oldSchemaReady;
      return new Response(JSON.stringify({ paramsSchema: '[{"name":"old_period","type":"string","default":"old"}]' }));
    }
    if (url === '/api/script-registry/get?id=8') return new Response(JSON.stringify({ paramsSchema: '[{"name":"new_period","type":"string","default":"new"}]' }));
    if (url === '/api/script-param-rules') return new Response('[]');
    if (options?.method === 'PUT') { saved = { ...original, ...JSON.parse(options.body) }; return new Response(JSON.stringify(saved)); }
    if (url.startsWith('/api/report-processes')) return reportResponse(url,original);
    return new Response('{}');
  });
  const { root, app } = mount();
  t.after(() => app.unmount());
  await flush(); await flush();
  const opening = button(root, '编辑').props.onClick();
  await flush(); await flush();
  const select = find(root, node => node.type === 'select' && node.props.placeholder === '请选择脚本');
  select.props['onUpdate:modelValue']('new');
  select.props.onChange();
  await flush();
  releaseOldSchema();
  await opening;
  await nextTick();
  await button(root, '保存').props.onClick();
  assert.equal(saved.nodes[0].scriptId, 'new');
  assert.deepEqual(JSON.parse(saved.nodes[0].scriptParamsJson), { new_period: 'new' });
});

test('four complete months configured in a dialog survive template save, reopen and copy', async t => {
  let saved;
  const original = {
    id: 2, name: '月报模板', createdBy: 'test-user', enabled: false,
    nodes: [{ nodeKey: 'n1', nodeName: '数据', nodeType: 'PYTHON', scriptId: 'demo', scriptParamsJson: '{}' }],
    reportOutline: { title: '报告', items: [{ id: 'c1', title: '章节', nodeKeys: ['n1'], children: [] }] },
  };
  t.mock.method(globalThis, 'fetch', async (url, options) => {
    if (url === '/api/script-registry') return new Response(JSON.stringify([{ id: 7, scriptId: 'demo', name: '月报脚本', enabled: 1 }]));
    if (url === '/api/script-registry/get?id=7') return new Response(JSON.stringify({ paramsSchema: '[{"name":"periods","type":"string[]"}]' }));
    if (url === '/api/script-param-rules') return new Response('[]');
    assert.notEqual(url, '/api/script-param-rules/preview');
    if (options?.method === 'PUT' || options?.method === 'POST') {
      saved = { ...original, ...JSON.parse(options.body) };
      return new Response(JSON.stringify(saved));
    }
    if (url.startsWith('/api/report-processes')) return reportResponse(url,saved || original);
    return new Response('{}');
  });
  const { root, app } = mount();
  t.after(() => app.unmount());
  await flush(); await flush();
  await button(root, '编辑').props.onClick();
  await nextTick();
  const mode = find(root, node => node.type === 'select' && node.props.value === 'LITERAL');
  mode.props.onChange({ target: { value: 'DYNAMIC_PERIOD' } });
  await nextTick();
  find(root, node => node.props['aria-label'] === '周期数量').props.onInput({ target: { value: '4' } });
  await nextTick();
  await button(root, '确定').props.onClick();
  await nextTick();
  assert.match(text(root), /最近 4/);
  assert.equal(find(root, node => node.props['aria-label'] === '预览基准日期'), null);
  await button(root, '保存').props.onClick();
  const expected = { periods: { $dynamic: { v: 1, kind: 'period_window', unit: 'month', startOffset: -4,
    endOffset: -1, format: '{year}年{month}月份版本', order: 'asc', output: 'list' } } };
  assert.deepEqual(JSON.parse(saved.nodes[0].scriptParamsJson), expected);
  await flush();
  await button(root, '编辑').props.onClick();
  await nextTick();
  assert.match(text(root), /最近 4/);
  await button(root, '修改').props.onClick();
  await nextTick();
  assert.equal(find(root, node => node.props['aria-label'] === '周期数量').props.value, 4);
  await button(root, '取消').props.onClick();
  await nextTick();
  await button(root, '保存').props.onClick();
  await flush();
  await button(root, '复制').props.onClick();
  await nextTick();
  await button(root, '保存').props.onClick();
  assert.equal(saved.name, '月报模板-副本');
  assert.deepEqual(JSON.parse(saved.nodes[0].scriptParamsJson), expected);
});

test('templates request server pages and filters, hydrate only opened details, and leave hidden tab idle', async t => {
  const urls=[];
  const original={id:9,name:'分页模板',createdBy:'test-user',enabled:true,nodes:[],reportOutline:{title:'完整目录',items:[]}};
  t.mock.method(globalThis,'fetch',async url=>{
    urls.push(String(url));
    if(String(url).startsWith('/api/report-processes/page')){
      const query=new URLSearchParams(String(url).split('?')[1]);
      return new Response(JSON.stringify({items:[{id:9,name:original.name,createdBy:'test-user',enabled:true,nodeCount:0}],page:Number(query.get('page')),pageSize:Number(query.get('pageSize')),total:53}));
    }
    if(url==='/api/report-processes/9')return new Response(JSON.stringify(original));
    if(String(url).startsWith('/api/skill-flow-executions'))return new Response(JSON.stringify({items:[],page:1,pageSize:20,total:0}));
    return new Response('[]');
  });
  const {root,app}=mount();t.after(()=>app.unmount());await flush();await flush();
  assert.equal(urls.filter(url=>url.startsWith('/api/report-processes/page')).length,1);
  assert.equal(urls.some(url=>url.startsWith('/api/script-registry')||url.startsWith('/api/skill-flow-executions')||url==='/api/report-processes/9'),false);
  let pager=find(root,node=>node.props.total===53);
  assert.ok(pager);
  pager.props.onCurrentChange(2);await flush();
  assert.ok(urls.some(url=>url.includes('page=2')));
  pager=find(root,node=>node.props.total===53);pager.props.onSizeChange(10);await flush();
  assert.ok(urls.some(url=>url.includes('page=1')&&url.includes('pageSize=10')));
  input(root,'搜索名称 / 编码').props['onUpdate:modelValue']('季度');await flush();
  assert.equal(new URLSearchParams(urls.filter(url=>url.startsWith('/api/report-processes/page')).at(-1).split('?')[1]).get('keyword'),'季度');
  await button(root,'查看').props.onClick();await flush();
  assert.ok(urls.includes('/api/report-processes/9'));
  assert.equal(urls.some(url=>url.startsWith('/api/script-registry')),false);
  button(root,'流程报告记录').props.onClick();await flush();
  const count=urls.filter(url=>url.startsWith('/api/report-processes/page')).length;
  const scopeControl=find(root,node=>node.props.modelValue==='mine'&&node.props['onUpdate:modelValue']);
  scopeControl.props['onUpdate:modelValue']('all');await flush();
  assert.equal(urls.filter(url=>url.startsWith('/api/report-processes/page')).length,count);
});
