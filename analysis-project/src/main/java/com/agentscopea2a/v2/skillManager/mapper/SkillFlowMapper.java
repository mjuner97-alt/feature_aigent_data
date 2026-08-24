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

@Mapper
public interface SkillFlowMapper {

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

    void insertNode(SkillFlowNode node);

    List<SkillFlowNode> selectNodesByFlowId(@Param("flowId") Long flowId);

    void deleteNodeMetricsByFlowId(@Param("flowId") Long flowId);

    void deleteNodesByFlowId(@Param("flowId") Long flowId);

    void insertNodeMetric(SkillFlowNodeMetric nodeMetric);

    List<Long> selectMetricIdsByNodeId(@Param("nodeId") Long nodeId);

    void insertTrigger(SkillFlowTrigger trigger);

    List<SkillFlowTrigger> selectTriggersByFlowId(@Param("flowId") Long flowId);

    SkillFlowTrigger selectTriggerByNormalizedKeyword(@Param("normalizedKeyword") String normalizedKeyword);

    void deleteTriggersByFlowId(@Param("flowId") Long flowId);

    void upsertMetricReadiness(SkillMetricReadiness readiness);

    SkillMetricReadiness selectMetricReadiness(@Param("metricId") Long metricId,
                                               @Param("dataDate") LocalDate dataDate);

    List<SkillMetricReadiness> selectReadyMetrics(@Param("metricIds") List<Long> metricIds,
                                                  @Param("dataDate") LocalDate dataDate);

    int insertFlowExecution(SkillFlowExecution execution);

    SkillFlowExecution selectFlowExecutionById(@Param("id") Long id);

    SkillFlowExecution selectFlowExecutionForUpdate(@Param("id") Long id);

    void insertNodeExecution(SkillFlowNodeExecution execution);

    List<SkillFlowNodeExecution> selectNodeExecutions(@Param("flowExecutionId") Long flowExecutionId);

    List<SkillFlowTrigger> selectEnabledTriggers();
    SkillFlowExecution selectActiveExecution(@Param("guardKey") String guardKey);
    SkillFlowExecution selectLatestConversationExecution(@Param("userId") String userId, @Param("conversationId") String conversationId);
    List<SkillFlowExecution> selectExecutions(@Param("status") String status, @Param("createdBy") String createdBy, @Param("userId") String userId);
    List<SkillFlowExecution> selectWaitingExecutions();
    void updateExecution(SkillFlowExecution execution);
    SkillFlowNodeExecution selectNodeExecution(@Param("id") Long id);
    List<SkillFlowNodeExecution> selectRunnableNodes(@Param("now") LocalDateTime now);
    List<SkillFlowNodeExecution> selectExpiredExhaustedNodes(@Param("now") LocalDateTime now);
    /** 服务重启后回收旧 worker 遗留的 RUNNING 节点,立即进入重试队列。 */
    int recoverAbandonedRunningNodes(@Param("owner") String owner, @Param("now") LocalDateTime now);
    int claimNode(@Param("id") Long id, @Param("owner") String owner, @Param("expiresAt") LocalDateTime expiresAt, @Param("now") LocalDateTime now);
    int countActiveRunningNodes(@Param("flowExecutionId") Long flowExecutionId, @Param("now") LocalDateTime now);
    int claimExecutionForSummary(@Param("id") Long id);
    int failRunningAttemptsForNode(@Param("nodeId") Long nodeId, @Param("now") LocalDateTime now);
    void updateNodeExecution(SkillFlowNodeExecution node);
    int completeNodeAttempt(@Param("node") SkillFlowNodeExecution node,
                            @Param("expectedAttempt") int expectedAttempt,
                            @Param("expectedLeaseOwner") String expectedLeaseOwner);
    void insertAttempt(com.agentscopea2a.v2.skillManager.entity.SkillFlowNodeAttempt attempt);
    int updateAttempt(com.agentscopea2a.v2.skillManager.entity.SkillFlowNodeAttempt attempt);
    List<com.agentscopea2a.v2.skillManager.entity.SkillFlowNodeAttempt> selectAttempts(@Param("nodeId") Long nodeId);
    List<com.agentscopea2a.v2.skillManager.entity.SkillFlowNotification> selectNotifications(@Param("executionId") Long executionId);
    void insertNotification(com.agentscopea2a.v2.skillManager.entity.SkillFlowNotification notification);
    void updateNotification(com.agentscopea2a.v2.skillManager.entity.SkillFlowNotification notification);
}
