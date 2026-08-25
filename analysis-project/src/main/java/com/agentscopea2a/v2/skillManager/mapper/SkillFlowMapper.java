package com.agentscopea2a.v2.skillManager.mapper;

import com.agentscopea2a.v2.skillManager.entity.SkillFlow;
import com.agentscopea2a.v2.skillManager.entity.SkillFlowExecution;
import com.agentscopea2a.v2.skillManager.entity.SkillFlowNode;
import com.agentscopea2a.v2.skillManager.entity.SkillFlowNodeExecution;
import com.agentscopea2a.v2.skillManager.entity.SkillFlowNodeMetric;
import com.agentscopea2a.v2.skillManager.entity.SkillFlowTrigger;
import com.agentscopea2a.v2.skillManager.entity.SkillMetricReadiness;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 技能流程持久化入口。
 *
 * <p>这里同时维护两类数据：</p>
 * <ul>
 *     <li>流程定义：流程、节点、依赖指标和触发词等可配置内容；</li>
 *     <li>流程运行态：流程实例、节点实例、执行尝试、worker 租约和通知记录。</li>
 * </ul>
 *
 * <p>涉及抢占、租约或状态流转的方法通常返回受影响行数。调用方应检查返回值，
 * {@code 0} 表示并发条件已发生变化，本次操作没有取得执行权或更新权。</p>
 */
@Mapper
public interface SkillFlowMapper {

    /** Reads an enabled built-in prompt by its stable business key. */
    // ==================== 流程定义 ====================

    // 创建、查询和修改流程本身；软删除会同时禁用流程，但不会物理删除历史定义。
    void insertFlow(SkillFlow flow);

    SkillFlow selectFlowById(@Param("id") Long id);

    SkillFlow selectFlowByCode(@Param("code") String code);

    SkillFlow selectFlowByName(@Param("name") String name);

    List<SkillFlow> selectFlows(@Param("ownerUserId") String ownerUserId, @Param("enabled") Boolean enabled,
                                @Param("keyword") String keyword, @Param("createdBy") String createdBy);

    List<SkillFlow> selectEnabledFlowsByMetricId(@Param("metricId") Long metricId);

    void updateFlow(SkillFlow flow);

    void updateFlowEnabled(@Param("id") Long id, @Param("enabled") Boolean enabled);

    void softDeleteFlow(@Param("id") Long id);

    // ==================== 流程节点及依赖指标 ====================

    // 维护流程的节点拓扑，以及每个节点依赖的数据指标。
    // 更新流程定义时，业务层会先清理旧关联和旧节点，再按新定义重新写入。
    void insertNode(SkillFlowNode node);

    List<SkillFlowNode> selectNodesByFlowId(@Param("flowId") Long flowId);

    void deleteNodeMetricsByFlowId(@Param("flowId") Long flowId);

    void deleteNodesByFlowId(@Param("flowId") Long flowId);

    void insertNodeMetric(SkillFlowNodeMetric nodeMetric);

    List<Long> selectMetricIdsByNodeId(@Param("nodeId") Long nodeId);

    // ==================== 触发规则 ====================

    // 维护聊天关键词等触发规则；normalizedKeyword 用于忽略输入格式差异后的精确匹配。
    void insertTrigger(SkillFlowTrigger trigger);

    List<SkillFlowTrigger> selectTriggersByFlowId(@Param("flowId") Long flowId);

    SkillFlowTrigger selectTriggerByNormalizedKeyword(@Param("normalizedKeyword") String normalizedKeyword);

    void deleteTriggersByFlowId(@Param("flowId") Long flowId);

    // ==================== 指标就绪状态 ====================

    // 记录某个业务日期的指标是否已准备完成，供流程判断是否可以从等待状态进入执行阶段。
    // upsert 会合并同一指标、同一日期的重复上报。
    void upsertMetricReadiness(SkillMetricReadiness readiness);

    SkillMetricReadiness selectMetricReadiness(@Param("metricId") Long metricId,
                                               @Param("dataDate") LocalDate dataDate);

    List<SkillMetricReadiness> selectReadyMetrics(@Param("metricIds") List<Long> metricIds,
                                                  @Param("dataDate") LocalDate dataDate);

    // ==================== 流程实例与节点实例 ====================

    // 流程实例是一次完整运行；节点实例是该次运行中各技能节点的状态快照。
    int insertFlowExecution(SkillFlowExecution execution);

    SkillFlowExecution selectFlowExecutionById(@Param("id") Long id);

    /**
     * 查询流程实例并对数据库行加排他锁。
     * 必须在事务中使用，用于串行化同一流程实例的关键状态变更。
     */
    SkillFlowExecution selectFlowExecutionForUpdate(@Param("id") Long id);

    void insertNodeExecution(SkillFlowNodeExecution execution);

    List<SkillFlowNodeExecution> selectNodeExecutions(@Param("flowExecutionId") Long flowExecutionId);

    // ==================== 运行态查询与状态维护 ====================

