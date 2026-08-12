/** SkillJob 配置。outputPath 不暴露给前端（磁盘路径由后端按 userId + baseDir 拼） */
export interface SkillJob {
  id: number;
  name: string;
  skillId: number;
  questionTemplate: string;
  enabled: boolean;
  /** 依赖指标 ID（可选，关联后随指标就绪触发） */
  metricId?: number | null;
  /** 依赖指标编码（join 展示） */
  metricCode?: string;
  /** 依赖指标名称（join 展示） */
  metricName?: string;
  createdBy: string;
  createdAt: string;
  updatedAt: string;
}

/** 创建请求体（skillId / createdBy 仅创建时确定，此后不可变） */
export interface SkillJobInput {
  name: string;
  skillId: number;
  questionTemplate: string;
  /** 依赖指标 ID，可选 */
  metricId?: number | null;
  enabled?: boolean;
}

/**
 * 更新请求体（字段全部可选，后端按非 null 增量更新）。
 * skillId 不可修改（想换 Skill 只能删除后重建），createdBy 不可变（后端忽略）。
 * metricId 亦不可修改（想换指标只能删除后重建）。
 */
export interface SkillJobUpdateInput {
  name?: string;
  questionTemplate?: string;
  enabled?: boolean;
}

/** 执行记录。resolvedOutputPath 不暴露给前端，判"有没有文件"用 mdFileExists */
export interface SkillJobExecution {
  id: number;
  jobId: number;
  triggerType: string;
  status: string;
  conversationId: string;
  mdFileWritten: boolean;
  mdFileExists: boolean;
  errorMsg: string;
  startedAt: string;
  completedAt: string;
  createdAt: string;
}

/** 依赖指标（admin 预置只读，对应后端 SkillDependencyMetricDto） */
export interface SkillDependencyMetric {
  id: number;
  code: string;
  name: string;
  description?: string;
  enabled: boolean;
  notifyEnabled?: boolean;
  notifyContentType?: string;
  notifyContentTemplate?: string;
  createdAt?: string;
  updatedAt?: string;
}

/** 按指标触发的单个 Job 排队结果（对应后端 MetricTriggerItemDto） */
export interface MetricTriggerItem {
  jobId: number;
  name: string;
  /** 排队后拿到的执行记录 id；REJECTED 时为 null */
  executionId: number | null;
  /** QUEUED | REJECTED */
  status: string;
  /** REJECTED 时填，如 JobAlreadyRunning */
  reason: string | null;
}

/** 按指标触发的批量结果（对应后端 MetricTriggerBatchDto） */
export interface MetricTriggerBatch {
  metricCode: string;
  total: number;
  results: MetricTriggerItem[];
}
