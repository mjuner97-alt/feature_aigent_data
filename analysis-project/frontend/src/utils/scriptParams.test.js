import test from 'node:test';
import assert from 'node:assert/strict';
import { loadModule } from '../../test/loadModule.js';

const { normalizeScriptParams } = await loadModule(new URL('./scriptParams.ts', import.meta.url));

for (const type of ['string', 'date', 'string[]', 'array', 'boolean', 'int']) {
  test(`preserves unsupported legacy markers for rejection through repeated ${type} normalization and JSON save`, () => {
    const schema = [{ name: 'period', type }];
    const params = { period: { $rule: 'current_quarter' }, business: '华东' };
    const opened = normalizeScriptParams(params, schema);
    const saved = JSON.parse(JSON.stringify(normalizeScriptParams(opened, schema)));
    assert.deepEqual(saved, params);
    assert.deepEqual(params.period, { $rule: 'current_quarter' });
  });
}

test('continues normalizing literal arrays and numbers', () => {
  assert.deepEqual(normalizeScriptParams({ regions: '["华东","华南"]', weeks: '4' }, [
    { name: 'regions', type: 'string[]' }, { name: 'weeks', type: 'int' },
  ]), { regions: ['华东', '华南'], weeks: 4 });
});

test('includes displayed schema defaults when saving old nodes with missing parameters', () => {
  assert.deepEqual(normalizeScriptParams({ business: '华东' }, [
    { name: 'period', type: 'string', required: true, default: '2026Q4' },
    { name: 'regions', type: 'array<string>' },
  ]), { business: '华东', period: '2026Q4', regions: [] });
});

test('preserves malformed rule markers for server validation instead of hiding them', () => {
  assert.deepEqual(normalizeScriptParams({ period: { $rule: null } }, [{ name: 'period', type: 'string' }]),
    { period: { $rule: null } });
});

test('preserves period windows and invalid markers on normalize, coerce, infer and JSON copy', async () => {
 const u = await loadModule(new URL('./scriptParams.ts', import.meta.url));
 const marker = {$dynamic:{v:1,kind:'period_window',unit:'month',startOffset:-3,endOffset:-1,format:'{year}-{month:02}',order:'asc',output:'list'}};
 for (const value of [marker,{$dynamic:null}]) {
  assert.deepEqual(u.coerceParamValue(value,'string[]'),value);
  assert.deepEqual(u.normalizeScriptParams({period:value},[{name:'period',type:'string[]'}]),{period:value});
 }
 assert.equal(u.inferParamSchema({period:marker})[0].type,'string[]');
 assert.deepEqual(JSON.parse(JSON.stringify(marker)),marker);
});
test('builds complete period defaults and rejects invalid counts and formats', async () => {
 const u = await loadModule(new URL('./scriptParams.ts', import.meta.url));
 assert.deepEqual(u.defaultPeriodValue('string[]').$dynamic, {v:1,kind:'period_window',unit:'month',startOffset:-3,endOffset:-1,format:'{year}-{month:02}',order:'asc',output:'list'});
 assert.ok(u.validateParamValue({...u.defaultPeriodValue('string[]'),$dynamic:{...u.defaultPeriodValue('string[]').$dynamic,startOffset:0,endOffset:-1}},'string[]'));
 assert.ok(u.validateParamValue({$dynamic:{...u.defaultPeriodValue('string').$dynamic,format:'{evil}'}},'string'));
 assert.equal(u.supportsPeriodParam('int[]'),false);
});

test('rejects unknown configuration fields and allows valid year date formats', async()=>{const u=await loadModule(new URL('./scriptParams.ts',import.meta.url));const value=u.defaultPeriodValue('date');value.$dynamic.unit='year';value.$dynamic.format='{year}-01-01';assert.equal(u.validateParamValue(value,'date'),'');value.$dynamic.count=1;assert.ok(u.validateParamValue(value,'date'));});

test('rejects retired preset markers instead of treating them as valid values',async()=>{
 const u=await loadModule(new URL('./scriptParams.ts',import.meta.url));
 for(const value of [{$rule:'old'},{$rule:null}])assert.match(u.validateParamValue(value,'string'),/旧预设规则已停用/);
});

test('dynamic markers must bind the whole parameter rather than an array item or nested field',async()=>{
 const u=await loadModule(new URL('./scriptParams.ts',import.meta.url));
 const marker=u.defaultPeriodValue('string');
 for(const value of [[marker],{nested:marker},[{$dynamic:null}]])assert.match(u.validateParamValue(value,'string[]'),/直接绑定参数/);
});

test('only explicit string and date schema types offer period windows',async()=>{const u=await loadModule(new URL('./scriptParams.ts',import.meta.url));for(const type of ['object','json','array<object>','custom','datetime','int[]']){assert.equal(u.supportsPeriodParam(type),false,type);assert.ok(u.validateParamValue(u.defaultPeriodValue('string'),type),type);}for(const type of ['string','date','array','string[]','date[]','array<string>','array<date>',' STRING[] '])assert.equal(u.supportsPeriodParam(type),true,type);});
