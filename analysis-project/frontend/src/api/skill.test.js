import test from 'node:test';
import assert from 'node:assert/strict';
import { loadModule } from '../../test/loadModule.js';

test('save retains rate limit details and request identifiers', async () => {
  globalThis.localStorage = { getItem: () => 'owner' };
  globalThis.fetch = async () => new Response(JSON.stringify({ message: 'gateway write quota exceeded' }), {
    status: 429, headers: { 'content-type': 'application/json', 'retry-after': '30', 'x-request-id': 'save-123' },
  });
  const { saveSkillForm } = await loadModule(new URL('./skill.ts', import.meta.url));
  await assert.rejects(saveSkillForm({}), error => {
    for (const text of ['gateway write quota exceeded', '429', '30', 'save-123']) assert.ok(error.message.includes(text));
    return true;
  });
});

test('ordinary conflict retains its business error', async () => {
  globalThis.localStorage = { getItem: () => 'owner' };
  globalThis.fetch = async () => new Response(JSON.stringify({ message: 'SkillNameConflict: duplicate' }), {
    status: 409, headers: { 'content-type': 'application/json' },
  });
  const { saveSkillForm } = await loadModule(new URL('./skill.ts', import.meta.url));
  await assert.rejects(saveSkillForm({}), /名称已存在/);
});

test('form save sends all publications and relations in a single request and can retry', async () => {
  globalThis.localStorage = { getItem: () => 'owner' };
  const requests = [];
  globalThis.fetch = async (url, options) => {
    requests.push({ url, options });
    return requests.length === 1
      ? new Response(JSON.stringify({ message: 'NoApproverConfigured: GROUP:missing' }), { status: 500, headers: { 'content-type': 'application/json' } })
      : new Response(JSON.stringify({ id: 7 }), { status: 200 });
  };
  const { saveSkillForm } = await loadModule(new URL('./skill.ts', import.meta.url));
  const input = { id: null, skill: { name: 'same', description: 'desc' }, targets: [{ targetType: 'GROUP', targetId: 'missing', targetName: 'Missing' }], fileIds: [3], grants: [] };
  await assert.rejects(saveSkillForm(input));
  assert.equal(requests.length, 1);
  assert.equal(requests[0].url, '/api/skills/save');
  assert.deepEqual(JSON.parse(requests[0].options.body), input);
  input.targets[0].targetId = 'configured';
  assert.equal((await saveSkillForm(input)).id, 7);
  assert.equal(requests.length, 2);
});
