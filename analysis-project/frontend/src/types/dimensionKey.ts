/** 维度键字典词表行: 口语/关键词 alias → 标准值 (即脚本收到的参数值)。
 *  type="regex" (我X方案 §8): alias 为正则串, value 为用途标签 (myDept/myself/extract 等),
 *  供 PersonalDimensionResolver 等正则消费方使用; 缺省 = 同义词 (contains 命中)。 */
export interface DimensionKeyAlias {
  alias: string;
  value: string;
  type?: string | null;
}

/**
 * 维度键字典 (docs/history/skill-direct-exec-plan.md §9):
 * Skill 配置页参数映射下拉的可选维度键 = 内置 8 键 + 本字典启用键。
 */
export interface DimensionKeyRow {
  id: number;
  key: string;
  label: string;
  builtin: boolean;
  aliases: DimensionKeyAlias[];
  enabled: boolean;
  remark: string | null;
}
