import test from 'node:test';
import assert from 'node:assert/strict';
import * as Vue from 'vue';
import { loadModule } from '../../test/loadModule.js';
import { renderer, container, find, text } from '../../test/memoryRenderer.js';
globalThis.Document ??= class Document {};
globalThis.ShadowRoot ??= class ShadowRoot {};
const flush = async () => { await new Promise(r => setImmediate(r)); await Vue.nextTick(); };
const button = (root, label) => find(root, n => n.type === 'button' && text(n).trim() === label);
const people = [{userId:'1',name:'张三',department:'研发'}, {userId:'2',name:'李四',department:'销售'}, {userId:'3',name:'李四',department:'财务'}];
const searchSkillUsers = async q => { if(q === '故障') throw Error('offline'); return people.filter(p => p.name.includes(q) || p.userId === q); };
for (const type of ['job', 'flow']) test(`${type} notification settings batch matches unique names and displays ambiguous and failed terms`, async t => {
  const {default: Settings} = await loadModule(new URL('./NotificationSettingsDrawer.vue', import.meta.url), {
    vue: {...Vue, Teleport: Vue.Fragment}, 'element-plus': {ElMessage:{success(){}}},
    '../api/skill': {currentUserId:()=> 'owner', batchUserNames:async()=>({}), searchSkillUsers},
    '../api/skillJob': {getJobNotifySettings:async()=>({notifyReceivers:[]})},
    '../api/skillFlow': {getFlowNotifySettings:async()=>({notifyReceivers:[]})},
  });
  const root = container(), app = renderer.createApp(Settings,{open:true,type,id:1});
  app.mount(root); t.after(()=>app.unmount()); await flush();
  const paste = find(root,n=>n.type==='input' && n.props.placeholder?.startsWith('粘贴多个'));
  paste.props['onUpdate:modelValue']('张三 李四 1 不存在 故障'); await Vue.nextTick();
  await button(root,'批量添加').props.onClick(); await flush();
  assert.match(text(root),/已添加 1 人/);
  assert.match(text(root),/张三 \(1\)/);
  assert.match(text(root),/需从候选中选择.*李四/);
  assert.match(text(root),/李四 \(2\) 销售/);
  assert.match(text(root),/李四 \(3\) 财务/);
  assert.match(text(root),/未匹配.*不存在/);
  assert.match(text(root),/查询失败，请重试：故障/);
});
test('independent notification resend search supports multiple terms and explicit feedback', async t => {
  const {default: Drawer} = await loadModule(new URL('./SkillJobNotificationDrawer.vue', import.meta.url), {
    vue:{...Vue,Teleport:Vue.Fragment,Transition:Vue.defineComponent({inheritAttrs:false,setup:(_, {slots})=>()=>slots.default?.()})}, '../api/skill':{searchSkillUsers},
    '../api/skillJob':{listExecutionNotifications:async()=>[]},
  });
  const root=container(),app=renderer.createApp(Drawer,{open:true,execId:1,canResend:true});
  app.mount(root); t.after(()=>app.unmount()); await flush();
  const input=find(root,n=>n.type==='input');
  input.props['onUpdate:modelValue']('张三，2 不存在 故障'); await Vue.nextTick();
  await button(root,'搜索').props.onClick(); await flush();
  assert.match(text(root),/张三 \(1\) 研发/);
  assert.match(text(root),/李四 \(2\) 销售/);
  assert.match(text(root),/未匹配：不存在/);
  assert.match(text(root),/查询失败，请重试：故障/);
});

