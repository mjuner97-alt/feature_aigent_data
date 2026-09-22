package com.agentscopea2a.v2.skills;

import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** Application service for the routing configuration page. */
@Service
public class SkillRoutingMetadataAdminService {
    private static final Pattern TAG_SEPARATOR = Pattern.compile("[,，、\\r\\n]+");

    private final SkillRoutingMetadataRepository repository;
    private final com.agentscopea2a.v2.governance.SkillToolOverlapService overlapService;
    private final com.agentscopea2a.v2.auth.service.AdminRoleService adminRoleService;

    public SkillRoutingMetadataAdminService(
            SkillRoutingMetadataRepository repository,
            com.agentscopea2a.v2.governance.SkillToolOverlapService overlapService,
            com.agentscopea2a.v2.auth.service.AdminRoleService adminRoleService) {
        this.repository = repository;
        this.overlapService = overlapService;
        this.adminRoleService = adminRoleService;
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

    public SkillRoutingMetadata save(String skillName, SkillRoutingMetadataInput input, String userId) {
        if (skillName == null || skillName.isBlank() || !repository.skillExists(skillName)) {
            throw new IllegalArgumentException("SkillNotFound: " + skillName);
        }
        if (input == null) throw new IllegalArgumentException("RoutingConfigRequired");
        String summary = cleanSummary(input.shortSummary());
        String creator = repository.creatorForSkill(skillName);
        assertOwner(creator, userId);
        SkillRoutingMetadata metadata = new SkillRoutingMetadata(skillName, summary,
                cleanTags(input.keywords()), cleanTags(input.domainTags()), cleanTags(input.topicTags()),
                creator, input.active(), null);
        if (!repository.upsert(metadata)) throw new IllegalStateException("RoutingConfigSaveFailed");
        invalidateOverlapCache();
        return metadata;
    }

    public SkillRoutingMetadataView setActive(String skillName, boolean active, String userId) {
        SkillRoutingMetadataView current = get(skillName);
        SkillRoutingMetadataInput input = new SkillRoutingMetadataInput(current.shortSummary(), current.keywords(),
                current.domainTags(), current.topicTags(), active);
        save(skillName, input, userId);
        return get(skillName);
    }

    private void assertOwner(String owner, String userId) {
        if (adminRoleService.isAdminUserId(userId)) {
            return;
        }
        if (owner == null || owner.isBlank() || userId == null || userId.isBlank()
                || !owner.trim().equals(userId.trim())) {
            throw new IllegalStateException("ResourceAccessDenied");
        }
    }

    private void invalidateOverlapCache() {
        if (overlapService != null) overlapService.invalidate();
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
