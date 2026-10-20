import test from 'node:test';
import assert from 'node:assert/strict';
import { createSSRApp, h, ref, nextTick } from 'vue';
import { renderToString } from '@vue/server-renderer';
import { loadModule } from '../../test/loadModule.js';
import { renderer, container, find, text } from '../../test/memoryRenderer.js';
const { default: FlowNodeCard } = await loadModule(new URL('./FlowNodeCard.vue', import.meta.url));
const marker=()=>({$dynamic:{v:1,kind:'period_window',unit:'month',startOffset:-3,endOffset:-1,format:'{year}-{month:02}',order:'asc',output:'list'}});
function mount(params={period:['fixed']},extra={}){
 const node=ref({nodeKey:'n',nodeType:'PYTHON',scriptId:'demo',scriptParams:params}),events=[],dates=[],root=container();
 const app=renderer.createApp({setup:()=>()=>h(FlowNodeCard,{node:node.value,title:'n',schema:[{name:'period',type:'string[]'}],scripts:[],metrics:[],onDebug:d=>dates.push(d),'onScript-params-change':e=>{events.push(e);if(e.value)node.value.scriptParams=e.value;},...extra})});
 app.component('el-select',{setup:()=>()=>h('div')});app.component('el-option',{setup:()=>()=>h('div')});app.mount(root);return {root,node,events,dates,app};
}
const button=(m,label)=>find(m.root,n=>n.type==='button'&&text(n)===label);
async function selectDynamic(m){find(m.root,n=>n.type==='select'&&n.props.value==='LITERAL').props.onChange({target:{value:'DYNAMIC_PERIOD'}});await nextTick();}
test('selecting dynamic time opens a dialog and cancelling retains the literal value',async()=>{
 const m=mount();await selectDynamic(m);assert.ok(find(m.root,n=>n.type==='dialog'));assert.deepEqual(m.node.value.scriptParams.period,['fixed']);assert.equal(m.events.length,0);button(m,'取消').props.onClick();await nextTick();assert.equal(find(m.root,n=>n.type==='dialog'),null);assert.deepEqual(m.node.value.scriptParams.period,['fixed']);await selectDynamic(m);assert.ok(find(m.root,n=>n.type==='dialog'));m.app.unmount();
});
test('confirming new dynamic time saves the marker and shows a compact summary',async()=>{
 const m=mount();await selectDynamic(m);button(m,'确定').props.onClick();await nextTick();assert.equal(m.node.value.scriptParams.period.$dynamic.output,'list');assert.equal(m.node.value.scriptParams.period.$dynamic.format,'{year}年{month}月份版本');assert.ok(button(m,'修改'));assert.equal(find(m.root,n=>n.type==='dialog'),null);assert.equal(find(m.root,n=>n.props['aria-label']==='周期数量'),null);m.app.unmount();
});
test('reopening saved nodes displays summaries without inline controls or baseline dates',async()=>{
 const app=createSSRApp(FlowNodeCard,{node:{nodeKey:'n',nodeType:'PYTHON',scriptId:'demo',scriptParams:{period:marker()}},title:'n',schema:[{name:'period',type:'string[]'}],scripts:[],metrics:[]});app.component('el-select',{setup:()=>()=>h('div')});app.component('el-option',{setup:()=>()=>h('div')});const html=await renderToString(app);assert.match(html,/最近 3/);assert.match(html,/修改/);assert.doesNotMatch(html,/预览基准日期|周期数量|\[object Object\]/);
});
test('editing an existing dynamic parameter remains transactional',async()=>{
 const original=marker(),m=mount({period:original});button(m,'修改').props.onClick();await nextTick();find(m.root,n=>n.props['aria-label']==='周期数量').props.onInput({target:{value:'5'}});await nextTick();assert.deepEqual(m.node.value.scriptParams.period,original);button(m,'取消').props.onClick();await nextTick();assert.deepEqual(m.node.value.scriptParams.period,original);m.app.unmount();
});
test('trial runs let the server choose its execution date',async()=>{
 const m=mount({period:marker()});button(m,'试跑').props.onClick();assert.deepEqual(m.dates,[undefined]);assert.equal(find(m.root,n=>n.props['aria-label']==='预览基准日期'),null);m.app.unmount();
});
test('parameter modes only offer literal values and page-configured dynamic time',async()=>{
 const m=mount({period:[]});const mode=find(m.root,n=>n.type==='select'&&n.props.value==='LITERAL');
 assert.ok(find(mode,n=>n.type==='option'&&n.props.value==='DYNAMIC_PERIOD'));
 assert.equal(find(mode,n=>n.type==='option'&&n.props.value==='PRESET_RULE'),null);m.app.unmount();
});

