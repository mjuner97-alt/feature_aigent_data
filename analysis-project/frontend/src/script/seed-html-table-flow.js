const base = process.env.SKILL_API_BASE || 'http://localhost:8081/api';
const userId = process.env.SKILL_USER_ID || 'skill_demo_u001';
const scriptId = 'html_table_duplicate_probe';
const headers = { 'Content-Type': 'application/json', 'X-User-Id': userId };

async function request(path, options = {}) {
  const response = await fetch(`${base}${path}`, { headers, ...options });
  if (!response.ok) throw new Error(`${path}: HTTP ${response.status} ${await response.text()}`);
  return response.json();
}

const source = `import json
import sys

def main():
    json.loads(sys.stdin.read() or '{}')
    print('''<!DOCTYPE html>
<html><head><meta charset="utf-8"><title>HTML 表格重复验证</title></head>
<body><h2>HTML 表格重复验证</h2>
<table border="1"><thead><tr><th>项目</th><th>数值</th></tr></thead>
<tbody><tr><td>样例 A</td><td>12</td></tr><tr><td>样例 B</td><td>24</td></tr></tbody></table>
</body></html>''')
    print('\\n\\n\x60\x60\x60echarts')
    print('{"xAxis":{"type":"category","data":["样例 A","样例 B"]},"yAxis":{"type":"value"},"series":[{"type":"bar","data":[12,24]}]}')
    print('\x60\x60\x60')

if __name__ == '__main__':
    main()
`;

let script = (await request('/script-registry')).find(item => item.scriptId === scriptId);
if (!script) {
  script = await request('/script-registry', {
    method: 'POST', body: JSON.stringify({ scriptId, name: '[测试] HTML 表格重复验证',
      description: '固定输出一张 HTML 表格和一张 ECharts 图表', datasources: '["gauss"]',
      paramsSchema: '[]', timeoutSeconds: 30, enabled: 1 }),
  });
}
await request(`/script-registry/${script.id}/source`, {
  method: 'PUT', body: JSON.stringify({ content: source, expectedContentHash: null }),
});

const flowName = '[测试] HTML 表格重复验证';
let flow = (await request('/skill-flows?scope=mine')).find(item => item.name === flowName);
if (!flow) {
  flow = await request('/skill-flows', {
    method: 'POST', body: JSON.stringify({
      name: flowName, description: '手动执行后对比节点内容与汇总报告的表格数量',
      taskQuestion: '执行 HTML 表格重复验证', summaryQuestionTemplate: '直接汇总节点结果',
      enabled: true, maxParallelism: 1, notifyEnabled: false,
      triggers: [{ keyword: `html-table-probe-${userId}`, priority: 1, enabled: false }],
      nodes: [{ nodeKey: 'html_table_probe', nodeName: 'HTML 表格和图表', nodeType: 'PYTHON',
        scriptId, scriptParamsJson: '{}', questionTemplate: '运行固定样例',
        metricIds: [], metricOverrideConfigured: true, required: true, maxAttempts: 2, sortOrder: 1 }],
    }),
  });
}
console.log(JSON.stringify({ flowId: flow.id, flowName: flow.name, scriptId, enabled: flow.enabled }, null, 2));
