/** SQL 注册表相关类型 */

export interface SqlRegistryEntry {
  id: number;
  sqlId: string;
  name: string;
  description: string;
  datasource: string;
  sqlTemplate: string;
  paramsSchema: string; // JSON string
  enabled: number; // 0 or 1
  createdAt: string;
  updatedAt: string;
  createdBy: string;
  /** 创建人姓名; 人员信息中查不到时为空 */
  createdByName?: string;
}

/** 列表视图项 (不含 sqlTemplate) */
export interface SqlRegistryListItem {
  id: number;
  sqlId: string;
  name: string;
  description: string;
  datasource: string;
  paramsSchema: string;
  enabled: number;
  createdAt: string;
  updatedAt: string;
  createdBy: string;
  /** 创建人姓名; 人员信息中查不到时为空 */
  createdByName?: string;
}

/** 新增/编辑输入 */
export interface SqlRegistryInput {
  sqlId: string;
  name: string;
  description: string;
  datasource: string;
  sqlTemplate: string;
  paramsSchema: string;
  enabled?: number;
  /** 创建人(统一认证号); 编辑时允许临时修正, 新增时后端默认取当前用户 */
  createdBy?: string;
}

/** SQL 测试请求 */
export interface SqlTestRequest {
  sqlTemplate: string;
  datasource: string;
  paramsSchema: string;
  params: Record<string, any>;
}

/** SQL 测试结果 */
export interface SqlTestResult {
  success: boolean;
  error: string | null;
  columns: string[];
  rows: Record<string, any>[];
  totalRows: number;
  elapsedMs: number;
  datasource: string;
  sqlId: string;
}

/** params_schema 中的单条参数定义 */
export interface ParamSchemaItem {
  name: string;
  type: string; // string | int | date | boolean | int[] | string[] | date[]
  required: boolean;
  description: string;
}
