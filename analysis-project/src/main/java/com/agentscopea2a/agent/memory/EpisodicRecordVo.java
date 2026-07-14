package com.agentscopea2a.agent.memory;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 返回记录：一条 episodic memory 行。
 * toolCallDetails 字段类型为 Object：
 *   - NULL -> null
 *   - 可解析JSON -> JsonNode（响应里展开为JSON对象）
 *   - 不可解析 -> 原始字符串
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EpisodicRecordVo {

    private Long id;

    private String sessionId;

    private String role;

    private String content;

    /** JSON object / raw string / null */
    private Object toolCallDetails;

    private String createdAt;
}