test('clearing an independent-task search while pending re-enables the search button', async t => {
 let resolve;
 const {default: Form} = await loadModule(new URL('./SkillJobFormDrawer.vue', import.meta.url), {
  vue:{...Vue,Teleport:Vue.Fragment,Transition:Vue.defineComponent({inheritAttrs:false,setup:(_, {slots})=>()=>slots.default?.()})},
  '../api/skill':{searchSkillUsers:()=>new Promise(r=>{resolve=r;}),listSkills:async()=>[],batchUserNames:async()=>({})},
  '../api/skillJob':{}, '../api/skillDependencyMetric':{listMetrics:async()=>[]},
  './ScheduleRulesEditor.vue':Vue.defineComponent({render:()=>null}),
 });
 const root=container(),app=renderer.createApp(Form,{open:true,editId:null});
 for(const name of ['el-select','el-option']) app.component(name,Vue.defineComponent({render(){return Vue.h('div',this.$slots.default?.());}}));
 app.mount(root);t.after(()=>app.unmount());await flush();
 const input=find(root,n=>n.type==='input' && n.props.placeholder?.startsWith('输入多个姓名'));
 input.props['onUpdate:modelValue']('张三');await Vue.nextTick();
 const pending=button(root,'搜索').props.onClick();await Vue.nextTick();
 assert.equal(button(root,'搜索').props.disabled,true);
 input.props['onUpdate:modelValue']('');await Vue.nextTick();
 await input.props.onKeyup({key:'Enter'});await Vue.nextTick();
 resolve([]);await pending;await flush();
 assert.equal(button(root,'搜索').props.disabled,false);
});

for(const type of ['job','flow']) test(type+' reordered recipients are saved and restored on reopen',async t=>{
 let saved=['1','2','3'];const calls=[];
 const get=async()=>({notifyReceivers:[...saved]});const update=async(id,value)=>{calls.push(value);saved=[...value.notifyReceivers];};
 const {default:Settings}=await loadModule(new URL('./NotificationSettingsDrawer.vue',import.meta.url),{
  vue:{...Vue,Teleport:Vue.Fragment,Transition:Vue.defineComponent({inheritAttrs:false,setup:(_, {slots})=>()=>slots.default?.()})},'element-plus':{ElMessage:{success(){}}},
  '../api/skill':{currentUserId:()=> 'owner',batchUserNames:async()=>({}),searchSkillUsers},
  '../api/skillJob':{getJobNotifySettings:get,updateJobNotifySettings:update},
  '../api/skillFlow':{getFlowNotifySettings:get,updateFlowNotifySettings:update},
 });
 const props=Vue.reactive({open:true,type,id:1});const root=container(),app=renderer.createApp({render:()=>Vue.h(Settings,{...props,'onUpdate:open':value=>{props.open=value;}})});
 app.mount(root);t.after(()=>app.unmount());await flush();
 const up=find(root,n=>n.props['aria-label']==='上移收件人 3');assert.ok(up,'settings order button exists');
 up.props.onClick();await flush();await button(root,'保存').props.onClick();await flush();
 assert.deepEqual(calls[0].notifyReceivers,['1','3','2']);
 assert.deepEqual(calls[0].notifyReceiversByTrigger[type==='flow'?'AUTO_METRIC':'DEFAULT'],['1','3','2']);
 props.open=true;await flush();
 const list=find(root,n=>n.type==='ol'&&n.props['aria-label']==='收件人');
 assert.deepEqual(list.children.filter(n=>n.type==='li').map(n=>n.props['data-recipient-id']),['1','3','2']);
});

test('independent resend sends exactly the reordered recipient list',async t=>{
 const calls=[];
 const {default:Drawer}=await loadModule(new URL('./SkillJobNotificationDrawer.vue',import.meta.url),{
  vue:{...Vue,Teleport:Vue.Fragment},'../api/skill':{searchSkillUsers},
  '../api/skillJob':{listExecutionNotifications:async()=>[],resendExecutionNotification:async(...args)=>{calls.push(args);}},
 });
 const root=container(),app=renderer.createApp(Drawer,{open:true,execId:7,canResend:true});app.mount(root);t.after(()=>app.unmount());await flush();
 find(root,n=>n.type==='input').props['onUpdate:modelValue']('张三 2 3');await Vue.nextTick();
 await button(root,'搜索').props.onClick();await flush();
 for(const label of ['张三 (1) 研发','李四 (2) 销售','李四 (3) 财务']){await button(root,label).props.onClick();await flush();}
 find(root,n=>n.props['aria-label']==='上移收件人 3').props.onClick();await flush();
 await button(root,'发送通知').props.onClick();await flush();
 assert.deepEqual(calls,[[7,['1','3','2']]]);
});
