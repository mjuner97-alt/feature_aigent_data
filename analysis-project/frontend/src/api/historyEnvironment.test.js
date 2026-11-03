import test from 'node:test';
import assert from 'node:assert/strict';
import { loadModule } from '../../test/loadModule.js';
const trace = await loadModule(new URL('./trace.ts', import.meta.url));
const logs = await loadModule(new URL('./longTaskNodeLog.ts', import.meta.url));
globalThis.localStorage = { getItem: () => 'test-user' };
for (const environment of ['prod', 'dev', 'ALL']) {
  test(`history APIs forward environment ${environment} with pagination`, async t => {
    const urls = [];
    t.mock.method(globalThis, 'fetch', async url => {
      urls.push(new URL(String(url), 'http://localhost'));
      return new Response(JSON.stringify({ items: [], conversations: [], total: 0 }));
    });
    await trace.listConversations(undefined, 'user', 'keyword', 2, 20, environment);
    await logs.listNodeLogs({ environment, page: 3, flowName: 'flow' });
    assert.equal(urls[0].searchParams.get('environment'), environment);
    assert.equal(urls[0].searchParams.get('page'), '2');
    assert.equal(urls[0].searchParams.get('keyword'), 'keyword');
    assert.equal(urls[1].searchParams.get('environment'), environment);
    assert.equal(urls[1].searchParams.get('page'), '3');
    assert.equal(urls[1].searchParams.get('flowName'), 'flow');
  });
}
