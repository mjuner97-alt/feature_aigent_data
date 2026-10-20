import test from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { readFile } from 'node:fs/promises';
import { build } from 'esbuild';
import { parse, compileScript, compileStyle } from '@vue/compiler-sfc';
const require = createRequire(import.meta.url);
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');

test('native handle drag inserts before/after rows, rejects cross-group drops, and disables during send', async () => {
  let css = '';
  const bundle = await build({
    stdin: { resolveDir: process.cwd(), contents: `
      import {createApp, ref, h} from 'vue';
      import List from './src/components/OrderedRecipientList.vue';
      import Dialog from './src/components/MailRecipientDialog.vue';
      window.showOrderDialog = () => {
        const node = document.createElement('div'); document.body.appendChild(node);
        createApp({render:()=>h(Dialog,{open:true,to:['a','d','b','c'],cc:['e','f'],names:{a:'张三',b:'李四',c:'王五',d:'新加入的人员'},onConfirm:value=>window.confirmedOrder=value})}).mount(node);
      };
      const to = ref(['a','b','c','d']), cc = ref(['e','f']), disabled = ref(false);
      window.orderState = { to, cc, disabled };
      createApp({render:()=>h('main',{},[
        h('h2','收件人排序'),
        h(List,{modelValue:to.value,label:'收件人',disabled:disabled.value,'onUpdate:modelValue':v=>to.value=v},{default:({id})=>'人员 '+id}),
        h('h2','抄送'),
        h(List,{modelValue:cc.value,label:'抄送',disabled:disabled.value,'onUpdate:modelValue':v=>cc.value=v},{default:({id})=>'人员 '+id})
      ])}).mount('#app');`, loader: 'ts' },
    bundle: true, write: false, format: 'iife',
    define: { __VUE_OPTIONS_API__: 'true', __VUE_PROD_DEVTOOLS__: 'false', __VUE_PROD_HYDRATION_MISMATCH_DETAILS__: 'false' },
    plugins: [{ name: 'vue-browser-test', setup(build) {
      build.onLoad({filter:/\.vue$/}, async ({path}) => {
        const {descriptor} = parse(await readFile(path,'utf8'));
        css += descriptor.styles.map(s=>compileStyle({source:s.content,filename:path,id:'data-v-order',scoped:false}).code).join('\n');
        return {contents:compileScript(descriptor,{id:'order',inlineTemplate:true}).content,loader:'ts'};
      });
    }}],
  });
  const browser = await chromium.launch({headless:true, executablePath:process.env.CHROMIUM_PATH});
  try {
    const page = await browser.newPage({viewport:{width:700,height:700}});
    await page.setContent('<html><body><div id="app" style="max-width:560px;margin:24px auto;font-family:Arial"></div></body></html>');
    await page.addStyleTag({content:css});
    await page.addScriptTag({content:bundle.outputFiles[0].text});
    const ids = () => page.evaluate(()=>[...window.orderState.to.value]);
    const source = await page.getByRole('button',{name:'拖动收件人 d',exact:true}).boundingBox();
    const target = await page.locator('[data-recipient-id="b"]').boundingBox();
    await page.mouse.move(source.x+source.width/2,source.y+source.height/2);
    await page.mouse.down();
    await page.mouse.move(source.x+source.width/2+10,source.y+source.height/2,{steps:3});
    await page.mouse.move(target.x+target.width/2,target.y+3,{steps:10});
    await page.mouse.move(target.x+target.width/2,target.y+4);
    await page.waitForFunction(()=>document.querySelector('[data-recipient-id="b"]').classList.contains('insert-before'));
    if (process.env.RECIPIENT_SCREENSHOT) await page.screenshot({path:process.env.RECIPIENT_SCREENSHOT});
    await page.mouse.up();
    await page.waitForFunction(()=>window.orderState.to.value.join(',')==='a,d,b,c');
    assert.deepEqual(await ids(),['a','d','b','c']);
    await page.getByRole('button',{name:'拖动收件人 a',exact:true}).dragTo(page.locator('[data-recipient-id="c"]'),{targetPosition:{x:100,y:36}});
    await page.waitForFunction(()=>window.orderState.to.value.join(',')==='d,b,c,a');
    await page.getByRole('button',{name:'拖动收件人 a',exact:true}).dragTo(page.locator('[data-recipient-id="e"]'));
    assert.deepEqual(await ids(),['d','b','c','a']);
    assert.deepEqual(await page.evaluate(()=>[...window.orderState.cc.value]),['e','f']);
    await page.getByRole('button',{name:'上移收件人 a',exact:true}).click();
    assert.deepEqual(await ids(),['d','b','a','c']);
    await page.evaluate(()=>{window.orderState.disabled.value=true;});
    assert.equal(await page.getByRole('button',{name:'拖动收件人 a',exact:true}).getAttribute('draggable'),'false');
    assert.equal(await page.getByRole('button',{name:'上移收件人 a',exact:true}).isDisabled(),true);
    await page.evaluate(()=>window.showOrderDialog());
    await page.waitForSelector('.mail-dialog .recipient-row');
    const overflow = await page.locator('.mail-dialog .recipient-row').evaluateAll(rows=>rows.some(row=>row.scrollWidth>row.clientWidth+1));
    assert.equal(overflow,false,'recipient controls fit a narrow dialog');
    assert.equal(await page.locator('.mail-dialog .recipient-label').evaluateAll(labels=>labels.every(label=>label.getBoundingClientRect().width>=100)),true,'person names remain readable beside actions');
    const rowA = page.locator('.mail-dialog [data-recipient-id="a"]');
    assert.equal(await rowA.getByRole('button',{name:'将 a 移至抄送',exact:true}).isVisible(),true,'move action remains visible');
    assert.equal(await rowA.getByRole('button',{name:'删除收件人 a',exact:true}).isVisible(),true,'remove action remains visible');
    assert.equal(await rowA.locator('details').count(),0,'no More menu');
    await page.setViewportSize({width:1572,height:900});
    assert.ok(await page.locator('.mail-dialog').evaluate(node=>node.clientWidth)>=1280,'desktop dialog is wide enough for both groups');
    assert.ok(await page.locator('.mail-dialog').evaluate(node=>node.getBoundingClientRect().left)>=248,'desktop dialog clears the left navigation');
    assert.equal(await page.locator('.mail-previews').evaluate(node=>getComputedStyle(node).gridTemplateColumns.split(' ').length),2);
    assert.equal(await page.locator('.mail-dialog .recipient-row').evaluateAll(rows=>rows.every(row=>row.scrollWidth<=row.clientWidth+1)),true);
    if(process.env.RECIPIENT_SCREENSHOT) await page.screenshot({path:process.env.RECIPIENT_SCREENSHOT.replace('.png','-dialog.png')});
    await page.setViewportSize({width:700,height:700});
    assert.equal(await page.locator('.mail-previews').evaluate(node=>getComputedStyle(node).gridTemplateColumns.split(' ').length),1,'narrow screens stack TO and CC');
    assert.equal(await page.locator('.mail-dialog').evaluate(node=>node.getBoundingClientRect().right<=window.innerWidth),true);
  } finally { await browser.close(); }
});
