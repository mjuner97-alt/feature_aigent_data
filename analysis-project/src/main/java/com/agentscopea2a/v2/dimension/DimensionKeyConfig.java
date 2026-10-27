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

    /** 词表行：口语/关键词 alias → 标准值（即脚本收到的参数值）。
     *  type="regex"（2026/10/26 我X方案 §8）：alias 为正则串、value 为用途标签
     *  （myDept/myGroup/myApp/myProductLine/myself/extract/block），仅供
     *  {@code PersonalDimensionResolver} 等正则消费方使用；所有同义词匹配路径必须
     *  isSynonym() 过滤（正则串含 | 等元字符，被字面 contains 会产生垃圾命中）。
     *  value=block（我X方案 §9）：维度候选 token 命中 alias 正则即拒收（垃圾代词值屏蔽）。 */
    @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record AliasMapping(String alias, String value, String type) {

        public static final String TYPE_REGEX = "regex";
        public static final String VALUE_BLOCK = "block";

        public AliasMapping(String alias, String value) {
            this(alias, value, null);
        }

        public AliasMapping {
            if (alias != null) alias = alias.trim();
            if (value != null) value = value.trim();
            if (type != null) type = type.trim();
        }

        public boolean valid() {
            return alias != null && !alias.isEmpty() && value != null && !value.isEmpty();
        }

        @com.fasterxml.jackson.annotation.JsonIgnore
        public boolean isRegex() {
            return TYPE_REGEX.equalsIgnoreCase(type);
        }

        /** block 条目（我X方案 §9）：regex 型且 value=block，alias 正则命中候选即拒收。 */
        @com.fasterxml.jackson.annotation.JsonIgnore
        public boolean isBlock() {
            return isRegex() && VALUE_BLOCK.equalsIgnoreCase(value);
        }

        /** 同义词条目（存量语义）：词表 contains 匹配 alias 原词收 value。 */
        @com.fasterxml.jackson.annotation.JsonIgnore
        public boolean isSynonym() {
            return !isRegex();
        }
    }

    public DimensionKeyConfig {
        if (key != null) key = key.trim();
        if (label != null) label = label.trim();
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
    }
}
