/** 维度同义词类型 (后端 DimensionAlias record, /v2/dimension/alias)。 */

/** URL 路径维度段: team / product-line / application */
export type DimensionPath = 'team' | 'product-line' | 'application';

/** 后端枚举 PeerDimensionType 序列化值 */
export type PeerDimension = 'TEAM' | 'PRODUCT_LINE' | 'APPLICATION';

export interface DimensionAlias {
  id: number | null;
  dimension: PeerDimension;
  alias: string;
  standardName: string;
  triggerKeyword: string | null;
  enabled: boolean;
  remark: string | null;
}

export interface DimensionAliasInput {
  /** PUT 必填; POST 时由路径参数决定 */
  dimension?: PeerDimension;
  alias: string;
  standardName: string;
  triggerKeyword?: string | null;
  enabled?: boolean;
  remark?: string | null;
}
