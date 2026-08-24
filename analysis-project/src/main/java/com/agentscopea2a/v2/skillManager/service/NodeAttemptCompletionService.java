package com.agentscopea2a.v2.skillManager.service;

import com.agentscopea2a.v2.skillManager.entity.FlowNodeExecutionStatus;
import com.agentscopea2a.v2.skillManager.entity.SkillFlowNodeExecution;
import com.agentscopea2a.v2.skillManager.mapper.SkillFlowMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/** Applies a node result only while the originating attempt still owns the node lease. */
@Service
public class NodeAttemptCompletionService {

    private final SkillFlowMapper mapper;

    public NodeAttemptCompletionService(SkillFlowMapper mapper) {
        this.mapper = mapper;
    }

    public boolean completeSuccess(SkillFlowNodeExecution node, int attempt, String leaseOwner,
                                   String resultJson, LocalDateTime completedAt) {
        node.setStatus(FlowNodeExecutionStatus.SUCCESS);
        node.setResultJson(resultJson);
        node.setErrorCode(null);
        node.setErrorMessage(null);
        node.setNextRunAt(null);
        node.setCompletedAt(completedAt);
        clearLease(node);
        return mapper.completeNodeAttempt(node, attempt, leaseOwner) == 1;
    }

    public boolean completeFailure(SkillFlowNodeExecution node, int attempt, String leaseOwner,
                                   boolean retryable, String errorCode, String errorMessage,
                                   LocalDateTime completedAt) {
        node.setErrorCode(errorCode);
        node.setErrorMessage(errorMessage);
        node.setStatus(retryable ? FlowNodeExecutionStatus.RETRY_WAIT : FlowNodeExecutionStatus.FAILED);
        node.setCompletedAt(retryable ? null : completedAt);
        clearLease(node);
        return mapper.completeNodeAttempt(node, attempt, leaseOwner) == 1;
    }

    private static void clearLease(SkillFlowNodeExecution node) {
        node.setLeaseOwner(null);
        node.setLeaseExpiresAt(null);
    }
}
