/** SkillJob 配置。outputPath 不暴露给前端（磁盘路径由后端按 userId + baseDir 拼） */
export interface SkillJob {
  id: number;
  name: string;
  skillId: number;
  /** 关联 Skill 名称（后端 join skill_manage 返回，跨用户查看也可显示） */
  skillName?: string;
  questionTemplate: string;
  enabled: boolean;
  /** 依赖指标 ID（可选，关联后随指标就绪触发） */
  metricId?: number | null;
  /** 依赖指标编码（join 展示） */
  metricCode?: string;
  /** 依赖指标名称（join 展示） */
  metricName?: string;
  createdBy: string;
  /** 创建人姓名(后端从 developer_pl_person_info 解析,缺失为 undefined,前端回退 userId) */
  createdByName?: string;
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
 * createdBy 不可变（后端忽略）。
 * skillId 可修改（正值才更新）。
 * metricId 可修改：0 = 清除关联（不关联），正值 = 关联该指标，不传 = 保留原值。
 */
export interface SkillJobUpdateInput {
  name?: string;
  skillId?: number;
  metricId?: number;
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
  /** 排队位置"前面还有N个"，仅 PENDING 状态有值，其余为 null */
  queueAhead?: number | null;
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
