import type { ScriptParams } from './scriptRegistry';

export interface SkillFlowTrigger {
  id?: number;
  keyword: string;
  priority: number;
  enabled?: boolean;
}

export interface SkillFlowNode {
  id?: number;
  nodeKey: string;
  nodeName?: string;
  skillId: number | null;
  /** New long-task execution mode: one node binds exactly one registered Python script. */
  nodeType?: 'SKILL' | 'PYTHON';
  scriptId?: string | null;
  scriptName?: string;
  /** Serialized to the backend script_params_json field. */
  scriptParams?: ScriptParams;
  skillName?: string;
  questionTemplate: string;
  metricIds: number[];
  metricNames?: string[];
  required: boolean;
  maxAttempts: number;
  sortOrder: number;
}

export type OutlineNumberingStyle = 'chinese' | 'arabic' | 'none';
export interface ReportOutlineNumbering {
  /** Legacy defaults retained for backwards-compatible templates. */
  level1?: OutlineNumberingStyle;
  level2?: OutlineNumberingStyle;
  level3?: OutlineNumberingStyle;
  /** Arbitrary-depth numbering styles (level is one-based). */
  [level: `level${number}`]: OutlineNumberingStyle | undefined;
}
export interface ReportOutlineItem {
  id: string;
  title: string;
  /** One-based depth; intentionally unbounded beyond the legacy 1~3 levels. */
  level?: number;
  /** 旧单节点绑定字段,保存时取 nodeKeys 首个兜底,读取时优先 nodeKeys。 */
  nodeKey?: string | null;
  /** 章节绑定的执行节点列表(按序渲染,同章节可挂多个)。 */
  nodeKeys?: string[];
  children?: ReportOutlineItem[];
}
export interface ReportOutline {
  /** 整份报告的总标题，渲染时居中显示。 */
  title?: string;
  numbering: ReportOutlineNumbering;
  items: ReportOutlineItem[];
}

export interface SkillFlow {
  id: number;
  code?: string;
  name: string;
  description?: string;
  taskQuestion: string;
  summaryQuestionTemplate: string;
  enabled: boolean;
  scheduleRules?: string | null;
  maxParallelism: number;
  notifyEnabled: boolean;
  triggers: SkillFlowTrigger[];
  nodes: SkillFlowNode[];
  reportOutline?: ReportOutline | null;
  createdBy?: string;
  /** 是否公开触发(由开发人员开通,前端只读展示):开启后所有人的聊天都会命中该流程,关闭时只有创建人自己的对话能触发。 */
  chatPublic?: boolean;
  createdAt?: string;
  updatedAt?: string;
  deleted?: boolean;
}

export interface SkillFlowInput {
  code?: string;
  name: string;
  description?: string;
  taskQuestion: string;
  summaryQuestionTemplate: string;
  enabled: boolean;
  scheduleRules?: string | null;
  maxParallelism: number;
  notifyEnabled: boolean;
  triggers: SkillFlowTrigger[];
  nodes: SkillFlowNode[];
  reportOutline?: ReportOutline | null;
}

export interface FlowMetricReadiness {
  metricId?: number;
  metricCode?: string;
  metricName?: string;
  status: string;
  readyAt?: string | null;
  affectedSkills?: string[];
}

export interface SkillFlowNodeAttempt {
  attemptNo: number;
  status: string;
  retryable?: boolean;
  errorCode?: string | null;
  errorMessage?: string | null;
  startedAt?: string | null;
  completedAt?: string | null;
  durationMs?: number | null;
}

export interface SkillFlowNodeExecution {
  id?: number;
  nodeKey: string;
  nodeName?: string;
  skillName?: string;
  questionTemplateSnapshot?: string;
  renderedQuestion?: string;
  required: boolean;
  status: string;
  attemptCount: number;
  maxAttempts: number;
  errorCode?: string | null;
  errorMessage?: string | null;
  hasResult?: boolean;
  startedAt?: string | null;
  completedAt?: string | null;
  attempts?: SkillFlowNodeAttempt[];
}

export interface SkillFlowNotification {
  id?: number;
  status: string;
  requestType?: string;
  recipientSummary?: string;
  errorMessage?: string | null;
  createdAt?: string;
  completedAt?: string | null;
}

export interface SkillFlowExecution {
  id: number;
  flowId?: number;
  flowName: string;
  flowCode?: string;
  status: string;
  triggerType?: 'AUTO_METRIC' | 'MANUAL' | 'CHAT' | null;
  triggerUserId?: string;
  triggerUserName?: string;
  originalQuestion?: string;
  dataDate?: string;
  requiredMetricCount: number;
  readyMetricCount: number;
  totalNodeCount?: number;
  completedNodeCount?: number;
  summaryQuestionTemplateSnapshot?: string;
  renderedSummaryQuestion?: string;
  summaryJson?: unknown;
  reportPath?: string | null;
  reportUrl?: string | null;
  latestNotificationStatus?: string | null;
  createdAt?: string;
  startedAt?: string | null;
  completedAt?: string | null;
  /** 有效执行耗时(秒):所有尝试审计耗时之和,不含排队/等指标/重跑间隔;尚无尝试记录时为 null。 */
  activeDurationSeconds?: number | null;
  metrics?: FlowMetricReadiness[];
  nodes?: SkillFlowNodeExecution[];
  notifications?: SkillFlowNotification[];
}

/** 流程定义级指标就绪预检(手动执行前查,同 FlowMetricReadiness 但含受影响节点)。 */
export interface FlowMetricPrecheck {
  metricId: number;
  metricCode?: string;
  metricName?: string;
  status: string;
  affectedNodeKeys?: string[];
}

/** 手动触发返回:created=false 表示当日已有活跃执行,直接复用。 */
export interface SkillFlowRunResult {
  executionId: number;
  created: boolean;
  status: string;
}
