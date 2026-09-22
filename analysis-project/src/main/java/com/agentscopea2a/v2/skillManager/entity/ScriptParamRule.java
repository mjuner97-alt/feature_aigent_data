package com.agentscopea2a.v2.skillManager.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 脚本参数取值规则实体 - 一条 PERIOD 型周期规则记录.
 *
 * <p>对应 GaussDB {@code script_param_rule} 表 (V20260922.1). Python 脚本节点的
 * {@code scriptParamsJson} 中, 参数值可写成 {@code {"$rule": "rule_key"}} 标记,
 * 执行时按本表的周期定义从数据日期推导出真实值, 免去每次发版手工改流程定义.
 *
 * <p>规则语义:
 * <ul>
 *   <li>锚点 = 流程执行的 data_date (兜底当天);</li>
 *   <li>{@code periodUnit}: 以月/季/年为周期, 从锚点所在周期起算;</li>
 *   <li>{@code offsetStart}/{@code offsetEnd}: 相对当前周期的偏移窗口 (含两端),
 *       宽度 1 → single 输出, 宽度 &gt; 1 → array 输出 (按 {@code sortOrder} 排);</li>
 *   <li>{@code formatPattern}: 每个周期格式化成一个字符串, 占位符
 *       {@code {year} {month} {quarter}} (支持 {@code {month:02}} 补零), 其余文本原样.</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScriptParamRule {
    private Long id;
    /** 规则标识, scriptParamsJson 中 {"$rule": "rule_key"} 引用 */
    private String ruleKey;
    /** 展示名, 前端下拉显示 */
    private String ruleName;
    /** 输出类型: single 单值 / array 数组 (偏移窗口宽度 > 1) */
    private String valueType;
    /** 周期单位: month / quarter / year */
    private String periodUnit;
    /** 周期格式化模板, 占位符 {year} {month} {quarter}, 支持 {month:02} 补零 */
    private String formatPattern;
    /** 相对当前周期的起始偏移 (含), 负数往过去 */
    private Integer offsetStart;
    /** 相对当前周期的结束偏移 (含), 正数往未来 */
    private Integer offsetEnd;
    /** 数组输出排序: asc 时间正序 / desc 时间倒序 */
    private String sortOrder;
    /** 1 启用 / 0 停用 */
    private Integer enabled;
    private String description;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static final String VALUE_TYPE_SINGLE = "single";
    public static final String VALUE_TYPE_ARRAY = "array";
    public static final String UNIT_MONTH = "month";
    public static final String UNIT_QUARTER = "quarter";
    public static final String UNIT_YEAR = "year";
}
