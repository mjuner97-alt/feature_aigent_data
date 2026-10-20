import test from 'node:test';
import assert from 'node:assert/strict';
import { build } from 'esbuild';
import { createRequire } from 'node:module';
import { mkdtemp, writeFile, unlink, rmdir, readFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
const require = createRequire(import.meta.url);
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');

for (const minify of [false, true]) test(`native Ctrl+C preserves selected text/table styles inside nested report frames (minify=${minify})`, async () => {
  const bundle = await build({ entryPoints: ['src/utils/reportClipboard.ts'], bundle: true, write: false, format: 'iife', minify, globalName: 'ReportClipboard' });
  const browser = await chromium.launch({ headless: true, executablePath: process.env.CHROMIUM_PATH });
  try {
    const page = await browser.newPage();
    await page.route('http://report.test/**', route => route.fulfill({ contentType: 'text/html;charset=utf-8', body: '<iframe id="report"></iframe><div id="paste" contenteditable="true"></div><input id="input" value="普通输入框">' }));
    await page.goto('http://report.test/');
    await page.addScriptTag({ content: bundle.outputFiles[0].text });
    await page.evaluate(() => {
      const child = `<html><head><style>#words{text-align:center;background:rgb(240,230,140)}strong{color:rgb(230,0,0);font-size:24px}td{border:2px solid rgb(18,52,86);background:rgb(255,238,221);padding:9px}</style></head><body><p>不要复制的前文</p><p id="words"><strong>前缀选中重点后缀</strong></p><table><tr><td id="cell">选中单元格</td><td>未选中单元格</td></tr><tr><td>未选中行</td></tr></table><p>不要复制的后文</p></body></html>`;
      const html = `<html><body><h1>父页面不要复制</h1><iframe id="child" srcdoc="${child.replaceAll('&','&amp;').replaceAll('"','&quot;')}"></iframe></body></html>`;
      document.querySelector('#report').srcdoc = ReportClipboard.withReportSelectionCopy(html);
    });
    const frame = page.frameLocator('#report').frameLocator('#child');
    await frame.locator('#words').waitFor();
    await frame.locator('#words').click();
    await frame.locator('#words').evaluate(element => {
      const range = element.ownerDocument.createRange();
      range.setStart(element.querySelector('strong').firstChild, 2);
      range.setEnd(element.querySelector('strong').firstChild, 6);
      const selection = element.ownerDocument.getSelection(); selection.removeAllRanges(); selection.addRange(range);
    });
    await page.keyboard.press('Control+C');
    assert.equal(await frame.locator('#words').evaluate(element => element.ownerDocument.getSelection().toString()), '选中重点');
    await page.locator('#paste').click(); await page.keyboard.press('Control+V');
    await page.waitForFunction(() => document.querySelector('#paste').textContent.includes('选中重点'));
    const copied = await page.locator('#paste').evaluate(root => {
      const node = Array.from(root.querySelectorAll('*')).find(element => element.children.length === 0 && element.textContent === '选中重点');
      return { text: root.innerText, color: node && getComputedStyle(node).color, size: node && getComputedStyle(node).fontSize, align: node && getComputedStyle(node).textAlign, backgrounds: Array.from(root.querySelectorAll('*')).map(element => getComputedStyle(element).backgroundColor) };
    });
    assert.equal(copied.text, '选中重点'); assert.equal(copied.color, 'rgb(230, 0, 0)'); assert.equal(copied.size, '24px');
    assert.equal(copied.align, 'center'); assert.ok(copied.backgrounds.includes('rgb(240, 230, 140)'));
    await page.locator('#paste').fill('');
    await frame.locator('#cell').click();
    await frame.locator('#cell').evaluate(element => {
      const range = element.ownerDocument.createRange(); range.selectNodeContents(element);
      const selection = element.ownerDocument.getSelection(); selection.removeAllRanges(); selection.addRange(range);
    });
    await page.keyboard.press('Control+C'); await page.locator('#paste').click(); await page.keyboard.press('Control+V');
    await page.locator('#paste td').waitFor();
    const table = await page.locator('#paste').evaluate(root => ({ text: root.innerText, cells: root.querySelectorAll('td').length, border: getComputedStyle(root.querySelector('td')).borderTop, background: getComputedStyle(root.querySelector('td')).backgroundColor }));
    assert.equal(table.cells, 1); assert.match(table.text, /选中单元格/); assert.doesNotMatch(table.text, /未选中|不要复制/);
    assert.equal(table.border, '2px solid rgb(18, 52, 86)'); assert.equal(table.background, 'rgb(255, 238, 221)');
    await page.evaluate(() => ReportClipboard.installReportSelectionCopy(document));
    await page.locator('#input').click(); await page.keyboard.press('Control+A'); await page.keyboard.press('Control+C');
    await page.locator('#paste').fill(''); await page.locator('#paste').click(); await page.keyboard.press('Control+V');
    await page.waitForFunction(() => document.querySelector('#paste').textContent.includes('普通输入框'));
    assert.equal(await page.locator('#paste').innerText(), '普通输入框');
  } finally { await browser.close(); }
});

test('copy-menu event preserves spanning partial selection and newly inserted/reloaded iframes', async () => {
  const bundle = await build({ entryPoints: ['src/utils/reportSelectionCopy.ts'], bundle: true, write: false, format: 'iife', minify: true, globalName: 'ReportSelection' });
  const browser = await chromium.launch({ headless: true, executablePath: process.env.CHROMIUM_PATH });
  try {
    const page = await browser.newPage();
    await page.route('http://report.test/**', route => route.fulfill({ contentType: 'text/html;charset=utf-8', body: '<div id="report"></div>' }));
    await page.goto('http://report.test/'); await page.addScriptTag({ content: bundle.outputFiles[0].text });
    await page.evaluate(() => {
      ReportSelection.installReportSelectionCopy(document);
      const frame = document.createElement('iframe'); frame.id = 'dynamic';
      frame.srcdoc = '<html><head><style>p{color:#123456;font-size:22px}</style></head><body><p>不选中的前文</p><p id="first">甲乙丙丁</p><p id="last">戊己庚辛</p><p>不选中的后文</p></body></html>';
      document.querySelector('#report').appendChild(frame);
    });
    const frame = page.frameLocator('#dynamic'); await frame.locator('#last').waitFor();
    const copied = await frame.locator('#first').evaluate(element => {
      const doc = element.ownerDocument;
      const range = doc.createRange(); range.setStart(element.firstChild, 2); range.setEnd(doc.querySelector('#last').firstChild, 2);
      const selection = doc.getSelection(); selection.removeAllRanges(); selection.addRange(range);
      const clipboardData = new DataTransfer(); const event = new ClipboardEvent('copy', { bubbles: true, cancelable: true, clipboardData });
      doc.dispatchEvent(event);
      const html = clipboardData.getData('text/html'); const parsed = new DOMParser().parseFromString(html, 'text/html');
      return { prevented: event.defaultPrevented, text: parsed.body.textContent, plain: clipboardData.getData('text/plain'), html };
    });
    assert.equal(copied.prevented, true); assert.equal(copied.text, '丙丁戊己'); assert.doesNotMatch(copied.plain, /甲乙|庚辛|不选中/); assert.match(copied.html, /font-size: 22px/);
    await page.locator('#dynamic').evaluate(frame => { frame.srcdoc = '<p id="reloaded" style="color:red">重新加载后选中</p>'; });
    await frame.locator('#reloaded').waitFor();
    const reloaded = await frame.locator('#reloaded').evaluate(element => {
      const doc = element.ownerDocument; const range = doc.createRange(); range.selectNodeContents(element);
      const selection = doc.getSelection(); selection.removeAllRanges(); selection.addRange(range);
      const clipboardData = new DataTransfer(); const event = new ClipboardEvent('copy', { bubbles: true, cancelable: true, clipboardData }); doc.dispatchEvent(event);
      return { prevented: event.defaultPrevented, html: clipboardData.getData('text/html') };
    });
    assert.equal(reloaded.prevented, true); assert.match(reloaded.html, /rgb\(255, 0, 0\)/);
  } finally { await browser.close(); }
});

test('downloaded self-contained HTML supports selected-copy offline', async () => {
  const bundle = await build({ entryPoints: ['src/utils/reportSelectionCopy.ts'], bundle: true, write: false, format: 'iife', minify: true, globalName: 'ReportSelection' });
  const folder = await mkdtemp(path.join(tmpdir(), 'report-selection-offline-'));
  const file = path.join(folder, 'report.html');
  const browser = await chromium.launch({ headless: true, executablePath: process.env.CHROMIUM_PATH });
  try {
    const page = await browser.newPage(); await page.addScriptTag({ content: bundle.outputFiles[0].text });
    const html = await page.evaluate(() => {
      const child = '<p id="selected" style="color:rgb(230,0,0);font-size:24px">离线选中内容</p><p>不要复制</p>';
      return ReportSelection.withReportSelectionCopy(`<html><meta charset="utf-8"><body><iframe id="child" srcdoc="${child.replaceAll('&','&amp;').replaceAll('"','&quot;')}"></iframe><div id="paste" contenteditable="true"></div></body></html>`);
    });
    await writeFile(file, html, 'utf8'); await page.goto(pathToFileURL(file).href);
    const selected = page.frameLocator('#child').locator('#selected'); await selected.click();
    await selected.evaluate(element => {
      const doc = element.ownerDocument; const range = doc.createRange(); range.selectNodeContents(element);
      const selection = doc.getSelection(); selection.removeAllRanges(); selection.addRange(range);
    });
    await page.keyboard.press('Control+C'); await page.locator('#paste').click(); await page.keyboard.press('Control+V');
    await page.waitForFunction(() => document.querySelector('#paste').textContent.includes('离线选中内容'));
    const pasted = await page.locator('#paste').evaluate(root => {
      const element = Array.from(root.querySelectorAll('*')).find(element => !element.children.length && element.textContent === '离线选中内容');
      return { text: root.innerText, color: element && getComputedStyle(element).color, size: element && getComputedStyle(element).fontSize };
    });
    assert.equal(pasted.text, '离线选中内容'); assert.equal(pasted.color, 'rgb(230, 0, 0)'); assert.equal(pasted.size, '24px');
  } finally { await browser.close(); await unlink(file).catch(() => {}); await rmdir(folder); }
});

test('clicking an ECharts chart then Ctrl+C copies the whole chart as an image without text selection', async () => {
  const bundle = await build({ entryPoints: ['src/utils/reportSelectionCopy.ts'], bundle: true, write: false, format: 'iife', minify: true, globalName: 'ReportSelection' });
  const library = await readFile(require.resolve('echarts/dist/echarts.min.js'), 'utf8');
  const browser = await chromium.launch({ headless: true, executablePath: process.env.CHROMIUM_PATH });
  try {
    const page = await browser.newPage();
    await page.route('http://report.test/**', route => route.fulfill({ contentType: 'text/html;charset=utf-8', body: '<iframe id="report" style="width:700px;height:450px"></iframe><div id="paste" contenteditable="true"></div>' }));
    await page.goto('http://report.test/'); await page.addScriptTag({ content: bundle.outputFiles[0].text });
    await page.evaluate(library => {
      const html = `<html><body><p id="before">图表之外不要复制</p><div data-report-chart id="chart" style="width:500px;height:300px"></div><p id="after">图表后正文</p><script>${library}</script><script>echarts.init(document.querySelector('#chart')).setOption({animation:false,xAxis:{data:['一','二']},yAxis:{},series:[{type:'bar',data:[2,5]}]})</script></body></html>`;
      document.querySelector('#report').srcdoc = ReportSelection.withReportSelectionCopy(html);
    }, library);
    const chart = page.frameLocator('#report').locator('#chart'); await chart.locator('canvas').waitFor();
    await chart.click({ position: { x: 100, y: 100 } });
    assert.equal(await chart.evaluate(element => element.ownerDocument.getSelection().isCollapsed), true);
    await page.keyboard.press('Control+C'); await page.locator('#paste').click(); await page.keyboard.press('Control+V');
    await page.locator('#paste img').waitFor({ timeout: 5000 });
    const result = await page.locator('#paste').evaluate(async element => {
      const image = element.querySelector('img'); await image.decode();
      return { text: element.textContent, src: image.src, width: image.naturalWidth, height: image.naturalHeight };
    });
    assert.match(result.src, /^data:image\/png;base64,/); assert.equal(result.width, 1000); assert.equal(result.height, 600);
    assert.doesNotMatch(result.text, /图表之外/);
    const mixed = await chart.evaluate(element => {
      const doc = element.ownerDocument; const range = doc.createRange();
      range.setStartBefore(doc.querySelector('#before')); range.setEndAfter(doc.querySelector('#after'));
      const selection = doc.getSelection(); selection.removeAllRanges(); selection.addRange(range);
      const clipboardData = new DataTransfer(); const event = new ClipboardEvent('copy', { bubbles: true, cancelable: true, clipboardData }); doc.dispatchEvent(event);
      const parsed = new DOMParser().parseFromString(clipboardData.getData('text/html'), 'text/html');
      return { prevented: event.defaultPrevented, count: parsed.querySelectorAll('img').length, text: parsed.body.textContent, src: parsed.querySelector('img')?.src };
    });
    assert.equal(mixed.prevented, true); assert.equal(mixed.count, 1); assert.match(mixed.text, /图表后正文/); assert.match(mixed.src, /^data:image\/png;base64,/);
    await chart.evaluate(element => element.ownerDocument.getSelection().removeAllRanges());
    await page.frameLocator('#report').locator('#before').click();
    const cleared = await chart.evaluate(element => {
      const clipboardData = new DataTransfer(); const event = new ClipboardEvent('copy', { cancelable: true, clipboardData }); element.ownerDocument.dispatchEvent(event); return event.defaultPrevented;
    });
    assert.equal(cleared, false, 'clicking ordinary text must clear the active chart');
  } finally { await browser.close(); }
});
