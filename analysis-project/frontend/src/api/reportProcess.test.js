import test from 'node:test';
import assert from 'node:assert/strict';
import { loadModule } from '../../test/loadModule.js';

const api = await loadModule(new URL('./reportProcess.ts', import.meta.url));
globalThis.localStorage = { getItem: () => 'test-user' };
const params = { period: {"$dynamic": {"v": 1, "kind": "period_window", "unit": "quarter", "startOffset": 0, "endOffset": 0, "format": "{year}Q{quarter}", "order": "asc", "output": "single"}}, regions: ['华东', '华南'] };
const node = { nodeKey: 'n1', nodeType: 'PYTHON', scriptId: 'demo', scriptParams: params };
const input = { name: '测试模板', nodes: [node], reportOutline: {}, enabled: false };

for (const operation of ['create', 'update']) {
  test(`${operation} sends page-configured dynamic parameters in the execution-compatible scriptParamsJson field`, async t => {
    let sent;
    t.mock.method(globalThis, 'fetch', async (_, options) => {
      sent = JSON.parse(options.body);
      return new Response(JSON.stringify({ ...sent, id: 1 }));
    });
    const result = operation === 'create'
      ? await api.createReportProcess(input) : await api.updateReportProcess(1, input);
    assert.deepEqual(JSON.parse(sent.nodes[0].scriptParamsJson), params);
    assert.deepEqual(result.nodes[0].scriptParams, params);
    assert.deepEqual(input.nodes[0].scriptParams, params);
  });
}

test('opens persisted JSON parameters without dropping dynamic configurations', async t => {
  const persisted = { ...node, scriptParams: undefined, scriptParamsJson: JSON.stringify(params) };
  t.mock.method(globalThis, 'fetch', async () => new Response(JSON.stringify([{ ...input, nodes: [persisted] }])));
  const [result] = await api.listReportProcesses();
  assert.deepEqual(result.nodes[0].scriptParams, params);
});

test('retains legacy object parameters when listing templates', async t => {
  t.mock.method(globalThis, 'fetch', async () => new Response(JSON.stringify([input])));
  const [result] = await api.listReportProcesses();
  assert.deepEqual(result.nodes[0].scriptParams, params);
});

test('paged report summaries send server filters without restoring definition nodes', async t => {
  let requested;
  const body = { items: [{ id: 8, name: 'summary', nodeCount: 3 }], page: 2, pageSize: 10, total: 31 };
  t.mock.method(globalThis, 'fetch', async url => { requested=String(url); return new Response(JSON.stringify(body)); });
  assert.deepEqual(await api.listReportProcessPage({scope:'all',keyword:'report',createdBy:'owner',page:2,pageSize:10}), body);
  const url = new URL(requested, 'http://localhost');
  assert.equal(url.pathname, '/api/report-processes/page');
  assert.deepEqual(Object.fromEntries(url.searchParams), {scope:'all',page:'2',pageSize:'10',keyword:'report',createdBy:'owner'});
});
