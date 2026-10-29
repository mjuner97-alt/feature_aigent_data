import test from 'node:test';
import assert from 'node:assert/strict';
import { h, ref, nextTick } from 'vue';
import { loadModule } from '../../test/loadModule.js';
import { renderer, container, find, text } from '../../test/memoryRenderer.js';
const {default:Editor}=await loadModule(new URL('./DynamicPeriodEditor.vue',import.meta.url));
const initial=()=>({$dynamic:{v:1,kind:'period_window',unit:'month',startOffset:-3,endOffset:-1,format:'{year}-{month:02}',order:'asc',output:'list'}});
function mount(type='string[]', initialValue=initial()) {
 const value=ref(initialValue), events=[], root=container();
 const app=renderer.createApp({setup:()=>()=>h(Editor,{modelValue:value.value,type,paramName:'period',scriptId:'demo',onChange:v=>{events.push(v);value.value=v;}})});
 app.mount(root); return {root,value,events,app};
}
const button=(m,label)=>find(m.root,n=>n.type==='button'&&text(n)===label);
async function open(m){button(m,'修改').props.onClick();await nextTick();}
test('saved configuration shows only a summary until opened',async()=>{
 const m=mount();assert.match(text(m.root),/最近 3/);assert.equal(find(m.root,n=>n.type==='dialog'),null);
 await open(m);assert.ok(find(m.root,n=>n.type==='dialog'));assert.doesNotMatch(text(m.root),/预览基准日期|基准日期/);m.app.unmount();
});
test('cancel and Escape discard drafts; confirming commits once',async()=>{
 const m=mount();await open(m);find(m.root,n=>n.props['aria-label']==='周期数量').props.onInput({target:{value:'5'}});await nextTick();
 assert.equal(m.value.value.$dynamic.startOffset,-3);assert.equal(m.events.length,0);
 button(m,'取消').props.onClick();await nextTick();await open(m);assert.equal(find(m.root,n=>n.props['aria-label']==='周期数量').props.value,3);
 find(m.root,n=>n.props['aria-label']==='周期数量').props.onInput({target:{value:'5'}});await nextTick();button(m,'确定').props.onClick();await nextTick();assert.equal(m.value.value.$dynamic.startOffset,-5);assert.equal(m.events.length,1);
 await open(m);find(m.root,n=>n.type==='dialog').props.onCancel({preventDefault(){}});await nextTick();assert.equal(find(m.root,n=>n.type==='dialog'),null);m.app.unmount();
});
test('invalid drafts block confirmation without changing saved parameters',async()=>{
 const m=mount();await open(m);find(m.root,n=>n.props['aria-label']==='周期数量').props.onInput({target:{value:'0'}});await nextTick();assert.equal(button(m,'确定').props.disabled,true);assert.match(text(m.root),/周期数量必须/);assert.equal(m.events.length,0);m.app.unmount();
});
test('monthly editor displays versions and converts saved monthly formats',async()=>{
 const m=mount();await open(m);const select=find(m.root,n=>n.props['aria-label']==='格式');assert.match(text(select),/2026年8月份版本/);assert.equal(select.children.filter(n=>n.type==='option').length,1);assert.equal(select.props.value,'{year}年{month}月份版本');
 assert.match(text(find(m.root,n=>n.props['aria-label']==='周期')),/版本/);
 assert.match(text(find(m.root,n=>n.props['aria-label']==='时间范围')),/上个版本当前版本/);
 button(m,'确定').props.onClick();await nextTick();assert.equal(m.value.value.$dynamic.format,'{year}年{month}月份版本');m.app.unmount();
});
test('monthly date fields generate version strings without blocking confirmation',async()=>{
 const v=initial();v.$dynamic.format='{year}-{month:02}-01';const m=mount('date[]',v);await open(m);assert.equal(button(m,'确定').props.disabled,false);button(m,'确定').props.onClick();await nextTick();assert.deepEqual(m.value.value,{$dynamic:{...v.$dynamic,format:'{year}年{month}月份版本'}});m.app.unmount();
});
test('quarterly editor uses the standard quarter label without 第',async()=>{
 const m=mount();await open(m);
 find(m.root,n=>n.props['aria-label']==='周期').props.onChange({target:{value:'quarter'}});await nextTick();
 const select=find(m.root,n=>n.props['aria-label']==='格式');
 assert.equal(select.props.value,'{year}年{quarter}季度');
 assert.match(text(select),/2026年3季度/);
 assert.doesNotMatch(text(select),/2026年第3季度/);
 m.app.unmount();
});
test('future windows retain their actual offsets and formats on confirmation',async()=>{
 const v=initial();Object.assign(v.$dynamic,{startOffset:0,endOffset:2});const m=mount('string[]',v);await open(m);assert.equal(find(m.root,n=>n.props['aria-label']==='结束偏移').props.value,2);button(m,'确定').props.onClick();await nextTick();assert.deepEqual(m.value.value,{$dynamic:{...v.$dynamic,format:'{year}年{month}月份版本'}});m.app.unmount();
});


test('preview shows resolved parameters without saving drafts or sending a baseline date',async t=>{
 globalThis.localStorage={getItem:()=>null};let request;
 t.mock.method(globalThis,'fetch',async(url,options)=>{request=JSON.parse(options.body);return new Response(JSON.stringify({dataDate:'2026-10-14',timeZone:'Asia/Shanghai',maxPeriods:120,resolvedParams:{period:['2026年7月','2026年8月','2026年9月']}}));});
 const m=mount();t.after(()=>m.app.unmount());await open(m);await button(m,'预览参数').props.onClick();await nextTick();
 assert.deepEqual(request,{scriptId:'demo',params:{period:{$dynamic:{...initial().$dynamic,format:'{year}年{month}月份版本'}}}});assert.match(text(m.root),/2026年8月/);assert.equal(m.events.length,0);assert.doesNotMatch(text(m.root),/2026-10-14/);
 find(m.root,n=>n.props['aria-label']==='周期数量').props.onInput({target:{value:'5'}});await nextTick();assert.equal(find(m.root,n=>n.type==='pre'),null);
});
test('preview discards stale responses after editing and displays errors',async t=>{
 globalThis.localStorage={getItem:()=>null};let resolve;
 t.mock.method(globalThis,'fetch',()=>new Promise(r=>{resolve=r;}));const m=mount();t.after(()=>m.app.unmount());await open(m);
 const pending=button(m,'预览参数').props.onClick();find(m.root,n=>n.props['aria-label']==='周期数量').props.onInput({target:{value:'5'}});await nextTick();resolve(new Response(JSON.stringify({maxPeriods:120,resolvedParams:{period:['obsolete']}})));await pending;await nextTick();assert.doesNotMatch(text(m.root),/obsolete/);
 globalThis.fetch=async()=>new Response(JSON.stringify({message:'无法预览'}),{status:400});await button(m,'预览参数').props.onClick();await nextTick();assert.match(text(m.root),/无法预览/);assert.equal(m.events.length,0);
});

