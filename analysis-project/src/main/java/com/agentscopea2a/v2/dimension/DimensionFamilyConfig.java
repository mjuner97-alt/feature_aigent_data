package com.agentscopea2a.v2.dimension;

/**
 * 维度家族映射配置行（表 {@code dimension_family_config}，
 * docs/202611/同级维度消解-维度提及无值快照删除与词表冲突方案.md §3.2）。
 *
 * <p>同级维度消解：本轮追问通过动态键（drillDown/dimension）提及某维度值且该家族
 * 无值时，快照该家族参数删除。value → family 的映射由业务人员在前端「维度键字典」页
 * 维护；family 是代码闭集（对应 extraction 字段），不能发明新家族。
 *
 * @param id      数据库主键（新增时 null）
 * @param value   词表标准值（drillDown/dimension 词表的 value，如 组维度/部门）
 * @param family  维度家族，逗号分隔（固定集 groupName/appName/productLine/perName/
 *                perNo/perRole/deptName，如 个人维度 → perName,perNo,perRole）
 * @param enabled 软删开关
 * @param remark  备注
 */
public record DimensionFamilyConfig(
        Long id,
        String value,
        String family,
        boolean enabled,
        String remark) {

    public DimensionFamilyConfig {
        if (value != null) value = value.trim();
        if (family != null) family = family.trim();
    }
}
