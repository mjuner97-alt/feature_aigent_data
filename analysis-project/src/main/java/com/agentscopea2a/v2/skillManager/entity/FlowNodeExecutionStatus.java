package com.agentscopea2a.v2.skillManager.entity;

/**
 * 节点执行状态。PENDING(等前置)-> QUEUED -> RUNNING ->(失败可 RETRY_WAIT 退避重试)-> 终态;
 * 前置失败会连带 BLOCKED;terminal() 为 true 的状态不再变化。
 */
public enum FlowNodeExecutionStatus {
    PENDING,
    QUEUED,
    RUNNING,
    RETRY_WAIT,
    SUCCESS,
    FAILED,
    BLOCKED,
    CANCELLED;

    public boolean terminal() {
        return this == SUCCESS || this == FAILED || this == BLOCKED || this == CANCELLED;
    }
}
