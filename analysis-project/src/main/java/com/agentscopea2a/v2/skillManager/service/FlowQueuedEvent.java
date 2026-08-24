package com.agentscopea2a.v2.skillManager.service;

/** 流程进入执行队列的进程内信号；数据库队列仍是最终事实来源。 */
public record FlowQueuedEvent(Long flowExecutionId) {}
