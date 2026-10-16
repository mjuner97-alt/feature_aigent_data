package com.agentscopea2a.v2.skillManager.dto;

import java.util.List;

/**
 * 创建 / 更新 Skill Flow 的请求体。
 * <p>启用(enabled=true)前会做完整性校验:模板变量合法、skill/指标可用、触发词不冲突。
 * 节点全并行执行,无依赖关系;sortOrder 决定兼容报告的拼接顺序,reportOutline 决定自定义报告的章节顺序。</p>
 */
public record SkillFlowDefinitionRequest(
        String code,
        String name,
        String description,
        String taskQuestion,
        String summaryQuestionTemplate,
        Boolean enabled,
        String scheduleRules,
        Integer maxParallelism,
        Boolean notifyEnabled,
        List<Trigger> triggers,
        List<Node> nodes,
        ReportOutline reportOutline,
        NotifySettingsUpdateRequest notifySettings,
        String notifyContactName,
        String notifyContactPhone,
        String notifyBodyTemplate,
        /** 可执行人 userId 列表;null = 不修改保持原名单,非 null(含空列表) = 全量替换,空 = 仅创建人。 */
        List<String> executors) {

    public SkillFlowDefinitionRequest {
        notifyContactName = com.agentscopea2a.v2.skillManager.notification.NotificationContact.trimToNull(notifyContactName);
        notifyContactPhone = com.agentscopea2a.v2.skillManager.notification.NotificationContact.trimToNull(notifyContactPhone);
        notifyBodyTemplate = com.agentscopea2a.v2.skillManager.notification.NotificationContact.trimToNull(notifyBodyTemplate);
        if (notifyBodyTemplate != null && notifyBodyTemplate.length() > 2000) {
            throw new IllegalStateException("FlowValidationFailed: 通知邮件正文模板超长(上限 2000 字符)");
        }
        triggers = triggers == null ? List.of() : List.copyOf(triggers);
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        executors = executors == null ? null : executors.stream().filter(java.util.Objects::nonNull).toList();
    }

    /** Compatibility for definition callers predating custom mail contacts. */
    public SkillFlowDefinitionRequest(String code, String name, String description, String taskQuestion,
            String summaryQuestionTemplate, Boolean enabled, String scheduleRules, Integer maxParallelism,
            Boolean notifyEnabled, List<Trigger> triggers, List<Node> nodes, ReportOutline reportOutline,
            NotifySettingsUpdateRequest notifySettings) {
        this(code, name, description, taskQuestion, summaryQuestionTemplate, enabled, scheduleRules,
                maxParallelism, notifyEnabled, triggers, nodes, reportOutline, notifySettings, null, null, null, null);
    }

    /** Compatibility for definition callers predating the mail body template. */
    public SkillFlowDefinitionRequest(String code, String name, String description, String taskQuestion,
            String summaryQuestionTemplate, Boolean enabled, String scheduleRules, Integer maxParallelism,
            Boolean notifyEnabled, List<Trigger> triggers, List<Node> nodes, ReportOutline reportOutline,
            NotifySettingsUpdateRequest notifySettings, String notifyContactName, String notifyContactPhone) {
        this(code, name, description, taskQuestion, summaryQuestionTemplate, enabled, scheduleRules,
                maxParallelism, notifyEnabled, triggers, nodes, reportOutline, notifySettings,
                notifyContactName, notifyContactPhone, null, null);
    }

    /** 兼容增加可执行人之前的完整定义请求；省略名单时不修改已有授权。 */
    public SkillFlowDefinitionRequest(String code, String name, String description, String taskQuestion,
            String summaryQuestionTemplate, Boolean enabled, String scheduleRules, Integer maxParallelism,
            Boolean notifyEnabled, List<Trigger> triggers, List<Node> nodes, ReportOutline reportOutline,
            NotifySettingsUpdateRequest notifySettings, String notifyContactName, String notifyContactPhone,
            String notifyBodyTemplate) {
        this(code, name, description, taskQuestion, summaryQuestionTemplate, enabled, scheduleRules,
                maxParallelism, notifyEnabled, triggers, nodes, reportOutline, notifySettings,
                notifyContactName, notifyContactPhone, notifyBodyTemplate, null);
    }

    /** 触发词配置。 */
    public record Trigger(String keyword, Integer priority, Boolean enabled) {}

    /**
     * 节点配置:questionTemplate 支持变量占位,
     * metricIds 为就绪门槛指标,sortOrder 决定兼容报告拼接顺序。
     */
    public record Node(
            String nodeKey,
            String nodeName,
            String nodeType,
            Long skillId,
            String scriptId,
            String scriptParamsJson,
            String questionTemplate,
            List<Long> metricIds,
            Boolean metricOverrideConfigured,
            Boolean required,
            Integer maxAttempts,
            Integer sortOrder) {

        public Node {
            metricIds = metricIds == null ? List.of() : List.copyOf(metricIds);
        }
    }

    /**
     * 报告大纲编号配置:各层级取值 chinese(中文序号)/arabic(阿拉伯数字)/none(关闭),
     * null 用缺省(一级 chinese、二三级 arabic)。
     */
    public record Numbering(String level1, String level2, String level3, String level4) {}

    /**
     * 大纲项:id 唯一、title 非空、level 与树位置一致(不能跳级);
     * nodeKeys 为该章节绑定的执行节点列表(按序渲染各自结果,同一章节可挂多个节点)。
     * 兼容旧数据:nodeKeys 为空时回退单个 nodeKey。
     */
    public record ReportOutlineItem(
            String id,
            String title,
            Integer level,
            String nodeKey,
            List<String> nodeKeys,
            List<ReportOutlineItem> children) {

        public ReportOutlineItem {
            nodeKeys = nodeKeys == null || nodeKeys.isEmpty()
                    ? (nodeKey == null || nodeKey.isBlank() ? List.of() : List.of(nodeKey.trim()))
                    : nodeKeys.stream().filter(key -> key != null && !key.isBlank()).map(String::trim).toList();
            children = children == null ? List.of() : List.copyOf(children);
        }
    }

    /** 报告大纲:items 为空视为未配置,走按节点顺序拼接的兼容逻辑。 */
    public record ReportOutline(String title, Numbering numbering, List<ReportOutlineItem> items) {

        public ReportOutline {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }
}
