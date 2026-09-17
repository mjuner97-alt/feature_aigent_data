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
    private final com.agentscopea2a.mapper.gauss.SqlRegistryMapper sqlRegistryMapper;
    private final com.agentscopea2a.mapper.gauss.ScriptRegistryMapper scriptRegistryMapper;
    public ToolRoutingMetadataAdminService(
            ToolRoutingMetadataRepository repository,
            com.agentscopea2a.v2.governance.SkillToolOverlapService overlapService,
            com.agentscopea2a.mapper.gauss.SqlRegistryMapper sqlRegistryMapper,
            com.agentscopea2a.mapper.gauss.ScriptRegistryMapper scriptRegistryMapper) {
        this.repository = repository;
        this.overlapService = overlapService;
        this.sqlRegistryMapper = sqlRegistryMapper;
        this.scriptRegistryMapper = scriptRegistryMapper;
    }

    public ToolRoutingMetadata save(String toolId, ToolRoutingMetadataInput input, String userId) {
        ToolRoutingMetadata metadata = buildMetadata(toolId, input);
        assertOwner(metadata, userId);
        return saveMetadata(metadata);
    }

    private void assertOwner(ToolRoutingMetadata metadata, String userId) {
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
