package com.agentscopea2a.v2.dimension;

import java.util.List;

/**
 * 维度键字典配置行（表 {@code dimension_key_config}，docs/history/skill-direct-exec-plan.md §9）。
 *
 * <p>方案 A 维度键动态化：内置 9 键（builtin=true）只存展示信息，提取走
 * {@code DimensionStateManager} 结构化规则不变；用户新增键的提取 = 词表命中，
 * {@code aliases} 为 {@code [{"alias":"Q1卡口","value":"Q1"}, ...]}，问题中出现
 * alias（contains 语义）即收集对应 value（去重保序，多值 List 传参）。
 *
 * @param id       数据库主键（新增时 null）
 * @param key      维度键，camelCase，param_mapping value / 管理页下拉取值
 * @param label    展示名，如 卡口
 * @param builtin  内置 9 键 seed 行，管理页只读（不可删/不可改 key）
 * @param aliases  词表行列表，alias → 标准值
 * @param enabled  软删开关
 * @param remark   备注
 */
public record DimensionKeyConfig(
        Long id,
        String key,
        String label,
        boolean builtin,
        List<AliasMapping> aliases,
        boolean enabled,
        String remark) {

    /** 词表行：口语/关键词 alias → 标准值（即脚本收到的参数值）。 */
    public record AliasMapping(String alias, String value) {

        public AliasMapping {
            if (alias != null) alias = alias.trim();
            if (value != null) value = value.trim();
        }

        public boolean valid() {
            return alias != null && !alias.isEmpty() && value != null && !value.isEmpty();
        }
    }

    public DimensionKeyConfig {
        if (key != null) key = key.trim();
        if (label != null) label = label.trim();
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
    }
}
