import test from 'node:test';
import assert from 'node:assert/strict';
import * as Vue from 'vue';
import {loadModule} from '../../test/loadModule.js';
import {renderer,container,find} from '../../test/memoryRenderer.js';
const control=(root,label)=>find(root,n=>n.props['aria-label']===label);
const row=(root,id)=>find(root,n=>n.props['data-recipient-id']===id);
const event=(target,y=1)=>({currentTarget:target,clientY:y,preventDefault(){},dataTransfer:{setData(){},effectAllowed:'',dropEffect:''}});
async function setup(t,disabled=false) {
 const {default:List}=await loadModule(new URL('./OrderedRecipientList.vue',import.meta.url));
 const state=Vue.reactive({ids:['a','b','c','d'],disabled}),updates=[];
 const root=container(), app=renderer.createApp({render:()=>Vue.h(List,{modelValue:state.ids,label:'收件人',disabled:state.disabled,'onUpdate:modelValue':ids=>{updates.push(ids);state.ids=ids;}})});
 app.mount(root);t.after(()=>app.unmount());await Vue.nextTick();return {root,state,updates};
}
test('drag last recipient into middle, render insertion indicator, preserve original array',async t=>{
 const {root,state}=await setup(t),original=state.ids;
 control(root,'拖动收件人 d').props.onDragstart(event());
 const target=row(root,'b');target.getBoundingClientRect=()=>({top:0,height:20});
 target.props.onDragover(event(target,1));await Vue.nextTick();
 assert.ok(row(root,'b').props.class.includes('insert-before'));
 target.props.onDrop(event(target,1));await Vue.nextTick();
 assert.deepEqual([...state.ids],['a','d','b','c']);assert.deepEqual([...original],['a','b','c','d']);
});
test('downward drop after a row and up/down buttons retain boundaries',async t=>{
 const {root,state}=await setup(t);
 assert.equal(control(root,'上移收件人 a').props.disabled,true);assert.equal(control(root,'下移收件人 d').props.disabled,true);
 control(root,'拖动收件人 a').props.onDragstart(event());const target=row(root,'c');target.getBoundingClientRect=()=>({top:0,height:20});
 target.props.onDragover(event(target,19));target.props.onDrop(event(target,19));await Vue.nextTick();
 assert.deepEqual([...state.ids],['b','c','a','d']);
 control(root,'上移收件人 a').props.onClick();await Vue.nextTick();assert.deepEqual([...state.ids],['b','a','c','d']);
 control(root,'下移收件人 a').props.onClick();await Vue.nextTick();assert.deepEqual([...state.ids],['b','c','a','d']);
});
test('foreign, cancelled and disabled drags cannot reorder',async t=>{
 const {root,state,updates}=await setup(t);const target=row(root,'b');target.getBoundingClientRect=()=>({top:0,height:20});
 target.props.onDrop(event(target));assert.equal(updates.length,0);
 const handle=control(root,'拖动收件人 d');handle.props.onDragstart(event());handle.props.onDragend();target.props.onDrop(event(target));assert.equal(updates.length,0);
 state.disabled=true;await Vue.nextTick();assert.equal(control(root,'拖动收件人 d').props.draggable,false);
 handle.props.onDragstart(event());target.props.onDrop(event(target));control(root,'上移收件人 d').props.onClick();assert.equal(updates.length,0);
});

test('changing the list or disabling during a drag invalidates the pending drop',async t=>{
 const {root,state,updates}=await setup(t);
 control(root,'拖动收件人 d').props.onDragstart(event());
 state.ids=['a','b','c'];await Vue.nextTick();
 const target=row(root,'b');target.getBoundingClientRect=()=>({top:0,height:20});
 target.props.onDrop(event(target));assert.equal(updates.length,0);
 control(root,'拖动收件人 c').props.onDragstart(event());state.disabled=true;await Vue.nextTick();
 state.disabled=false;await Vue.nextTick();target.props.onDrop(event(target));assert.equal(updates.length,0);
});
