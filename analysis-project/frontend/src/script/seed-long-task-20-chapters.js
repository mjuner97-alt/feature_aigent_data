// 创建一个用于报告目录/多图表布局验证的长任务测试流程。
// 用法:
//   node seed-long-task-20-chapters.js
//   node seed-long-task-20-chapters.js <scriptId>
// 默认使用内置演示脚本 weekly_business_html_brief_mock；也可以传入任意已启用的 scriptId。

const BASE = process.env.SKILL_API_BASE || 'http://localhost:8085/api';
const USER_ID = process.env.SKILL_USER_ID || 'demo-user';
const SCRIPT_ID = process.argv[2] || 'weekly_business_html_brief_mock';

async function createFlow() {
  const nodes = Array.from({ length: 30 }, (_, index) => {
    const n = index + 1;
    return {
      nodeKey: `chapter_node_${String(n).padStart(2, '0')}`,
      nodeName: `测试节点 ${String(n).padStart(2, '0')}`,
      nodeType: 'PYTHON',
      skillId: null,
      scriptId: SCRIPT_ID,
      scriptParamsJson: JSON.stringify({ weeks: Math.min(8, 3 + (n % 6)), business_line: `测试业务线 ${n}` }),
      questionTemplate: `执行第 ${n} 个测试章节的数据脚本`,
      metricIds: [],
      required: true,
      maxAttempts: 2,
      sortOrder: index,
    };
  });

  // 30 个唯一章节，覆盖 1~4 级嵌套；每个章节绑定一个同名唯一节点。
  const item = (id, title, level, nodeKey, children = []) => ({ id, title, level, nodeKeys: [nodeKey], children });
  let chapterNo = 0;
  const next = (id, title, level, children = []) => {
    const node = nodes[chapterNo];
    chapterNo += 1;
    return item(id, title, level, node.nodeKey, children);
  };
  const level4 = (prefix, count) => Array.from({ length: count }, (_, i) =>
    next(`${prefix}-${i + 1}`, `${prefix} ${i + 1} 详细指标`, 4));
  const level3 = (prefix, count, deep = 0) => Array.from({ length: count }, (_, i) =>
    next(`${prefix}-${i + 1}`, `${prefix} ${i + 1} 分析`, 3, deep && i === 0 ? level4(`${prefix}-${i + 1}.1`, deep) : []));
  const level2 = (prefix, count, deepFirst = 0) => Array.from({ length: count }, (_, i) =>
    next(`${prefix}-${i + 1}`, `${prefix} ${i + 1} 分析`, 2, level3(`${prefix}-${i + 1}`, 2, i === 0 ? deepFirst : 0)));
  const top = (n) => next(`chapter-${n}`, `第${n}章 测试主题${n}`, 1, level2(`第${n}章`, 2, n <= 2 ? 1 : 0));
  const outline = {
    title: '三十章节嵌套目录测试报告',
    numbering: {
      level1: 'chinese', level2: 'arabic', level3: 'arabic', level4: 'arabic',
    },
    items: [top(1), top(2), top(3), top(4)],
  };
  if (chapterNo !== 30) throw new Error(`章节生成数量错误: ${chapterNo}`);

  const body = {
    name: `[测试] 三十章节目录布局 ${new Date().toISOString().slice(0, 19).replace(/[T:]/g, '-')}`,
    description: '用于验证长任务单目录、四级嵌套章节、多图表与标题布局。',
    taskQuestion: '生成二十章节嵌套目录测试报告',
    summaryQuestionTemplate: '请按报告大纲汇总全部章节结果。',
    enabled: false,
    maxParallelism: 1,
    notifyEnabled: false,
    triggers: [],
    nodes,
    reportOutline: outline,
  };

  const response = await fetch(`${BASE}/skill-flows`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-User-Id': USER_ID },
    body: JSON.stringify(body),
  });
  if (!response.ok) throw new Error(`HTTP ${response.status}: ${await response.text()}`);
  const created = await response.json();
  console.log(`已创建测试长任务: id=${created.id}, scriptId=${SCRIPT_ID}`);
  console.log('流程默认关闭，请在长任务页面确认后启用并执行。');
}

createFlow().catch(error => { console.error(error.message || error); process.exitCode = 1; });
