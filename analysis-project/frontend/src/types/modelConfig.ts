/** 用户模型配置 — 与后端 com.agentscopea2a.entity.UserModelConfig 对齐 */

export interface UserModelConfig {
  userId: string;
  provider: string;
  token: string;
  modelName: string;
  requestUrl: string;
  expireAt?: string | null;
  lastNotifiedAt?: string | null;
  createdAt?: string | null;
  updatedAt?: string | null;
}

/** 连接测试结果 */
export interface ModelTestResult {
  success: boolean;
  /** 成功时模型返回的一小段内容(截断) */
  reply?: string | null;
  /** 失败原因 / 成功提示 */
  message?: string | null;
  /** 测试耗时 (ms) */
  latencyMs: number;
}
