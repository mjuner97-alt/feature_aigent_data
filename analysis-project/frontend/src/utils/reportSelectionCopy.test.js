import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import { loadModule } from '../../test/loadModule.js';
const { withReportSelectionCopy, reportBlobWithSelectionCopy } = await loadModule(new URL('./reportSelectionCopy.ts', import.meta.url));

for (const close of ['</body>', '</BODY >', '']) test(`injects portable copy handler without rewriting original report (closing=${close})`, async () => {
  const original = `<html><head><script>var library="</body>";</script></head><body><iframe srcdoc="&lt;p&gt;节点&lt;/p&gt;"></iframe><p>正文</p>${close}</html>`;
  const result = withReportSelectionCopy(original);
  const injected = result.match(/<script data-report-selection-copy>([\s\S]*?)<\/script>/);
  assert.ok(injected); assert.doesNotThrow(() => new vm.Script(injected[1]));
  assert.equal(result.replace(injected[0], ''), original);
  assert.equal(withReportSelectionCopy(result), result);
  if (!close) assert.ok(result.endsWith(injected[0]), 'fragment copy script must not be inserted into the library string');
  assert.equal((await reportBlobWithSelectionCopy(new Blob([original]))).type, 'text/html;charset=utf-8');
});
