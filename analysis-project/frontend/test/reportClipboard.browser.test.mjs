import test from 'node:test';
import assert from 'node:assert/strict';
import { build } from 'esbuild';
import { createRequire } from 'node:module';
import { readFile } from 'node:fs/promises';
const require = createRequire(import.meta.url);
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');

test('copies nested report content and computed styles as rich HTML without UI or scripts', async () => {
  const bundle = await build({ entryPoints: ['src/utils/reportClipboard.ts'], bundle: true, write: false, format: 'iife', globalName: 'ReportClipboard' });
  const browser = await chromium.launch({ headless: true, executablePath: process.env.CHROMIUM_PATH });
  try {
    const page = await browser.newPage();
    await page.route('http://localhost/**', route => route.fulfill({ contentType: 'text/html', body: '<html><body></body></html>' }));
    await page.goto('http://localhost/');
    await page.addScriptTag({ content: bundle.outputFiles[0].text });
    const result = await page.evaluate(async () => {
      const nested = `<html><head><style>td{border:2px solid #123456;background:#ffeedd;padding:9px}strong{color:#ff0000;font-size:20px}</style></head><body><div id="report"><strong>节点二重点</strong><table><tr><td rowspan="2">完整表格</td><td>甲</td></tr><tr><td>乙</td></tr></table><svg width="100" height="50"><defs><linearGradient id="gradient"><stop offset="0" stop-color="red"/><stop offset="1" stop-color="blue"/></linearGradient><path id="shape" d="M0 0h100v50H0z"/></defs><use href="#shape" fill="url(#gradient)"/></svg></div></body></html>`;
      const html = `<html><head><style>h1{color:#123456;text-align:center}p{font-size:18px}</style></head><body><aside class="report-toc">目录不要复制</aside><div class="report"><h1>总标题</h1><p>第一章正文</p><button>全屏不要复制</button><iframe srcdoc="${nested.replaceAll('&','&amp;').replaceAll('"','&quot;')}"></iframe><p>结尾正文</p></div><script>parent.reportScriptRan=true</script></body></html>`;
      const payload = await ReportClipboard.prepareReportClipboard(html);
      let written;
      Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { write: async items => { written = items[0]; } } });
      await ReportClipboard.writeReportClipboard(Promise.resolve(payload));
      const parsed = new DOMParser().parseFromString(payload.html, 'text/html');
      const use = parsed.querySelector('use');
      const shape = parsed.querySelector('path');
      const gradient = parsed.querySelector('linearGradient');
      const svgIntact = use.getAttribute('href') === '#' + shape.id && use.style.fill.includes(gradient.id) && shape.id !== 'shape' && gradient.id !== 'gradient';
      return { ...payload, svgIntact, clipboardHtml: await (await written.getType('text/html')).text(), clipboardText: await (await written.getType('text/plain')).text(), ran: !!window.reportScriptRan, frames: document.querySelectorAll('iframe').length };
    });
    for (const value of ['总标题','第一章正文','节点二重点','完整表格','甲','乙','结尾正文']) assert.ok(result.text.includes(value), value);
    assert.ok(result.html.indexOf('第一章正文') < result.html.indexOf('节点二重点'));
    assert.ok(result.html.indexOf('节点二重点') < result.html.indexOf('结尾正文'));
    assert.match(result.html, /rowspan="2"/);
    assert.match(result.html, /border-width: 2px/);
    assert.match(result.html, /border-style: solid/);
    assert.match(result.html, /border-color: rgb\(18, 52, 86\)/);
    assert.match(result.html, /background-color: rgb\(255, 238, 221\)/);
    assert.match(result.html, /font-size: 20px/);
    assert.match(result.html, /text-align: center/);
    assert.doesNotMatch(result.html, /<iframe|<script|<button|目录不要复制|全屏不要复制/);
    assert.equal(result.clipboardHtml, result.html);
    assert.equal(result.clipboardText, result.text);
    assert.equal(result.ran, false);
    assert.equal(result.svgIntact, true);
    assert.equal(result.frames, 0);
  } finally { await browser.close(); }
});

