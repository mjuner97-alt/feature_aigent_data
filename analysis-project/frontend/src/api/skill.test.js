import test from 'node:test';
import assert from 'node:assert/strict';
import { loadModule } from '../../test/loadModule.js';

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
