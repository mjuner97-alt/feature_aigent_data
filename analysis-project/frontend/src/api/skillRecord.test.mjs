import { test } from 'node:test';
import assert from 'node:assert/strict';
import ts from 'typescript';
import { readFileSync } from 'node:fs';
const source = readFileSync(new URL('./skillRecord.ts', import.meta.url), 'utf8').replace("import { apiError } from '../utils/apiError';", 'const apiError = async (_response, message) => new Error(message);');
const js = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 } }).outputText;
const api = await import(`data:text/javascript;base64,${Buffer.from(js).toString('base64')}`);
globalThis.window = { location: { origin: 'http://localhost' }, localStorage: { getItem() { throw new Error('must not use local storage'); }, setItem() { throw new Error('must not use local storage'); } } };
test('set, list and delete use configured service with correct payloads', async () => {
  const requests = [];
  globalThis.fetch = async (url, options) => {
    requests.push({ url, options });
    if (!options) return Response.json({ configValue: 'http://example.test/' });
    return Response.json({ result: 'success', data: [{ conversationId: 'one' }] });
  };
  await api.setConversationAsCase('one', 'CODE', 'owner');
  assert.deepEqual(await api.listConversationCases('owner'), [{ conversationId: 'one' }]);
  await api.deleteConversationCase('one');
  const posts = requests.filter(item => item.options);
  assert.deepEqual(posts.map(item => item.url), ['http://example.test/skillRecord/record', 'http://example.test/skillRecord/list', 'http://example.test/skillRecord/delete']);
  assert.deepEqual(posts.map(item => JSON.parse(item.options.body)), [{ conversationId: 'one', stdCode: 'CODE', owner: 'owner' }, { owner: 'owner' }, { conversationId: 'one' }]);
});
test('HTTP errors propagate for all case operations', async () => {
  globalThis.fetch = async (_url, options) => options ? new Response('', { status: 500 }) : Response.json({ configValue: 'http://example.test/' });
  await assert.rejects(() => api.setConversationAsCase('one', 'CODE', 'owner'));
  await assert.rejects(() => api.listConversationCases('owner'));
  await assert.rejects(() => api.deleteConversationCase('one'));
});
test('changing configured host and port redirects requests without a trailing slash', async () => {
  const requests = [];
  globalThis.fetch = async (url, options) => {
    if (!options) return Response.json({ configValue: ' http://another.test:9090 ' });
    requests.push(url);
    return Response.json({ result: 'success', data: [] });
  };
  await api.setConversationAsCase('one', 'CODE', 'owner');
  await api.listConversationCases('owner');
  await api.deleteConversationCase('one');
  assert.deepEqual(requests, ['http://another.test:9090/skillRecord/record', 'http://another.test:9090/skillRecord/list', 'http://another.test:9090/skillRecord/delete']);
});
test('invalid list and failed delete responses are reported', async () => {
  globalThis.fetch = async (_url, options) => Response.json(options ? { result: '失败' } : { configValue: 'http://example.test/' });
  await assert.rejects(() => api.listConversationCases('owner'));
  await assert.rejects(() => api.deleteConversationCase('one'));
});



test('set and cancel return success message and reject failed result with server message', async () => {
  for (const operation of [() => api.setConversationAsCase('one', 'CODE', 'owner'), () => api.deleteConversationCase('one')]) {
    globalThis.fetch = async (_url, options) => Response.json(options ? { result: 'success', message: '服务端成功提示' } : { configValue: 'http://example.test' });
    assert.equal(await operation(), '服务端成功提示');
    globalThis.fetch = async (_url, options) => Response.json(options ? { result: 'failed', message: '服务端失败提示' } : { configValue: 'http://example.test' });
    await assert.rejects(operation, { message: '服务端失败提示' });
  }
});