test('changing another field cannot submit an invalid saved period window',async()=>{
 const bad=marker();bad.$dynamic.startOffset=0;const m=mount({period:bad,region:'east'},{schema:[{name:'period',type:'string[]'},{name:'region',type:'string'}]});find(m.root,n=>n.type==='input'&&n.props.value==='east').props.onInput({target:{value:'west'}});await nextTick();assert.equal(m.events.at(-1).value,null);m.app.unmount();
});
test('changing the selected script discards an open dynamic draft',async()=>{
 const m=mount();await selectDynamic(m);m.node.value.scriptId='other';await nextTick();assert.equal(find(m.root,n=>n.type==='dialog'),null);assert.equal(m.events.length,0);m.app.unmount();
});

test('scalar date parameters choose one version and save its version format',async()=>{
 const m=mount({period:'2026-08-01'},{schema:[{name:'period',type:'date'}]});await selectDynamic(m);
 assert.equal(find(m.root,n=>n.props['aria-label']==='格式').props.value,'{year}年{month}月份版本');
 const range=find(m.root,n=>n.props['aria-label']==='时间范围');assert.equal(find(range,n=>n.type==='option'&&n.props.value==='recent'),null);
 range.props.onChange({target:{value:'current'}});await nextTick();button(m,'确定').props.onClick();await nextTick();
 assert.equal(m.node.value.scriptParams.period.$dynamic.output,'single');assert.equal(m.node.value.scriptParams.period.$dynamic.startOffset,0);assert.equal(m.node.value.scriptParams.period.$dynamic.endOffset,0);assert.equal(m.node.value.scriptParams.period.$dynamic.format,'{year}年{month}月份版本');m.app.unmount();
});

test('building versions selection saves immediately and remains preview only when reopened',async()=>{
 const m=mount();const select=find(m.root,n=>n.type==='select'&&n.props.value==='LITERAL');
 assert.ok(find(select,n=>n.type==='option'&&n.props.value==='BUILDING_VERSIONS'));
 select.props.onChange({target:{value:'BUILDING_VERSIONS'}});await nextTick();
 assert.deepEqual(m.node.value.scriptParams.period,{$dynamic:{v:1,kind:'building_versions'}});
 assert.ok(button(m,'预览参数'));assert.equal(button(m,'修改'),null);assert.equal(button(m,'＋ 添加一项'),null);assert.equal(find(m.root,n=>n.type==='dialog'),null);m.app.unmount();
 const reopened=mount({period:{$dynamic:{v:1,kind:'building_versions'}}});assert.ok(button(reopened,'预览参数'));assert.equal(button(reopened,'修改'),null);reopened.app.unmount();
});
test('building versions choice is absent for date arrays and scalars',()=>{
 for(const type of ['date[]','string','array<object>']) {const m=mount({}, {schema:[{name:'period',type}]});assert.equal(find(m.root,n=>n.type==='option'&&n.props.value==='BUILDING_VERSIONS'),null);m.app.unmount();}
});
test('building versions can switch transactionally to dynamic time',async()=>{
 const fixed={$dynamic:{v:1,kind:'building_versions'}},m=mount({period:fixed});
 const change=()=>find(m.root,n=>n.type==='select'&&n.props.value==='BUILDING_VERSIONS').props.onChange({target:{value:'DYNAMIC_PERIOD'}});
 change();await nextTick();assert.ok(find(m.root,n=>n.type==='dialog'));button(m,'取消').props.onClick();await nextTick();assert.deepEqual(m.node.value.scriptParams.period,fixed);
 change();await nextTick();button(m,'确定').props.onClick();await nextTick();assert.equal(m.node.value.scriptParams.period.$dynamic.kind,'period_window');m.app.unmount();
});
test('building versions preview follows dynamic parameter visibility',()=>{const m=mount({period:{$dynamic:{v:1,kind:'building_versions'}}},{showDynamicParams:false});assert.equal(button(m,'预览参数'),null);assert.equal(button(m,'＋ 添加一项'),null);m.app.unmount();});
