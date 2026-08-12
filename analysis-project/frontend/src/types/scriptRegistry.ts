/** Python 脚本注册表相关类型 (对应后端 ScriptRegistryEntry) */

export interface ScriptRegistryEntry {
  id: number;
  scriptId: string;
  name: string;
  description: string;
  /** 脚本相对路径, 相对 /app/workspace/scripts/ */
  scriptPath: string;
  /** JSON 数组字符串, 如 ["gauss"] 或 ["gauss","mysql"] */
  datasources: string;
  /** 参数定义 JSON 字符串 */
  paramsSchema: string;
  /** 执行超时 (秒), 硬上限 300 */
  timeoutSeconds: number;
  enabled: number; // 0 or 1
  createdAt: string;
  updatedAt: string;
  createdBy: string;
}

/** 列表视图项 (不含 paramsSchema) */
export interface ScriptRegistryListItem {
  id: number;
  scriptId: string;
  name: string;
  description: string;
  scriptPath: string;
  datasources: string;
  timeoutSeconds: number;
  enabled: number;
  createdAt: string;
  updatedAt: string;
  createdBy: string;
}

/** 新增/编辑输入 */
export interface ScriptRegistryInput {
  scriptId: string;
  name: string;
  description: string;
  scriptPath: string;
  datasources: string;
  paramsSchema: string;
  timeoutSeconds: number;
  enabled?: number;
}

/** params_schema 中的单条参数定义 */
export interface ParamSchemaItem {
  name: string;
  type: string; // string | int | date | boolean | int[] | string[] | date[]
  required: boolean;
  description: string;
}
