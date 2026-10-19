import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import { loadModule } from '../../test/loadModule.js';

const api = await loadModule(new URL('./skillFlow.ts', import.meta.url));
globalThis.localStorage = { getItem: () => 'test-user' };

for (const existingToc of [true, false]) {
  for (const uppercase of [false, true]) {
    test(`report Blob preserves ECharts and nested reports (toc=${existingToc}, uppercase=${uppercase})`, async t => {
      const library = fs.readFileSync(new URL('../../../src/main/resources/report-assets/echarts.min.js', import.meta.url), 'utf8');
      const headEnd = uppercase ? '</HEAD >' : '</head>';
      const bodyEnd = uppercase ? '</BODY >' : '</body>';
      const nested = '<iframe srcdoc="&lt;html&gt;&lt;body&gt;child&lt;/body&gt;&lt;/html&gt;"></iframe>';
      const html = `<html><head><script>var marker="</head>";</script>${headEnd}<body>${existingToc ? '<aside class="report-toc"></aside>' : ''}${nested}<script>${library}</script><script>renderReportCharts([{id:"echarts-0",json:"{}"},{id:"echarts-1",json:"{}"}]);</script>${bodyEnd}</html>`;
      t.mock.method(globalThis, 'fetch', async () => new Response(html, { headers: { 'Content-Type': 'text/html', 'Content-Disposition': 'attachment; filename="report.html"' } }));
      let blob;
      t.mock.method(URL, 'createObjectURL', value => { blob = value; return 'blob:report-test'; });
      const result = await api.getSkillFlowExecutionReportUrl(1);
      assert.equal(result.downloadName, 'report.html');
      const output = await blob.text();
      assert.ok(output.includes(`<script>${library}</script>`), 'complete ECharts source must remain unchanged');
      assert.ok(output.includes('<script>var marker="</head>";</script>'));
      assert.ok(output.includes(nested), 'nested srcdoc must remain unchanged');
      const scripts = [...output.matchAll(/<script>([\s\S]*?)<\/script>/g)].map(match => match[1]);
      for (const script of scripts) assert.doesNotThrow(() => new vm.Script(script));
      assert.ok(output.indexOf('<style>') < output.lastIndexOf(headEnd));
      assert.ok(output.lastIndexOf('document.querySelectorAll(".report-toc a[data-level]")') > output.indexOf('renderReportCharts(['));
      assert.ok(output.endsWith(`${bodyEnd}</html>`));
    });
  }
}

for (const operation of ['create', 'update']) {
  for (const detail of [
    'ScriptParamsInvalid: 节点 n1 的参数 year 必填',
    'invalid parameter: year',
    'FlowValidationFailed: 节点 n1 缺少脚本参数 year',
    'ReportOutlineInvalid: chapter_1 references missing node n2',
  ]) {
    test(`${operation} retains backend detail: ${detail}`, async t => {
      t.mock.method(globalThis, 'fetch', async () => new Response(JSON.stringify({ message: detail }), {
        status: 400, headers: { 'Content-Type': 'application/json' },
      }));
      await assert.rejects(
        () => operation === 'create' ? api.createSkillFlow({ nodes: [] }) : api.updateSkillFlow(1, { nodes: [] }),
        error => error.message.includes(detail),
      );
    });
  }
}

test('empty failure response includes HTTP status', async t => {
  t.mock.method(globalThis, 'fetch', async () => new Response('', { status: 502 }));
  await assert.rejects(() => api.createSkillFlow({ nodes: [] }), /HTTP 502/);
});

test('known name conflict keeps its readable translation', async t => {
  t.mock.method(globalThis, 'fetch', async () => new Response(JSON.stringify({ message: 'FlowNameConflict' }), {
    status: 409, headers: { 'Content-Type': 'application/json' },
  }));
  await assert.rejects(() => api.createSkillFlow({ nodes: [] }), /流程名称已存在/);
});
