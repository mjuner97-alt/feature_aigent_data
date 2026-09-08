package com.agentscopea2a.v2.skills;

import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** Application service for the routing configuration page. */
@Service
public class SkillRoutingMetadataAdminService {
    private static final Pattern TAG_SEPARATOR = Pattern.compile("[,，、\\r\\n]+");

    private final SkillRoutingMetadataRepository repository;

    public SkillRoutingMetadataAdminService(SkillRoutingMetadataRepository repository) {
        this.repository = repository;
    }

    public List<SkillRoutingMetadataView> list(String keyword, Boolean active, int limit, int offset) {
        return list(keyword, active, false, null, limit, offset);
    }

    public List<SkillRoutingMetadataView> list(String keyword, Boolean active, boolean mine, String userId,
                                               int limit, int offset) {
        return repository.findAllWithSkillManage(keyword, active, mine, userId, limit, offset);
    }

    public SkillRoutingMetadataView get(String skillName) {
        return repository.findOneWithSkillManage(skillName)
                .orElseThrow(() -> new IllegalArgumentException("SkillNotFound: " + skillName));
    }

    public SkillRoutingMetadata save(String skillName, SkillRoutingMetadataInput input) {
        return save(skillName, input, "");
    }

    public SkillRoutingMetadata save(String skillName, SkillRoutingMetadataInput input, String ignoredUserId) {
        if (skillName == null || skillName.isBlank() || !repository.skillExists(skillName)) {
            throw new IllegalArgumentException("SkillNotFound: " + skillName);
        }
        if (input == null) throw new IllegalArgumentException("RoutingConfigRequired");
        if (input.priority() < -1000 || input.priority() > 1000) {
            throw new IllegalArgumentException("PriorityOutOfRange: -1000..1000");
        }
        String summary = cleanSummary(input.shortSummary());
        String creator = repository.creatorForSkill(skillName);
        SkillRoutingMetadata metadata = new SkillRoutingMetadata(skillName, summary,
                cleanTags(input.keywords()), cleanTags(input.domainTags()), cleanTags(input.topicTags()),
                cleanTags(input.metricTags()), creator,
                input.priority(), input.active(), null);
        if (!repository.upsert(metadata)) throw new IllegalStateException("RoutingConfigSaveFailed");
        return metadata;
    }

    public SkillRoutingMetadataView setActive(String skillName, boolean active) {
        SkillRoutingMetadataView current = get(skillName);
        SkillRoutingMetadataInput input = new SkillRoutingMetadataInput(current.shortSummary(), current.keywords(),
                current.domainTags(), current.topicTags(), current.metricTags(), current.priority(), active);
        save(skillName, input, null);
        return get(skillName);
    }

    private static String cleanSummary(String value) {
        String result = value == null ? "" : value.trim();
        if (result.length() > 3000) throw new IllegalArgumentException("ShortSummaryTooLong: max 3000");
        return result;
    }

    private static List<String> cleanTags(List<String> values) {
        if (values == null) return List.of();
        return values.stream().filter(v -> v != null).flatMap(v -> TAG_SEPARATOR.splitAsStream(v))
                .map(String::trim).filter(v -> !v.isEmpty()).distinct()
                .peek(v -> { if (v.length() > 128) throw new IllegalArgumentException("TagTooLong: max 128"); })
                .toList();
    }
}
