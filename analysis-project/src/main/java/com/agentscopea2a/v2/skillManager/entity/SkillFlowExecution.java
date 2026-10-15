package com.agentscopea2a.v2.skillManager.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 流程执行记录实体:触发即插入,快照流程当时的模板/并发度/通知开关(改编排不影响在跑的执行)。
 * 指标未就绪时停在 WAITING_METRICS,activeGuardKey 保证(用户,会话,流程,日期)幂等。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillFlowExecution {
    /** 执行实例主键，也是返回给调用方的任务 ID。 */
    private Long id;
    /** 来源长任务定义 ID；直接执行报告模板时为空。 */
    private Long flowId;
    /** 来源报告流程（模板）ID；未引用报告流程的长任务可为空。 */
    private Long reportProcessId;
    /** 报告大纲 JSON 快照；按提交时的章节结构汇总，避免后续模板修改影响本次执行。 */
    private String reportOutlineSnapshot;
    /** 来源流程或报告模板的编码快照。 */
    private String flowCode;
    /** 来源流程或报告模板的名称快照，用于任务展示和报告标题。 */
    private String flowName;
    /** 汇总问题模板快照；为空时按节点结果直接生成报告。 */
    private String summaryQuestionTemplateSnapshot;
    /** 完成变量替换后实际用于汇总的提问；尚未汇总或无需模型汇总时可为空。 */
    private String renderedSummaryQuestion;
    /** 本次执行允许同时运行的节点数量上限快照。 */
    private Integer maxParallelismSnapshot;
    /** 创建执行时的通知开关快照。 */
    private Boolean notifyEnabledSnapshot;
    /** 额外通知收件人的用户 ID 快照（逗号分隔）；触发人默认接收通知，无需加入名单。 */
    private String notifyReceiversSnapshot;
    /** 额外名单适用的触发类型快照（逗号分隔）；自动触发未配置时默认 AUTO_METRIC，CHAT/MANUAL 始终允许使用名单。 */
    private String notifyReceiverTriggersSnapshot;
    /** 触发方式：CHAT 对话触发、MANUAL 手动执行、AUTO_METRIC 指标自动触发。 */
    private FlowTriggerType triggerType;
    /** 本次执行归属用户及默认通知对象：主动执行时为触发人，指标自动触发时为流程创建人。 */
    private String triggerUserId;
    /** 触发会话标识；自动执行使用系统生成的标识，用于任务关联和幂等控制。 */
    private String conversationId;
    /** 触发时的原始问题，供节点及汇总模板变量替换使用；模板直接执行时可为空字符串。 */
    private String originalQuestion;
    /** 本次执行的业务数据日期，用于指标就绪判断、参数规则和缓存匹配。 */
    private LocalDate dataDate;
    /** 执行生命周期状态，包含等待、运行、汇总、取消及成功/失败等终态。 */
    private FlowExecutionStatus status;
    /** 活跃任务防重键；终态时清空，允许同一触发条件再次创建任务。 */
    private String activeGuardKey;
    /** 本次执行依赖的去重后指标总数。 */
    private Integer requiredMetricCount;
    /** 依赖指标中已就绪的数量。 */
    private Integer readyMetricCount;
    /** 尚未就绪的指标清单 JSON，通常为数组，全部就绪时为 []。 */
    private String missingMetricsJson;
    /** 汇总结果 JSON，保存节点结果或汇总错误等信息；尚未收尾时可为空。 */
    private String summaryJson;
    /** 汇总报告在报告根目录下的相对文件路径；尚未生成或生成失败时可为空。 */
    private String reportPath;
    /** 用户请求取消的时间；未请求取消时为空。 */
    private LocalDateTime cancelRequestedAt;
    /** 本次任务开始执行的时间；尚未开始时为空。 */
    private LocalDateTime startedAt;
    /** 本次任务进入终态的时间；尚未结束时为空。 */
    private LocalDateTime completedAt;
    /** 执行记录创建时间。 */
    private LocalDateTime createdAt;
    /** 执行记录最后更新时间。 */
    private LocalDateTime updatedAt;
    /** 列表查询聚合的节点总数，非执行表持久化字段。 */
    private Integer totalNodeCount;
    /** 列表查询聚合的终态节点数，包含成功、失败、阻塞和取消，非执行表持久化字段。 */
    private Integer completedNodeCount;
    /** 列表查询聚合的各节点尝试耗时之和（秒），非执行表持久化字段；并行节点耗时相加，区别于任务总历时。 */
    private Long activeDurationSeconds;
}
