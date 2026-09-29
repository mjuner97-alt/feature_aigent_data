const BASE = process.env.SKILL_API_BASE || 'http://localhost:8085/api';
const USER_ID = process.env.SKILL_USER_ID || 'skill_demo_u001';
const SCRIPT_ID = process.argv[2] || 'sales_daily_report';

const nodes = Array.from({ length: 4 }, (_, i) => {
  const n = i + 1;
  return {
    nodeKey: `small_chapter_node_${n}`,
    nodeName: `四章节测试节点 ${n}`,
    nodeType: 'PYTHON', skillId: null, scriptId: SCRIPT_ID,
    scriptParamsJson: JSON.stringify({ business_line: `测试业务线 ${n}` }),
    questionTemplate: `执行四章节测试节点 ${n}`, metricIds: [], required: true,
    maxAttempts: 2, sortOrder: i,
  };
});
const item = (id, title, level, nodeKey, children = []) =>
  ({ id, title, level, nodeKeys: [nodeKey], children });
const outline = {
  title: '四节点三层目录测试报告',
  numbering: { level1: 'chinese', level2: 'arabic', level3: 'arabic', level4: 'none' },
  items: [
    item('chapter-1', '第一章 业务概览', 1, nodes[0].nodeKey, [
      item('chapter-1-1', '核心指标分析', 2, nodes[1].nodeKey, [
        item('chapter-1-1-1', '趋势明细', 3, nodes[2].nodeKey),
      ]),
    ]),
    item('chapter-2', '第二章 风险与建议', 1, nodes[3].nodeKey),
  ],
};
const body = {
  name: `[测试] 四节点三层目录 ${new Date().toISOString().slice(0, 19).replace(/[T:]/g, '-')}`,
  description: '用于验证四个节点、两大章、三层目录的长任务报告布局。',
  taskQuestion: '生成四节点三层目录测试报告',
  summaryQuestionTemplate: '请按报告大纲汇总全部章节结果。',
  enabled: false, maxParallelism: 1, notifyEnabled: false,
  triggers: [{ keyword: '四节点目录测试', priority: 1, enabled: true }],
  nodes, reportOutline: outline,
};
const response = await fetch(`${BASE}/skill-flows`, {
  method: 'POST', headers: { 'Content-Type': 'application/json', 'X-User-Id': USER_ID },
  body: JSON.stringify(body),
});
if (!response.ok) throw new Error(`HTTP ${response.status}: ${await response.text()}`);
const created = await response.json();
console.log(JSON.stringify({ id: created.id, name: created.name, createdBy: created.createdBy, nodeCount: nodes.length }, null, 2));
