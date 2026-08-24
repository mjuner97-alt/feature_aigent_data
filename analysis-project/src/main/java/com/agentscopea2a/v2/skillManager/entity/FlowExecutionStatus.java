package com.agentscopea2a.v2.skillManager.entity;

/**
 * 流程执行状态。WAITING_METRICS(等指标)-> QUEUED -> RUNNING -> SUMMARIZING -> 终态;
 * terminal() 为 true 的状态不再变化。
 */
public enum FlowExecutionStatus {
    WAITING_METRICS,
    QUEUED,
    RUNNING,
    CANCEL_REQUESTED,
    SUMMARIZING,
    SUCCESS,
    PARTIAL_SUCCESS,
    FAILED,
    CANCELLED;

    public boolean terminal() {
        return this == SUCCESS || this == PARTIAL_SUCCESS || this == FAILED || this == CANCELLED;
    }
}
