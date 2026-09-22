package com.agentscopea2a.v2.dimension;

import com.agentscopea2a.v2.dimension.DimensionState.PeerDimensionType;

/**
 * 维度同义词配置行（表 {@code dimension_alias}），"口语化词 → 标准化名称"映射。
 *
 * <p>{@code triggerKeyword} 非空时本行是条件行：仅当问题中出现该完整关键词时才参与匹配
 * （如 TEAM/军队 配触发词"军队组"——说"军队组"落小组，裸"军队"时本行不参与）。
 *
 * @param dimension      维度（TEAM / PRODUCT_LINE / APPLICATION）
 * @param alias          口语化词，如 军队 / GBC / 车贷组
 * @param standardName   标准化名称，如 特种业务组 / FS-LFS-FARM
 * @param triggerKeyword 触发关键词（完整词），可空
 * @param enabled        软删开关
 * @param remark         备注
 */
public record DimensionAlias(
        Long id,
        PeerDimensionType dimension,
        String alias,
        String standardName,
        String triggerKeyword,
        boolean enabled,
        String remark) {

    public DimensionAlias {
        if (alias != null) alias = alias.trim();
        if (standardName != null) standardName = standardName.trim();
        if (triggerKeyword != null && triggerKeyword.isBlank()) triggerKeyword = null;
    }
}
