/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.agentscopea2a.v2.skillManager.dto;

import com.agentscopea2a.v2.skillManager.entity.SkillJobExecution;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.LocalDateTime;

/**
 * SkillJob 执行记录响应 DTO。
 * resolvedOutputPath 加 @JsonIgnore 不序列化给前端（磁盘路径，仅后端下载时按 baseDir + createdBy 拼解析）；
 * 前端判"有没有文件"用 mdFileExists。
 */
public record SkillJobExecutionDto(
        Long id,
        Long jobId,
        String triggerType,
        String status,
        String conversationId,
        @JsonIgnore String resolvedOutputPath,
        Boolean mdFileWritten,
        Boolean mdFileExists,
        String errorMsg,
        LocalDateTime startedAt,
        LocalDateTime completedAt,
        LocalDateTime createdAt
) {
    public static SkillJobExecutionDto of(SkillJobExecution exec) {
        return new SkillJobExecutionDto(
                exec.getId(), exec.getJobId(), exec.getTriggerType(),
                exec.getStatus(), exec.getConversationId(),
                exec.getResolvedOutputPath(),
                exec.getMdFileWritten(), exec.getMdFileExists(),
                exec.getErrorMsg(), exec.getStartedAt(),
                exec.getCompletedAt(), exec.getCreatedAt());
    }
}