test('copies actual ECharts from a nested long-task report and rejects missing embedded content', async () => {
  const bundle = await build({ entryPoints: ['src/utils/reportClipboard.ts'], bundle: true, write: false, format: 'iife', globalName: 'ReportClipboard' });
  const echarts = await readFile(require.resolve('echarts/dist/echarts.min.js'), 'utf8');
  const browser = await chromium.launch({ headless: true, executablePath: process.env.CHROMIUM_PATH });
  try {
    const page = await browser.newPage();
    await page.route('http://localhost/**', route => route.fulfill({ contentType: 'text/html', body: '<html><body></body></html>' }));
    await page.goto('http://localhost/');
    await page.addScriptTag({ content: bundle.outputFiles[0].text });
    const result = await page.evaluate(async library => {
      const node = `<html><body><div id="report"><p>图表前正文</p><div data-report-chart-shell><div data-report-chart id="chart" style="width:500px;height:300px"></div></div><p>图表后正文</p></div><script>${library}</script><script>echarts.init(document.querySelector('#chart')).setOption({animation:false,xAxis:{data:['一','二']},yAxis:{},series:[{type:'bar',data:[2,5]}]})</script></body></html>`;
      const wrapper = `<html><body><div class="report"><h1>长任务标题</h1><iframe srcdoc="${node.replaceAll('&','&amp;').replaceAll('"','&quot;')}"></iframe><p>长任务结尾</p></div><script>var unused='echarts.init(';var frames=document.querySelectorAll('iframe');</script></body></html>`;
      const payload = await ReportClipboard.prepareReportClipboard(wrapper);
      const parsed = new DOMParser().parseFromString(payload.html, 'text/html');
      let externalError, emptyError, chartError;
      try { await ReportClipboard.prepareReportClipboard('<div class="report"><iframe src="https://example.invalid/report"></iframe></div>'); } catch (e) { externalError=e.message; }
      try { await ReportClipboard.prepareReportClipboard('<div class="report"></div>'); } catch (e) { emptyError=e.message; }
      try { await ReportClipboard.prepareReportClipboard('<div class="report"><div data-report-chart></div></div>'); } catch (e) { chartError=e.message; }
      return { text: payload.text, images: parsed.querySelectorAll('img').length, image: parsed.querySelector('img')?.src, externalError, emptyError, chartError, frames: document.querySelectorAll('iframe').length };
    }, echarts);
    for (const text of ['长任务标题','图表前正文','图表后正文','长任务结尾']) assert.ok(result.text.includes(text));
    assert.equal(result.images, 1);
    assert.match(result.image, /^data:image\/png;base64,/);
    assert.match(result.externalError, /外部嵌入/);
    assert.match(result.emptyError, /正文为空/);
    assert.match(result.chartError, /图表渲染失败/);
    assert.equal(result.frames, 0);
  } finally { await browser.close(); }
});

