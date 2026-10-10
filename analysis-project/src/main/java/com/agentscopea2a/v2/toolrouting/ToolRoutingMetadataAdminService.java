package com.agentscopea2a.v2.toolrouting;

import org.springframework.stereotype.Service;
import java.util.List;
import java.util.regex.Pattern;

/** Validates management input before storing the discoverability summary. */
@Service
public class ToolRoutingMetadataAdminService {

    private static final Pattern TAG_SEPARATOR = Pattern.compile("[,，、\\r\\n]+");
    private final ToolRoutingMetadataRepository repository;
    private final com.agentscopea2a.v2.governance.SkillToolOverlapService overlapService;
    private final com.agentscopea2a.v2.governance.ToolToolOverlapService toolToolOverlapService;
    private final com.agentscopea2a.mapper.gauss.SqlRegistryMapper sqlRegistryMapper;
    private final com.agentscopea2a.mapper.gauss.ScriptRegistryMapper scriptRegistryMapper;
    private final com.agentscopea2a.v2.auth.service.AdminRoleService adminRoleService;
    public ToolRoutingMetadataAdminService(
            ToolRoutingMetadataRepository repository,
            com.agentscopea2a.v2.governance.SkillToolOverlapService overlapService,
            com.agentscopea2a.mapper.gauss.SqlRegistryMapper sqlRegistryMapper,
            com.agentscopea2a.mapper.gauss.ScriptRegistryMapper scriptRegistryMapper,
            com.agentscopea2a.v2.auth.service.AdminRoleService adminRoleService) {
        this(repository, overlapService, null, sqlRegistryMapper, scriptRegistryMapper, adminRoleService);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ToolRoutingMetadataAdminService(
            ToolRoutingMetadataRepository repository,
            com.agentscopea2a.v2.governance.SkillToolOverlapService overlapService,
            com.agentscopea2a.v2.governance.ToolToolOverlapService toolToolOverlapService,
            com.agentscopea2a.mapper.gauss.SqlRegistryMapper sqlRegistryMapper,
            com.agentscopea2a.mapper.gauss.ScriptRegistryMapper scriptRegistryMapper,
            com.agentscopea2a.v2.auth.service.AdminRoleService adminRoleService) {
        this.repository = repository;
        this.overlapService = overlapService;
        this.toolToolOverlapService = toolToolOverlapService;
        this.sqlRegistryMapper = sqlRegistryMapper;
        this.scriptRegistryMapper = scriptRegistryMapper;
        this.adminRoleService = adminRoleService;
    }

    public ToolRoutingMetadata save(String toolId, ToolRoutingMetadataInput input, String userId) {
        ToolRoutingMetadata metadata = buildMetadata(toolId, input);
        assertOwner(metadata, userId);
        assertNotHighOverlap(metadata);
        ToolRoutingMetadata saved = saveMetadata(metadata);
        // 启用互斥: 同 toolId 的 (tool_id, tool_type) 多行各自有独立开关, 但同一时刻至多一个
        // 类型启用 (路由目录单条, Agent 按 toolId 寻址)——启用一个类型时自动停用其余类型
        if (metadata.enabled() && repository.disableOtherTypes(metadata.toolId(), metadata.toolType().name()) > 0) {
            if (overlapService != null) overlapService.invalidate();
            if (toolToolOverlapService != null) toolToolOverlapService.invalidate();
        }
        return saved;
    }

    /**
     * HIGH 重叠 (同候选集内四层无差异) 的工具禁止通过管理页启用——启用了 Agent 叶子选型必然瞎选。
     * 停用与元数据修改不拦 (那是整改动作); 绕过页面直改 DB 的由启动审计 (block-tool-overlap) 兜底。
     */
    private void assertNotHighOverlap(ToolRoutingMetadata metadata) {
        if (!metadata.enabled() || toolToolOverlapService == null) {
            return;
        }
        for (com.agentscopea2a.v2.governance.ToolToolOverlapView view : toolToolOverlapService.report().items()) {
            if (!"HIGH".equals(view.level())) {
                continue;
            }
            // 同 toolId 跨类型重复注册: 只能改注册表 (重命名/退役) 整改, 元数据开关处置不了, 不拦启用 (否则死锁)
            if (view.toolIdA().equals(view.toolIdB())) {
                continue;
            }
            // 只拦与"已启用"工具的重叠: 对侧未启用不构成选型风险 (先启用的一方保留,
            // 即"仅可启用其中一个"); 想换保留侧需先停用当前启用的一方
            if (metadata.toolId().equals(view.toolIdA()) && view.enabledB()) {
                throw new IllegalStateException("ToolOverlapBlocked: 与工具 " + view.toolIdB()
                        + " 有重复，同一候选集内至多启用一个；请先停用对方，或在重叠检测页区分描述或标签后再启用");
            }
            if (metadata.toolId().equals(view.toolIdB()) && view.enabledA()) {
                throw new IllegalStateException("ToolOverlapBlocked: 与工具 " + view.toolIdA()
                        + " 有重复，同一候选集内至多启用一个；请先停用对方，或在重叠检测页区分描述或标签后再启用");
            }
        }
    }

    private void assertOwner(ToolRoutingMetadata metadata, String userId) {
        if (!adminRoleService.canEditConfig(userId)) {
            throw new IllegalStateException("ResourceAccessDenied");
        }
        if (adminRoleService.isAdminUserId(userId)) {
            return;
        }
        String owner = switch (metadata.toolType()) {
            case SQL -> {
                var entry = sqlRegistryMapper.selectBySqlId(metadata.toolId());
                yield entry == null ? null : entry.getCreatedBy();
            }
            case SCRIPT -> {
                var entry = scriptRegistryMapper.selectByScriptId(metadata.toolId());
                yield entry == null ? null : entry.getCreatedBy();
            }
            case API -> null;
        };
        if (owner == null || owner.isBlank() || userId == null || userId.isBlank()
                || !owner.trim().equals(userId.trim())) {
            throw new IllegalStateException("ResourceAccessDenied");
        }
    }

    private ToolRoutingMetadata buildMetadata(String toolId, ToolRoutingMetadataInput input) {
        if (toolId == null || toolId.isBlank()) {
            throw new IllegalArgumentException("ToolIdRequired");
        }
        if (input == null || input.toolType() == null) {
            throw new IllegalArgumentException("ToolRoutingMetadataRequired");
        }
        if (input.priority() < -1000 || input.priority() > 1000) {
            throw new IllegalArgumentException("PriorityOutOfRange: -1000..1000");
        }
        return new ToolRoutingMetadata(toolId.trim(), input.toolType(),
                cleanDescription(input.description()),
                cleanTags(input.topicTags(), true, "TopicTagsRequired"),
                cleanTags(input.metricTags(), true, "MetricTagsRequired"),
                cleanTags(input.dimensionTags(), false, ""), input.priority(), input.enabled(), null);
    }

    private ToolRoutingMetadata saveMetadata(ToolRoutingMetadata metadata) {
        if (!repository.upsert(metadata)) {
            throw new IllegalStateException("ToolRoutingMetadataSaveFailed");
        }
        if (overlapService != null) overlapService.invalidate();
        if (toolToolOverlapService != null) toolToolOverlapService.invalidate();
        return metadata;
    }

    public List<ToolRoutingMetadata> list() {
        return repository.findAll();
    }

    public ToolRoutingMetadata get(String toolId) {
        return repository.findByToolId(toolId)
                .orElseThrow(() -> new IllegalArgumentException("ToolRoutingMetadataNotFound: " + toolId));
    }

    private static String cleanDescription(String value) {
        String result = value == null ? "" : value.trim();
        if (result.length() > 3000) {
            throw new IllegalArgumentException("DescriptionTooLong: max 3000");
        }
        return result;
    }

    private static List<String> cleanTags(List<String> values, boolean required, String requiredMessage) {
        List<String> tags = (values == null ? List.<String>of() : values).stream()
                .filter(value -> value != null).flatMap(TAG_SEPARATOR::splitAsStream)
                .map(String::trim).filter(value -> !value.isEmpty()).distinct()
                .peek(value -> {
                    if (value.length() > 64) {
                        throw new IllegalArgumentException("TagTooLong: max 64");
                    }
                }).toList();
        if (tags.size() > 30) {
            throw new IllegalArgumentException("TooManyTags: max 30");
        }
        if (required && tags.isEmpty()) {
            throw new IllegalArgumentException(requiredMessage);
        }
        return tags;
    }
}
