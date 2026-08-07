/** SkillJob 配置 */
export interface SkillJob {
  id: number;
  name: string;
  skillId: number;
  questionTemplate: string;
  outputPath: string;
  enabled: boolean;
  createdBy: string;
  createdAt: string;
  updatedAt: string;
}

/** 创建请求体（skillId / createdBy 仅创建时确定，此后不可变） */
export interface SkillJobInput {
  name: string;
  skillId: number;
  questionTemplate: string;
  outputPath?: string;
  enabled?: boolean;
}

/**
 * 更新请求体（字段全部可选，后端按非 null 增量更新）。
 * skillId 不可修改（想换 Skill 只能删除后重建），createdBy 不可变（后端忽略）。
 */
export interface SkillJobUpdateInput {
  name?: string;
  questionTemplate?: string;
  outputPath?: string;
  enabled?: boolean;
}

/** 执行记录 */
export interface SkillJobExecution {
  id: number;
  jobId: number;
  triggerType: string;
  status: string;
  conversationId: string;
  resolvedOutputPath: string;
  mdFileWritten: boolean;
  mdFileExists: boolean;
  errorMsg: string;
  startedAt: string;
  completedAt: string;
  createdAt: string;
}
