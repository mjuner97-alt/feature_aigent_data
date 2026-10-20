import test from 'node:test';
import assert from 'node:assert/strict';
import { loadModule } from '../../test/loadModule.js';
const people = [{userId:'1',name:'张三'}, {userId:'2',name:'李四'}, {userId:'3',name:'李四'}, {userId:'4',name:'张三丰'}];
const search = async q => { if(q === '故障') throw Error('offline'); return people.filter(p => p.name.includes(q) || p.userId === q); };
const helper = () => loadModule(new URL('./recipientLookup.ts', import.meta.url));
test('multi search splits separators, deduplicates and reports missing and failed terms', async () => {
 const {lookupRecipients} = await helper();
 const r = await lookupRecipients('张三，1; 李四\n不存在、故障 张三', search);
 assert.deepEqual(r.items.map(p=>p.userId), ['1','4','2','3']);
 assert.deepEqual(r.missing,['不存在']); assert.deepEqual(r.failed,['故障']);
});
test('batch resolves exact IDs and unique full names, leaves ambiguous candidates', async () => {
 const {lookupRecipients} = await helper();
 const r = await lookupRecipients('张三 李四 2 张 不存在', search, true);
 assert.deepEqual(r.matched.map(p=>p.userId), ['1','2']);
 assert.deepEqual(r.ambiguous,['李四','张']);
 assert.deepEqual(r.items.map(p=>p.userId), ['2','3','1','4']);
 assert.deepEqual(r.missing,['不存在']);
});
test('truncated result cannot prove a full name is unique', async () => {
 const {lookupRecipients} = await helper();
 const r = await lookupRecipients('张三', async()=>Array.from({length:5},(_,i)=>({userId:String(i),name:i?'张三丰':'张三'})), true);
 assert.deepEqual(r.matched,[]); assert.deepEqual(r.ambiguous,['张三']);
});
