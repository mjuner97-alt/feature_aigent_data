import test from 'node:test';
import assert from 'node:assert/strict';
import * as Vue from 'vue';
const { h, nextTick } = Vue;
import { loadModule } from '../../../test/loadModule.js';
import { renderer, container, find, text } from '../../../test/memoryRenderer.js';
globalThis.localStorage={getItem:()=> 'owner'};
globalThis.Document ??= class Document {};
globalThis.ShadowRoot ??= class ShadowRoot {};
const flush=async()=>{await new Promise(r=>setImmediate(r)); await nextTick();};
const button=(root,label)=>find(root,n=>n.type==='button'&&text(n)===label);
const empty={items:[],page:1,pageSize:20,total:42};
async function mountPage(t,tab,handler){
 const calls=[];
 t.mock.method(globalThis,'setInterval',()=>0);
 t.mock.method(globalThis,'fetch',async(url,opts)=>{calls.push(String(url));return new Response(JSON.stringify(handler?.(String(url),opts)??empty));});
 const stub={setup:()=>()=>h('div')};
 const {default:Page}=await loadModule(new URL('./SkillJobListPage.vue',import.meta.url),{
 'vue': {...Vue, vModelSelect: {}, createStaticVNode: () => h('span')},
 'vue-router':{useRoute:()=>({query:{tab}}),useRouter:()=>({push(){}})},
 'element-plus':{ElMessage:{error(){},success(){}},ElMessageBox:{alert(){}}},
 '../../components/SkillFlowList.vue':stub,'../../components/SkillFlowExecutionList.vue':stub,
 '../../components/SkillJobExecutionDrawer.vue':stub,'../../components/SkillJobNotificationDrawer.vue':stub,
 });
 const root=container(),app=renderer.createApp(Page);
 for(const name of ['el-icon','el-pagination'])app.component(name,{props:['total','currentPage','pageSize'],emits:['update:currentPage','size-change'],setup:(props,ctx)=>()=>h(name,{...ctx.attrs,total:props.total,onNext:()=>ctx.emit('update:currentPage',2)},ctx.slots.default?.())});
 t.after(()=>app.unmount());app.mount(root);await flush();return {root,calls};
}
test('flow initial tab issues no hidden job or execution-center fetch; switching tabs loads only selected list',async t=>{
 const {root,calls}=await mountPage(t,'flows'); assert.deepEqual(calls,[]);
 await button(root,'独立任务').props.onClick();await flush();assert.equal(calls.length,1);assert.match(calls[0],/^\/api\/skill-jobs\/page\?/);
 await button(root,'执行记录').props.onClick();await flush();assert.equal(calls.length,2);assert.match(calls[1],/^\/api\/skill-jobs\/executions\/page\?/);
});
test('management keeps enabled-only partial updates without fetching the hidden execution center',async t=>{
 const row={id:8,name:'weekly',skillId:2,enabled:true,createdBy:'owner',createdAt:'2026-10-17'};
 const updates=[];const {root,calls}=await mountPage(t,'manage',(url,opts)=>{
  if(opts?.method==='PUT'){updates.push(JSON.parse(opts.body));return row;}
  return {...empty,items:[row]};
 });
 const page=find(root,n=>n.type==='el-pagination');assert.ok(page);
 assert.equal(page.props.total,42);
 page.props.onNext();await flush();
 assert.equal(new URL(calls.at(-1),'http://localhost').searchParams.get('page'),'2');
 await button(root,'禁用').props.onClick();await flush();assert.deepEqual(updates,[{enabled:false}]);
 assert.equal(calls.filter(u=>u.includes('/executions')).length,0);
});
