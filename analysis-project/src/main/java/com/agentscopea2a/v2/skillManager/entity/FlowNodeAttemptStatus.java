package com.agentscopea2a.v2.skillManager.entity;

/**
 * 单次尝试的审计状态(与节点执行状态分开记录,只用于审计展示)。
 */
public enum FlowNodeAttemptStatus {
    RUNNING,
    SUCCESS,
    FAILED,
    CANCELLED
}
