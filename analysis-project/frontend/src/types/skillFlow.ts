export interface SkillFlowTrigger {
  id?: number;
  keyword: string;
  priority: number;
  enabled?: boolean;
}

export interface SkillFlowNode {
  id?: number;
  nodeKey: string;
  skillId: number | null;
  skillName?: string;
  questionTemplate: string;
  metricIds: number[];
  metricNames?: string[];
  required: boolean;
  maxAttempts: number;
  sortOrder: number;
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
  createdBy?: string;
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
