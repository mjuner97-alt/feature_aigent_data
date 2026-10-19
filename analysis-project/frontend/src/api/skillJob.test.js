import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { loadModule } from '../../test/loadModule.js';
const api = await loadModule(new URL('./skillJob.ts', import.meta.url));
globalThis.localStorage = { getItem: () => 'test-user' };
for (const [method,path,filters] of [['listJobPage','/api/skill-jobs/page',{enabled:false,keyword:'weekly',createdBy:'owner'}],['listExecutionCenterPage','/api/skill-jobs/executions/page',{status:'FAILED',createdBy:'owner'}]]) {
 test(`${method} requests a server page and preserves total`, async t => {
  let url; const body={items:[],page:3,pageSize:10,total:82};
  t.mock.method(globalThis,'fetch',async input=>{url=new URL(input,'http://localhost');return new Response(JSON.stringify(body));});
  assert.deepEqual(await api[method]({...filters,page:3,pageSize:10}),body);
  assert.equal(url.pathname,path);
  assert.deepEqual(Object.fromEntries(url.searchParams),Object.fromEntries(Object.entries({...filters,page:3,pageSize:10}).map(([k,v])=>[k,String(v)])));
 });
}
test('job page uses server totals and lazy tabs without full metric prefetch',async()=>{
 const page=await readFile(new URL('../pages/skill/SkillJobListPage.vue',import.meta.url),'utf8');
 assert.doesNotMatch(page,/\.slice\(|await listMetrics\(/);
 assert.match(page,/:total="manageTotal"/); assert.match(page,/:total="executionTotal"/);
 assert.match(page,/watch\(activeTab/);
});
