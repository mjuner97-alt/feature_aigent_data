package com.agentscopea2a.agent.memory;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 查询参数容器：按 conversationId 查询 episodic memory 消息时间线。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EpisodicQueryCriteria {

    /** 会话ID，必传 */
    private String conversationId;

    /** 可选，created_at >=（ISO-8601 格式如 2026-07-13T10:00:00） */
    private String from;

    /** 可选，created_at <=（ISO-8601 格式） */
    private String to;

    /** 可选，USER / ASSISTANT / TOOL */
    private String role;

    /** 可选，content 全文搜索关键词 */
    private String q;

    /** 可选，默认 200，上限 1000 */
    @Builder.Default
    private Integer limit = 200;

    /** 可选，默认 0 */
    @Builder.Default
    private Integer offset = 0;
}
