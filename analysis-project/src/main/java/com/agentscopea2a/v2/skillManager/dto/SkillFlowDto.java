package com.agentscopea2a.v2.skillManager.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Skill Flow(多 Skill 编排流)完整定义的查询返回 DTO。
 * <p>包含触发词列表({@link Trigger})和编排节点列表({@link Node}),两者均随流程一并返回。</p>
 */
public record SkillFlowDto(
        Long id,
        String code,
        String name,
        String description,
        String taskQuestion,
        String summaryQuestionTemplate,
        Boolean enabled,
        String scheduleRules,
        Integer maxParallelism,
        Boolean notifyEnabled,
        /** 是否公开触发(由开发人员开通,创建人修改后自动退出公开);前端据此展示公开标识与编辑警告。 */
        Boolean chatPublic,
        List<Trigger> triggers,
        List<Node> nodes,
        /** 报告大纲(解析后的 JSON 对象);null=未配置,报告走按节点顺序拼接的兼容逻辑。 */
        Object reportOutline,
        String createdBy,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        Boolean deleted) {

    /** 触发词:用户消息命中 keyword 后该流程进入长任务路由。 */
    public record Trigger(Long id, String keyword, Integer priority, Boolean enabled) {}

    /**
     * 编排节点:一个节点绑定一个 skill,声明就绪门槛指标(metricIds);
     * 节点全并行执行,sortOrder 决定结果拼接顺序。
     */
    public record Node(
            Long id,
            String nodeKey,
            String nodeName,
            String nodeType,
            Long skillId,
            String scriptId,
            String scriptParamsJson,
            String skillName,
            String questionTemplate,
            List<Long> metricIds,
            List<String> metricNames,
            Boolean required,
            Integer maxAttempts,
            Integer sortOrder) {}
}
