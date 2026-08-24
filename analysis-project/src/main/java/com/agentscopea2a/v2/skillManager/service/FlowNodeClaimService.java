package com.agentscopea2a.v2.skillManager.service;

import com.agentscopea2a.v2.skillManager.entity.FlowExecutionStatus;
import com.agentscopea2a.v2.skillManager.entity.FlowNodeExecutionStatus;
import com.agentscopea2a.v2.skillManager.entity.SkillFlowExecution;
import com.agentscopea2a.v2.skillManager.entity.SkillFlowNodeExecution;
import com.agentscopea2a.v2.skillManager.mapper.SkillFlowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.EnumSet;

/**
 * Skill Flow 节点认领服务:工作线程执行节点前的并发仲裁。
 *
 * <p>独立成 bean(不并入 FlowCoordinator)的原因:claim 依赖
 * {@code selectFlowExecutionForUpdate} 行锁 + {@code claimNode} 条件更新,
 * 必须经由 Spring 事务代理生效;若内联到 Coordinator 里以 this 调用会绕开代理。</p>
 *
 * <p>认领成功需同时满足:节点可运行(QUEUED/RETRY_WAIT/租约过期的 RUNNING,且退避时间已到)、
 * 流程在跑(QUEUED/RUNNING)、流程级并发未超执行快照的 maxParallelismSnapshot。</p>
 */
@Service
public class FlowNodeClaimService {

    /** 允许认领节点的流程状态。 */
    private static final EnumSet<FlowExecutionStatus> RUNNABLE_EXECUTION_STATUSES =
            EnumSet.of(FlowExecutionStatus.QUEUED, FlowExecutionStatus.RUNNING);

    private final SkillFlowMapper mapper;

    public FlowNodeClaimService(SkillFlowMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 尝试认领节点:成功则租约(leaseOwner/leaseExpiresAt)写入本 worker,返回 true;
     * 被其他 worker 抢先或并发超限返回 false。
     */
    @Transactional("gaussTransactionManager")
    public boolean claim(Long nodeId, String owner, LocalDateTime expiresAt, LocalDateTime now) {
        SkillFlowNodeExecution node = mapper.selectNodeExecution(nodeId);
        if (node == null || !runnable(node, now)) {
            return false;
        }
        // 行锁锁流程,保证同一流程的并发计数判断原子
        SkillFlowExecution execution = mapper.selectFlowExecutionForUpdate(node.getFlowExecutionId());
        if (execution == null || !RUNNABLE_EXECUTION_STATUSES.contains(execution.getStatus())) {
            return false;
        }
        int limit = Math.max(1, execution.getMaxParallelismSnapshot());
        if (mapper.countActiveRunningNodes(execution.getId(), now) >= limit) {
            return false;
        }
        return mapper.claimNode(nodeId, owner, expiresAt, now) == 1;
    }

    /** 节点是否可被认领:状态允许 + 租约已释放/过期 + 重试退避时间已到;租约过期的 RUNNING 重认领还受尝试次数上限约束。 */
    private boolean runnable(SkillFlowNodeExecution node, LocalDateTime now) {
        boolean eligibleStatus = node.getStatus() == FlowNodeExecutionStatus.QUEUED
                || node.getStatus() == FlowNodeExecutionStatus.RETRY_WAIT
                || (node.getStatus() == FlowNodeExecutionStatus.RUNNING
                    && node.getAttemptCount() != null && node.getMaxAttempts() != null
                    && node.getAttemptCount() < node.getMaxAttempts());
        boolean leaseExpired = node.getLeaseExpiresAt() == null || node.getLeaseExpiresAt().isBefore(now);
        boolean retryDue = node.getNextRunAt() == null || !node.getNextRunAt().isAfter(now);
        return eligibleStatus && leaseExpired && retryDue;
    }
}