    List<SkillFlowTrigger> selectEnabledTriggers();

    /** 根据防重键查询尚未结束的运行实例，避免同一任务被重复触发。 */
    SkillFlowExecution selectActiveExecution(@Param("guardKey") String guardKey);

    /** 查询某次会话最近关联的流程实例，优先返回仍持有防重键的活动实例。 */
    SkillFlowExecution selectLatestConversationExecution(@Param("userId") String userId, @Param("conversationId") String conversationId);

    List<SkillFlowExecution> selectExecutions(@Param("status") String status, @Param("createdBy") String createdBy, @Param("userId") String userId);

    /** 扫描仍在等待依赖指标就绪的流程实例。 */
    List<SkillFlowExecution> selectWaitingExecutions();

    void updateExecution(SkillFlowExecution execution);

    SkillFlowNodeExecution selectNodeExecution(@Param("id") Long id);

    // ==================== 节点调度、worker 租约与并发抢占 ====================

    /**
     * 找出当前可调度的节点，包括首次排队、等待重试，以及租约已过期且仍可重试的节点。
     * 查询同时受 nextRunAt、租约有效期和流程并行度限制；结果只是候选集，仍需调用
     * {@link #claimNode(Long, String, LocalDateTime, LocalDateTime)} 原子抢占后才能执行。
     */
    List<SkillFlowNodeExecution> selectRunnableNodes(@Param("now") LocalDateTime now);

    /** 查询租约已经过期且重试次数耗尽的节点，供兜底扫描将其判定为失败。 */
    List<SkillFlowNodeExecution> selectExpiredExhaustedNodes(@Param("now") LocalDateTime now);

    /**
     * 服务重启后回收其他 worker 遗留的 RUNNING 节点，并立即放回重试队列。
     *
     * @return 被回收的节点数量
     */
    int recoverAbandonedRunningNodes(@Param("owner") String owner, @Param("now") LocalDateTime now);

    /**
     * 原子抢占一个候选节点：切换为 RUNNING、增加尝试次数并写入 worker 租约。
     *
     * @return {@code 1} 表示抢占成功；{@code 0} 表示节点已被其他 worker 抢占或已不可运行
     */
    int claimNode(@Param("id") Long id, @Param("owner") String owner, @Param("expiresAt") LocalDateTime expiresAt, @Param("now") LocalDateTime now);

    /** 统计租约仍有效的运行中节点，用于判断流程当前占用的并行度。 */
    int countActiveRunningNodes(@Param("flowExecutionId") Long flowExecutionId, @Param("now") LocalDateTime now);

    /**
     * 原子取得流程汇总权，仅允许 QUEUED 或 RUNNING 状态进入 SUMMARIZING。
     *
     * @return {@code 1} 表示取得汇总权；{@code 0} 表示流程已被其他线程推进
     */
    int claimExecutionForSummary(@Param("id") Long id);

    // ==================== 节点执行结果与尝试记录 ====================

    /** 将指定节点尚未结束的尝试标记为租约过期失败，避免留下永久 RUNNING 的尝试记录。 */
    int failRunningAttemptsForNode(@Param("nodeId") Long nodeId, @Param("now") LocalDateTime now);

    void updateNodeExecution(SkillFlowNodeExecution node);

    /**
     * 提交一次节点执行结果。只有节点仍为 RUNNING，且尝试次数、租约所有者均与预期一致时才更新。
     * 这些条件用于阻止租约过期的旧 worker 覆盖新 worker 已提交的结果。
     *
     * @return {@code 1} 表示提交成功；{@code 0} 表示当前 worker 已失去该节点的更新权
     */
    int completeNodeAttempt(@Param("node") SkillFlowNodeExecution node,
                            @Param("expectedAttempt") int expectedAttempt,
                            @Param("expectedLeaseOwner") String expectedLeaseOwner);

    void insertAttempt(com.agentscopea2a.v2.skillManager.entity.SkillFlowNodeAttempt attempt);

    /**
     * 结束一次尝试，仅允许更新仍为 RUNNING 的记录，防止重复回调覆盖终态。
     *
     * @return 实际更新的记录数；{@code 0} 通常表示该尝试已经结束
     */
    int updateAttempt(com.agentscopea2a.v2.skillManager.entity.SkillFlowNodeAttempt attempt);

    List<com.agentscopea2a.v2.skillManager.entity.SkillFlowNodeAttempt> selectAttempts(@Param("nodeId") Long nodeId);

    // ==================== 流程完成通知 ====================

    // 通知记录保存发送请求、响应和最终状态，用于追踪流程完成后的消息投递结果。
    List<com.agentscopea2a.v2.skillManager.entity.SkillFlowNotification> selectNotifications(@Param("executionId") Long executionId);
    void insertNotification(com.agentscopea2a.v2.skillManager.entity.SkillFlowNotification notification);
    void updateNotification(com.agentscopea2a.v2.skillManager.entity.SkillFlowNotification notification);
}
