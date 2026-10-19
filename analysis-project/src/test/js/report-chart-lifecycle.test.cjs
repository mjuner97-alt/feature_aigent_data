const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, '../../main/java/com/agentscopea2a/v2/skillManager/report/HtmlReportRenderer.java'), 'utf8');
const script = source.match(/CHART_RENDER_JS\s*=\s*"""([\s\S]*?)""";/)[1];

function fixture({ hidden = false, observer = true, dependency = true, fail = false, loading = false } = {}) {
  function events(target) {
    const listeners = new Map();
    target.addEventListener = (name, fn) => { if (!listeners.has(name)) listeners.set(name, new Set()); listeners.get(name).add(fn); };
    target.removeEventListener = (name, fn) => listeners.get(name)?.delete(fn);
    target.fire = (name, event = {}) => [...(listeners.get(name) || [])].forEach(fn => fn(event));
    target.listenerCount = () => [...listeners.values()].reduce((sum, set) => sum + set.size, 0);
    return target;
  }
  const el = { id: 'a', clientWidth: hidden ? 0 : 600, clientHeight: hidden ? 0 : 300, style: {}, querySelector: () => null, appendChild() {} };
  const second = { ...el, id: 'b', clientWidth: 600, clientHeight: 300 };
  const errors = [], instances = [], observers = [], timers = new Map();
  let timerId = 0;
  const document = events({ readyState: loading ? 'loading' : 'complete', getElementById: id => id === 'a' ? el : second, querySelectorAll: () => [] });
  const window = events({});
  if (dependency) window.echarts = { init(element) {
    const chart = { disposed: false, resizes: 0, setOption() { if (fail && element === el) throw Error('setOption failed'); }, resize() { this.resizes++; }, dispose() { this.disposed = true; }, isDisposed() { return this.disposed; } };
    instances.push(chart); return chart;
  } };
  if (observer) window.ResizeObserver = class {
    constructor(fn) { this.fn = fn; this.targets = []; observers.push(this); }
    observe(target) { this.targets.push(target); }
    disconnect() { this.disconnected = true; }
  };
  const context = { window, document, console, echarts: window.echarts, normalizeChartOption: option => option, applyReportBarWidths() {},
    setTimeout(fn) { timers.set(++timerId, fn); return timerId; }, clearTimeout(id) { timers.delete(id); } };
  vm.createContext(context);
  vm.runInContext(script, context);
  context.showReportChartError = (element, error) => errors.push({ element, error });
  context.renderReportCharts([{ id: 'a', json: '{}' }, { id: 'b', json: '{}' }]);
  return { window, document, el, instances, observers, errors, timers,
    flush() { const pending = [...timers.values()]; timers.clear(); pending.forEach(fn => fn()); },
    observe() { observers.forEach(o => o.fn(o.targets.map(target => ({ target })))); } };
}

test('all charts initialize on load using the original rendering flow', () => {
  const f = fixture({ loading: true });
  assert.equal(f.instances.length, 0);
  f.window.fire('load');
  assert.equal(f.instances.length, 2);
  assert.equal(f.errors.length, 0);
});

test('original rendering does not skip initialization for zero size', () => {
  const f = fixture({ hidden: true });
  assert.equal(f.instances.length, 2);
});

test('window and fullscreen changes resize every chart', () => {
  const f = fixture();
  f.window.fire('resize');
  assert.ok(f.instances.every(c => c.resizes === 1));
  f.document.fire('fullscreenchange'); f.flush();
  assert.ok(f.instances.every(c => c.resizes === 2));
});

test('one failed chart does not prevent following charts from initializing', () => {
  const f = fixture({ fail: true });
  assert.equal(f.instances.length, 2);
  assert.ok(f.instances[0].disposed);
  assert.ok(!f.instances[1].disposed);
  assert.equal(f.errors.length, 1);
});

// 先运行 HtmlReportRendererTest 生成文件；这里验证 Java 最终产物，而非手写脚本替身。
for (const name of ['markdown-node', 'markdown-summary', 'mixed-summary', 'summary-sample']) {
 for (const insertScript of [false, true]) {
  test(`generated ${name} keeps executable ECharts (${insertScript ? 'preview' : 'download'})`, () => {
    const html = fs.readFileSync(path.join(__dirname, `../../../target/skill-report-${name}.html`), 'utf8');
    // 兼容现有汇总预览的插入方式；生成器不能让库字符串被误识别为文档结束。
    const preview = insertScript
      ? html.replace('</body>', '<script>window.reportPreviewReady = true;</script></body>')
      : html;
    const documents = [preview];
    for (const frame of preview.matchAll(/<iframe[^>]* srcdoc="([^"]*)"/g)) {
      documents.push(frame[1].replaceAll('&quot;', '"').replaceAll('&lt;', '<')
        .replaceAll('&gt;', '>').replaceAll('&amp;', '&'));
    }
    let libraries = 0;
    for (const doc of documents) {
      const scripts = [...doc.matchAll(/<script>([\s\S]*?)<\/script>/g)].map(match => match[1]);
      for (const script of scripts) {
        try { new vm.Script(script); }
        catch (error) { assert.fail(`generated script syntax: ${error.message}`); }
      }
      const library = scripts.find(script => script.length > 100_000);
      if (!library) continue;
      libraries++;
      const context = { console, setTimeout: () => 0, clearTimeout() {},
        navigator: { userAgent: 'Mozilla/5.0' },
        document: { documentElement: { style: {} }, createElement: () => ({ getContext: () => null }) } };
      context.window = context; context.self = context;
      vm.createContext(context);
      vm.runInContext(library, context);
      assert.equal(typeof context.window.echarts?.init, 'function');
      assert.ok(scripts.some(script => script.startsWith('renderReportCharts([')), 'library and chart data must be in the same document');
      // 用生成的真实 option 调用真实 ECharts，进一步验证库与数据可重建图表。
      vm.runInContext(scripts.find(script => script.includes('function normalizeChartOption(')), context);
      let chartData;
      context.renderReportCharts = data => { chartData = data; };
      vm.runInContext(scripts.find(script => script.startsWith('renderReportCharts([')), context);
      for (const data of chartData) {
        const chart = context.echarts.init(null, null, { renderer: 'svg', ssr: true, width: 640, height: 400 });
        try {
          chart.setOption(context.normalizeChartOption(JSON.parse(data.json), 640));
          assert.ok(chart.renderToSVGString().startsWith('<svg'));
        } catch (error) {
          assert.fail(`actual chart reconstruction: ${error.message}`);
        } finally {
          chart.dispose();
        }
      }
    }
    assert.equal(libraries, name === 'mixed-summary' || name === 'summary-sample' ? 2 : 1);
  });
 }
}
