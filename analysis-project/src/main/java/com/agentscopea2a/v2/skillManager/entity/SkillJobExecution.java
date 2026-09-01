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
package com.agentscopea2a.v2.skillManager.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * SkillJob 执行记录实体，对应 skill_job_execution 表。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillJobExecution {
    /** 主键 */
    private Long id;
    /** 关联的 SkillJob ID */
    private Long jobId;
    /** 触发类型: MANUAL(手动) / EXTERNAL(按名外部触发) / METRIC(按指标批量触发) */
    private String triggerType;
    /** 状态: RUNNING / SUCCESS / FAILED */
    private String status;
    /** 本次执行使用的会话 ID */
    private String conversationId;
    /** 实际解析后的输出路径（{date} 已替换） */
    private String resolvedOutputPath;
    /** Complete Markdown source used to render the report; DB fallback for missing files. */
    private String reportMarkdown;
    /** WriteMarkdownTool 是否已成功写入（由 SkillJobScheduler 直接 Java 调用） */
    private Boolean mdFileWritten;
    /** 报告文件是否存在且非空（.html，字段名保留兼容 DB 列 md_file_exists） */
    private Boolean mdFileExists;
    /** 错误信息 */
    private String errorMsg;
    /** 开始时间 */
    private LocalDateTime startedAt;
    /** 完成时间 */
    private LocalDateTime completedAt;
    /** 创建时间 */
    private LocalDateTime createdAt;
    /** 执行中心关联字段（不参与执行记录写入）。 */
    private String jobName;
    private String skillName;
    private String createdBy;
    private String latestNotificationStatus;
    private Integer notificationAttemptCount;
}
