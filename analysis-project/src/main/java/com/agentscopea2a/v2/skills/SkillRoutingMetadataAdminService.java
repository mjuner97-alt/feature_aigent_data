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
    private final com.agentscopea2a.v2.governance.SkillSkillOverlapService skillSkillOverlapService;
    private final com.agentscopea2a.v2.auth.service.AdminRoleService adminRoleService;

    public SkillRoutingMetadataAdminService(
            SkillRoutingMetadataRepository repository,
            com.agentscopea2a.v2.governance.SkillToolOverlapService overlapService,
            com.agentscopea2a.v2.governance.SkillSkillOverlapService skillSkillOverlapService,
            com.agentscopea2a.v2.auth.service.AdminRoleService adminRoleService) {
        this.repository = repository;
        this.overlapService = overlapService;
        this.skillSkillOverlapService = skillSkillOverlapService;
        this.adminRoleService = adminRoleService;
    }

    public List<SkillRoutingMetadataView> list(String keyword, Boolean active, int limit, int offset) {
        return list(keyword, active, false, null, limit, offset);
    }

    public List<SkillRoutingMetadataView> list(String keyword, Boolean active, boolean mine, String userId,
                                               int limit, int offset) {
        return repository.findAllWithSkillManage(keyword, active, mine, userId, limit, offset);
    }

    public int count(String keyword, Boolean active, boolean mine, String userId) {
        return repository.countAllWithSkillManage(keyword, active, mine, userId);
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
                creator, input.active(), null,
                cleanTags(input.level1Keywords()), cleanTags(input.level2Keywords()),
                cleanTags(input.level3Keywords()));
        assertNotHighSkillOverlap(metadata);
        if (!repository.upsert(metadata)) throw new IllegalStateException("RoutingConfigSaveFailed");
        invalidateOverlapCache();
        return metadata;
    }

    /**
     * HIGH 重叠 (同一候选集内三层无差异) 的 Skill 禁止通过管理页启用——启用了 Agent
     * 候选集内必然瞎选。停用与元数据修改不拦 (那是整改动作); 绕过页面直改 DB 的由
     * 启动审计 (block-skill-overlap) 兜底。
     */
    private void assertNotHighSkillOverlap(SkillRoutingMetadata metadata) {
        if (!metadata.active() || skillSkillOverlapService == null) {
            return;
        }
        for (com.agentscopea2a.v2.governance.SkillSkillOverlapView view
                : skillSkillOverlapService.report().items()) {
            if (!"HIGH".equals(view.level())) {
                continue;
            }
            // 只拦与"已 active"Skill 的重叠: 对侧未 active 不构成选型风险 (先启用的一方保留,
            // 即"仅可启用其中一个"); 想换保留侧需先停用当前启用的一方
            if (metadata.skillName().equals(view.skillNameA()) && view.activeB()) {
                throw new IllegalStateException("SkillOverlapBlocked: 与 Skill " + view.skillNameB()
                        + " 有重复，同一候选集内至多启用一个；请先停用对方，或在重叠检测页区分关键词、领域标签或描述后再启用");
            }
            if (metadata.skillName().equals(view.skillNameB()) && view.activeA()) {
                throw new IllegalStateException("SkillOverlapBlocked: 与 Skill " + view.skillNameA()
                        + " 有重复，同一候选集内至多启用一个；请先停用对方，或在重叠检测页区分关键词、领域标签或描述后再启用");
            }
        }
    }

    public SkillRoutingMetadataView setActive(String skillName, boolean active, String userId) {
        SkillRoutingMetadataView current = get(skillName);
        SkillRoutingMetadataInput input = new SkillRoutingMetadataInput(current.shortSummary(), current.keywords(),
                current.domainTags(), current.topicTags(), active,
                current.level1Keywords(), current.level2Keywords(), current.level3Keywords());
        save(skillName, input, userId);
        return get(skillName);
    }

    private void assertOwner(String owner, String userId) {
        if (!adminRoleService.canEditConfig(userId)) {
            throw new IllegalStateException("ResourceAccessDenied");
        }
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
        if (skillSkillOverlapService != null) skillSkillOverlapService.invalidate();
    }

    private static String cleanSummary(String value) {
        String result = value == null ? "" : value.trim();
        if (result.length() > 3000) throw new IllegalArgumentException("ShortSummaryTooLong: max 3000");
        return result;
    }

    private static List<String> cleanTags(List<String> values) {
        if (values == null) return List.of();
        return values.stream().filter(v -> v != null).flatMap(SkillRoutingMetadataAdminService::splitAwareOfRegexBraces)
                .map(String::trim).filter(v -> !v.isEmpty()).distinct()
                .peek(v -> { if (v.length() > 128) throw new IllegalArgumentException("TagTooLong: max 128"); })
                .toList();
    }

    /**
     * 括号感知切分: 正则量词 {@code {1,3}} 内的逗号不作为分隔符, 否则
     * {@code 未闭环.{1,3}问题} 会被切成两段破坏正则语义。
     */
    private static java.util.stream.Stream<String> splitAwareOfRegexBraces(String value) {
        StringBuilder buffer = new StringBuilder();
        java.util.List<String> parts = new java.util.ArrayList<>();
        int braceDepth = 0;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '{') braceDepth++;
            if (ch == '}') braceDepth = Math.max(0, braceDepth - 1);
            if (braceDepth == 0 && TAG_SEPARATOR.matcher(String.valueOf(ch)).matches()) {
                if (buffer.length() > 0) parts.add(buffer.toString());
                buffer.setLength(0);
            } else {
                buffer.append(ch);
            }
        }
        if (buffer.length() > 0) parts.add(buffer.toString());
        return parts.stream();
    }
}
