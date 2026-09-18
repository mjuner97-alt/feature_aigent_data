package com.agentscopea2a.v2.skillManager.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Skill Flow(多 Skill 编排流)定义表实体。
 * 一条流程 = 触发词 + 节点编排(DAG)+ 汇总模板;用户消息命中触发词后按此编排跑长任务。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillFlow {
    private Long id;
    private String code;
    private String name;
    private String description;
    private String taskQuestion;
    private String summaryQuestionTemplate;
    private Boolean enabled;
    /** JSON weekday to time list shared by scheduled long-task triggers. */
    private String scheduleRules;
    private Integer maxParallelism;
    private Boolean notifyEnabled;
    /** 是否公开触发:开启后所有用户的聊天均可命中该流程;仅开发人员可开通,创建人修改流程后自动退出公开。 */
    private Boolean chatPublic;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime deletedAt;
}
