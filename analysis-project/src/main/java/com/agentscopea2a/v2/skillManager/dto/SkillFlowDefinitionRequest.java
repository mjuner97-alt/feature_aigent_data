package com.agentscopea2a.v2.skillManager.dto;

import java.util.List;

/**
 * 创建 / 更新 Skill Flow 的请求体。
 * <p>启用(enabled=true)前会做完整性校验:模板变量合法、skill/指标可用、触发词不冲突。
 * 节点全并行执行,无依赖关系;sortOrder 决定结果拼接顺序。</p>
 */
public record SkillFlowDefinitionRequest(
        String code,
        String name,
        String description,
        String taskQuestion,
        String summaryQuestionTemplate,
        Boolean enabled,
        Integer maxParallelism,
        Boolean notifyEnabled,
        List<Trigger> triggers,
        List<Node> nodes) {

    public SkillFlowDefinitionRequest {
        triggers = triggers == null ? List.of() : List.copyOf(triggers);
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
    }

    /** 触发词配置。 */
    public record Trigger(String keyword, Integer priority, Boolean enabled) {}

    /**
     * 节点配置:questionTemplate 支持变量占位,
     * metricIds 为就绪门槛指标,sortOrder 决定结果拼接顺序。
     */
    public record Node(
            String nodeKey,
            Long skillId,
            String questionTemplate,
            List<Long> metricIds,
            Boolean required,
            Integer maxAttempts,
            Integer sortOrder) {

        public Node {
            metricIds = metricIds == null ? List.of() : List.copyOf(metricIds);
        }
    }
}
