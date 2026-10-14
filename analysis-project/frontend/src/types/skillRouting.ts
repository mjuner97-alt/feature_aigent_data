export interface SkillRoutingMetadata {
  skillName: string;
  description: string | null;
  shortSummary: string;
  keywords: string[];
  domainTags: string[];
  topicTags: string[];
  creator: string;
  active: boolean;
  updatedAt: string | null;
  configured: boolean;
  level1Keywords: string[];
  level2Keywords: string[];
  level3Keywords: string[];
  scriptId: string | null;
  paramMapping: Record<string, string>;
}

export interface SkillRoutingInput {
  shortSummary: string;
  keywords: string[];
  domainTags: string[];
  topicTags: string[];
  active: boolean;
  level1Keywords: string[];
  level2Keywords: string[];
  level3Keywords: string[];
  scriptId: string | null;
  paramMapping: Record<string, string>;
}

/** 直接执行维度键枚举 (param_mapping value 合法取值, 与后端 DirectSkillExecutor.DIMENSION_KEYS 同源) */
export const DIRECT_EXEC_DIMENSION_KEYS = [
  { key: 'versionPlan', label: '版本计划 (2026年7月份版本)' },
  { key: 'vestQuarter', label: '季度 (2026年2季度)' },
  { key: 'deptName', label: '部门 (杭州开发二部)' },
  { key: 'groupName', label: '组 (xxxx组)' },
  { key: 'appName', label: '应用 (F-xxxx)' },
  { key: 'productLine', label: '产品线 (xxxx产品线)' },
  { key: 'itemNo', label: '需求项 (Ixxxx)' },
  { key: 'perName', label: '人' },
] as const;
