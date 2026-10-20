import test from 'node:test';
import assert from 'node:assert/strict';
import { h, ref, nextTick } from 'vue';
import { loadModule } from '../../test/loadModule.js';
import { renderer, container, find, text } from '../../test/memoryRenderer.js';
const {default:Preview}=await loadModule(new URL('./BuildingVersionsPreview.vue',import.meta.url));
function mount(){const props=ref({modelValue:{$dynamic:{v:1,kind:'building_versions'}},type:'string[]',paramName:'versions',scriptId:'demo'}),root=container();const app=renderer.createApp({setup:()=>()=>h(Preview,props.value)});app.mount(root);return {root,props,app};}
const button=m=>find(m.root,n=>n.type==='button');
test('readonly preview submits fixed marker and displays duplicate ordered versions',async t=>{
 globalThis.localStorage={getItem:()=>null};let request;
 t.mock.method(globalThis,'fetch',async(url,options)=>{request=JSON.parse(options.body);return new Response(JSON.stringify({resolvedParams:{versions:['版本乙','版本乙','版本甲']}}));});
 const m=mount();t.after(()=>m.app.unmount());assert.doesNotMatch(text(m.root),/修改|格式|排序|周期数量/);assert.equal(find(m.root,n=>['input','select'].includes(n.type)),null);
 await button(m).props.onClick();await nextTick();assert.deepEqual(request,{scriptId:'demo',params:{versions:{$dynamic:{v:1,kind:'building_versions'}}}});assert.match(text(m.root),/版本乙/);assert.deepEqual(JSON.parse(text(find(m.root,n=>n.type==='pre'))),{versions:['版本乙','版本乙','版本甲']});
});
test('preview clears completed results and ignores pending responses for changed context',async t=>{
 globalThis.localStorage={getItem:()=>null};let resolve;
 t.mock.method(globalThis,'fetch',()=>new Promise(r=>{resolve=r;}));const m=mount();t.after(()=>m.app.unmount());
 for(const patch of [{scriptId:'other'},{paramName:'other'},{modelValue:{$dynamic:{v:1,kind:'building_versions',extra:1}}},{type:'array'}]) {
  const pending=button(m).props.onClick();m.props.value={...m.props.value,...patch};await nextTick();resolve(new Response(JSON.stringify({resolvedParams:{versions:['obsolete'],other:['obsolete']}})));await pending;await nextTick();assert.doesNotMatch(text(m.root),/obsolete/);
  m.props.value={...m.props.value,modelValue:{$dynamic:{v:1,kind:'building_versions'}}};await nextTick();
 }
 globalThis.fetch=async()=>new Response(JSON.stringify({message:'数据库查询失败'}),{status:400});await button(m).props.onClick();await nextTick();assert.match(text(m.root),/数据库查询失败/);
 m.props.value={...m.props.value,scriptId:'third'};await nextTick();assert.doesNotMatch(text(m.root),/数据库查询失败/);
});
test('unmount invalidates pending preview',async t=>{globalThis.localStorage={getItem:()=>null};let resolve;t.mock.method(globalThis,'fetch',()=>new Promise(r=>{resolve=r;}));const m=mount();const pending=button(m).props.onClick();m.app.unmount();resolve(new Response(JSON.stringify({resolvedParams:{versions:['obsolete']}})));await pending;assert.doesNotMatch(text(m.root),/obsolete/);});
