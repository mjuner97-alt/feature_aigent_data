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
  return [...selected.values(), ...results.filter(metric => !selected.has(metric.id))];
}

/** An empty selection means inherit the script/Skill defaults. */
export function setNodeMetricSelection(node: SkillFlowNode, values: number[]): void {
  node.metricIds = [...new Set(values || [])];
  node.metricOverrideConfigured = node.metricIds.length > 0;
  node.effectiveMetricIds = undefined;
  node.metricSource = undefined;
}

export function flowMetricCount(flow: SkillFlow): number {
  return new Set(flow.nodes.flatMap(node =>
    node.metricIds?.length ? node.metricIds : (node.effectiveMetricIds || []))).size;
}