test('writes real clipboard, pastes formatted content and captures dynamic canvas in an isolated sandbox', async () => {
  const bundle = await build({ entryPoints: ['src/utils/reportClipboard.ts'], bundle: true, write: false, format: 'iife', globalName: 'ReportClipboard' });
  const browser = await chromium.launch({ headless: true, executablePath: process.env.CHROMIUM_PATH });
  try {
    const context = await browser.newContext({ permissions: ['clipboard-read', 'clipboard-write'] });
    const page = await context.newPage();
    await page.route('http://localhost/**', route => route.fulfill({ contentType: 'text/html', body: '<button id="copy">复制内容</button><div id="paste" contenteditable="true"></div>' }));
    await page.goto('http://localhost/');
    await page.addScriptTag({ content: bundle.outputFiles[0].text });
    await page.evaluate(() => {
      window.copyDone = false;
      document.querySelector('#copy').onclick = () => {
        const html = `<html><head><style>h2{color:#ff0000;text-align:center}td{border:2px solid #123456;background:#ffeedd}</style></head><body><div class="report"><h2>可粘贴标题</h2><table><tr><td>完整单元格</td></tr></table><div id="chart" style="width:200px;height:100px"><canvas width="200" height="100"></canvas></div></div><script>try{parent.hacked=true}catch(e){};var c=document.querySelector('canvas');var ctx=c.getContext('2d');ctx.fillStyle='red';ctx.fillRect(0,0,200,100);</script></body></html>`;
        ReportClipboard.copyReportContent(() => Promise.resolve(html)).then(() => { window.copyDone = true; }, error => { window.copyError = error.message; });
      };
    });
    await page.click('#copy');
    await page.waitForFunction(() => window.copyDone || window.copyError);
    assert.equal(await page.evaluate(() => window.copyError), undefined);
    await page.click('#paste');
    await page.keyboard.press('Control+V');
    await page.waitForFunction(() => document.querySelector('#paste td'));
    const pasted = await page.evaluate(() => {
      const root = document.querySelector('#paste');
      const td = getComputedStyle(root.querySelector('td'));
      const heading = getComputedStyle(root.querySelector('h2'));
      return { text: root.innerText, border: td.borderTop, background: td.backgroundColor, color: heading.color, align: heading.textAlign, image: root.querySelector('img')?.src, hacked: !!window.hacked, frames: document.querySelectorAll('iframe').length };
    });
    assert.match(pasted.text, /可粘贴标题/);
    assert.match(pasted.text, /完整单元格/);
    assert.equal(pasted.border, '2px solid rgb(18, 52, 86)');
    assert.equal(pasted.background, 'rgb(255, 238, 221)');
    assert.equal(pasted.color, 'rgb(255, 0, 0)');
    assert.equal(pasted.align, 'center');
    assert.match(pasted.image, /^data:image\/png;base64,/);
    assert.equal(pasted.hacked, false);
    assert.equal(pasted.frames, 0);
  } finally { await browser.close(); }
});

test('intranet HTTP fallback copies HTML and preserves rich formatting on paste', async () => {
  const bundle = await build({ entryPoints: ['src/utils/reportClipboard.ts'], bundle: true, write: false, format: 'iife', globalName: 'ReportClipboard' });
  const browser = await chromium.launch({ headless: true, executablePath: process.env.CHROMIUM_PATH });
  try {
    const page = await browser.newPage();
    await page.route('http://report.test/**', route => route.fulfill({ contentType: 'text/html', body: '<button id="copy">复制内容</button><div id="paste" contenteditable="true"></div>' }));
    await page.goto('http://report.test/');
    await page.addScriptTag({ content: bundle.outputFiles[0].text });
    assert.equal(await page.evaluate(() => !!navigator.clipboard), false);
    await page.evaluate(() => {
      document.querySelector('#copy').onclick = () => ReportClipboard.copyReportContent(async () => {
        await new Promise(resolve => setTimeout(resolve, 50));
        return '<div class="report"><strong style="color:#ff0000">HTTP保留格式</strong><table><tr><td style="border:1px solid blue">表格正文</td></tr></table><canvas width="100" height="50"></canvas></div><script>var c=document.querySelector("canvas");c.getContext("2d").fillRect(0,0,100,50)</script>';
      }).then(() => { window.copyDone = true; }, e => { window.copyError = e.message; });
    });
    await page.click('#copy');
    await page.waitForFunction(() => window.copyDone || window.copyError);
    assert.equal(await page.evaluate(() => window.copyError), undefined);
    await page.click('#paste'); await page.keyboard.press('Control+V');
    await page.waitForFunction(() => document.querySelector('#paste td'));
    assert.equal(await page.locator('#paste').evaluate(root => {
      const element = Array.from(root.querySelectorAll('*')).find(element => element.children.length === 0 && element.textContent.includes('HTTP保留格式'));
      return element && getComputedStyle(element).color;
    }), 'rgb(255, 0, 0)');
    assert.equal(await page.locator('#paste td').textContent(), '表格正文');
    assert.match(await page.locator('#paste img').getAttribute('src'), /^data:image\/png;base64,/);
  } finally { await browser.close(); }
});
