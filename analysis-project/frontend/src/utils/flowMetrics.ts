import type { SkillFlow, SkillFlowNode } from '../types/skillFlow';
import type { SkillDependencyMetric } from '../types/skillJob';

/** Keep selected options visible when the remote result set changes. */
export function retainSelectedMetrics(results: SkillDependencyMetric[], previous: SkillDependencyMetric[], nodes: SkillFlowNode[]): SkillDependencyMetric[] {
  const selected = new Map<number, SkillDependencyMetric>();
  for (const node of nodes) {
    (node.metricIds || []).forEach((id, index) => {
      const name = node.metricNames?.[index];
      if (name) selected.set(id, { id, name, code: '', enabled: true });
    });
  }
  for (const metric of previous) {
    if (nodes.some(node => (node.metricIds || []).includes(metric.id))) selected.set(metric.id, metric);
  }
  // Fresh registry entries replace detail-only placeholders (which have no code).
  for (const metric of results) selected.set(metric.id, metric);
  return [...selected.values()];
}

export function metricOptionLabel(metric: SkillDependencyMetric): string {
  const name = metric.name?.trim() || metric.code?.trim() || `指标 #${metric.id}`;
  const code = metric.code?.trim();
  return code && code !== name ? `${name} (${code})` : name;
}

/** An empty selection means inherit the script/Skill defaults. */
export function setNodeMetricSelection(node: SkillFlowNode, values: number[]): void {
  const names = new Map((node.metricIds || []).map((id, index) => [id, node.metricNames?.[index] || '']));
  node.metricIds = [...new Set(values || [])];
  node.metricNames = node.metricIds.map(id => names.get(id) || '');
  node.metricOverrideConfigured = node.metricIds.length > 0;
  node.effectiveMetricIds = undefined;
  node.metricSource = undefined;
}

export function flowMetricCount(flow: SkillFlow): number {
  return new Set(flow.nodes.flatMap(node =>
    node.metricIds?.length ? node.metricIds : (node.effectiveMetricIds || []))).size;
}
