import test from 'node:test';
import assert from 'node:assert/strict';
import {h,nextTick} from 'vue';
import {loadModule} from '../../test/loadModule.js';
import {renderer,container,find,text} from '../../test/memoryRenderer.js';
const {default:List}=await loadModule(new URL('./SkillFlowList.vue',import.meta.url),{'vue-router':{useRouter:()=>({push(){}})}});
globalThis.localStorage={getItem:()=> 'owner'};
const flush=async()=>{await new Promise(resolve=>setImmediate(resolve));await nextTick()};
test('flow management renders scalar counts and requests server pages with retained filters',async t=>{
 const urls=[];
 t.mock.method(globalThis,'fetch',async url=>{
  urls.push(String(url));const query=new URLSearchParams(String(url).split('?')[1]);
  return new Response(JSON.stringify({items:[{id:1,name:'标量流程',enabled:true,createdBy:'owner',nodeCount:7,metricCount:12,canExecute:true}],page:Number(query.get('page')),pageSize:Number(query.get('pageSize')),total:64}));
 });
 const root=container(),app=renderer.createApp(List,{scope:'all',createdBy:'owner'});
 app.component('el-dialog',{props:['modelValue'],setup:(props,{slots})=>()=>h('div',props.modelValue?slots.default?.():[])});
 app.component('el-pagination',{setup:(_,{attrs})=>()=>h('pager',attrs)});
 const instance=app.mount(root);t.after(()=>app.unmount());await flush();
 assert.match(text(root),/标量流程/);assert.match(text(root),/7/);assert.match(text(root),/12/);
 assert.equal(new URLSearchParams(urls[0].split('?')[1]).get('createdBy'),'owner');
 let pager=find(root,node=>node.type==='pager');assert.equal(pager.props.total,64);
 await instance.load('季度','other',true,'all');await flush();
 pager=find(root,node=>node.type==='pager');pager.props.onCurrentChange(3);await flush();
 let query=new URLSearchParams(urls.at(-1).split('?')[1]);
 assert.equal(query.get('page'),'3');assert.equal(query.get('keyword'),'季度');assert.equal(query.get('createdBy'),'other');assert.equal(query.get('enabled'),'true');
 pager=find(root,node=>node.type==='pager');pager.props.onSizeChange(10);await flush();
 query=new URLSearchParams(urls.at(-1).split('?')[1]);assert.equal(query.get('page'),'1');assert.equal(query.get('pageSize'),'10');
 assert.ok(urls.every(url=>url.startsWith('/api/skill-flows/page?')));
});
