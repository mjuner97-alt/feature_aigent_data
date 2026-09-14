package com.agentscopea2a.v2.skillManager.dto;

import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;

/**
 * 技能评审提交 - 完整的候选配置(非增量 patch,不含被继承/省略的关系)。
 *
 * <p>构造时对关联集合统一做"规范化 + 排序 + 去重",保证序列化结果稳定(哈希一致);
 * 同一发布目标若配置了多个不同的显示名则直接拒绝。
 */
public record SkillReviewSubmission(
        String name, String description, String content, String category, String tags, String visibility,
        List<AttachmentReference> attachments, RoutingMetadata routing, List<String> dimensionTags,
        List<String> toolIds, List<VisibleGrant> visibleGrants, List<PublishTarget> publishTargets) {
    /** 构造时规范化并排序去重全部关联集合,同时校验发布目标名称冲突。 */
    public SkillReviewSubmission {
        attachments = ordered(attachments, Comparator.comparing(AttachmentReference::fileId)
                .thenComparing(AttachmentReference::referenceType));
        dimensionTags = identifiers(dimensionTags);
        toolIds = identifiers(toolIds);
        visibleGrants = ordered(visibleGrants, Comparator.comparing(VisibleGrant::grantType)
                .thenComparing(VisibleGrant::targetId));
        publishTargets = ordered(publishTargets, Comparator.comparing(PublishTarget::targetType)
                .thenComparing(PublishTarget::targetId).thenComparing(PublishTarget::targetName));
        for (int i = 1; i < publishTargets.size(); i++) {
            var previous = publishTargets.get(i - 1);
            var next = publishTargets.get(i);
            if (previous.targetType().equals(next.targetType()) && previous.targetId().equals(next.targetId())) {
                throw new IllegalArgumentException("Conflicting labels for one publish target");
            }
        }
    }

    /** 附件引用 - 仅记录附件 ID 与引用类型,附件实体内容在快照阶段冻结。 */
    public record AttachmentReference(Long fileId, String referenceType) {
        /** 校验附件 ID 合法,并规范化引用类型。 */
        public AttachmentReference {
            if (fileId == null || fileId <= 0) throw new IllegalArgumentException("Invalid attachment ID");
            referenceType = identifier(referenceType);
        }
    }
    /** 可见性授权 - 私有技能的授权对象(如指定用户/组/部门可见)。 */
    public record VisibleGrant(String grantType, String targetId) {
        /** 规范化授权类型与目标 ID。 */
        public VisibleGrant {
            grantType = identifier(grantType);
            targetId = identifier(targetId);
        }
    }
    /** 发布目标 - 技能要发布到的目标(如公司/部门/组/用户)。 */
    public record PublishTarget(String targetType, String targetId, String targetName) {
        /** 规范化发布目标各字段。 */
        public PublishTarget {
            targetType = identifier(targetType);
            targetId = identifier(targetId);
            targetName = identifier(targetName);
        }
    }
    /** 路由元数据 - 技能接入 AI 路由时的摘要、关键词与各维度标签。 */
    public record RoutingMetadata(String shortSummary, List<String> keywords, List<String> domainTags,
                                  List<String> topicTags, List<String> metricTags, int priority, boolean active) {
        /** 规范化各标签集合。 */
        public RoutingMetadata {
            keywords = identifiers(keywords);
            domainTags = identifiers(domainTags);
            topicTags = identifiers(topicTags);
            metricTags = identifiers(metricTags);
        }
    }

    /** 规范化单个关系标识:非空且去首尾空白。 */
    private static String identifier(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Blank relation identifier");
        return value.strip();
    }

    /** 规范化标识集合(逐项 identifier + 排序去重);null 视为空集合。 */
    private static List<String> identifiers(List<String> values) {
        if (values == null) return List.of();
        return ordered(values.stream().map(SkillReviewSubmission::identifier).toList(), Comparator.naturalOrder());
    }

    /** 通用排序去重工具:null 视为空集合,集合内不允许出现 null 项。 */
    private static <T> List<T> ordered(List<T> values, Comparator<? super T> comparator) {
        if (values == null) return List.of();
        TreeSet<T> result = new TreeSet<>(comparator);
        for (T value : values) {
            if (value == null) throw new IllegalArgumentException("Null relation entry");
            result.add(value);
        }
        return List.copyOf(result);
    }
}
