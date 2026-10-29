package com.agentscopea2a.v2.skills;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.Supplier;

/**
 * 检索旁路判定（docs/history/skill-retrieval-logic.md §8.3）。
 *
 * <p>判定顺序（拍板④ 旁路让位）：任一启用旁路词命中问题（selector 词条语义=拍板⑤，
 * 复用 {@link SkillTermMatcher}）→ 检查问题是否显式点名任一 active skill 名（contains，
 * 与 selector explicit 同语义）→ 点名则不旁路 → 检查问题是否命中任一 active skill 的
 * domain_tags（2026/10/28，docs/202610/domain_tags命中优先于直接执行重排序方案.md §3.1）
 * → 命中则不旁路（漏斗第 2 层 domain 命中走 LLM 领域 skill，高于第 4 层旁路词）→
 * 否则旁路（skill 检索置空，LLM 直接走工具层）。消费语义分层（2026/10/27 重排序，
 * docs/202610/直接执行优先于旁路词重排序方案.md）：目录侧
 * {@code SkillVectorIndexVisibilityFilter.filter()} 无条件旁路（domain/点名豁免由本类判定承担）；
 * 直接执行侧 {@code DirectSkillExecutor.plan()} 层级轨 top-1（L1/L2/L3 AND 命中）让位旁路 ——
 * 旁路词命中时仅存量轨/空候选才放弃短路。本类判定语义仍是单一判定源。
 *
 * <p>容错契约同 dimension 字典：字典库读失败/无行 → 不旁路（返回 false），不阻塞主链路。
 */
public class SkillBypassKeywordService {

    private static final Logger log = LoggerFactory.getLogger(SkillBypassKeywordService.class);

    private final Supplier<List<SkillBypassKeyword>> keywordSupplier;
    private final Supplier<List<SkillRoutingMetadata>> activeMetadataSupplier;

    public SkillBypassKeywordService(SkillBypassKeywordRepository repository,
                                     SkillRoutingMetadataRepository metadataRepository) {
        this(repository::findAllEnabled, metadataRepository::findActive);
    }

    /** Supplier 注入口（单测免 DB; V2SkillConfig 走仓储构造）。 */
    public SkillBypassKeywordService(Supplier<List<SkillBypassKeyword>> keywordSupplier,
                                     Supplier<List<SkillRoutingMetadata>> activeMetadataSupplier) {
        this.keywordSupplier = keywordSupplier;
        this.activeMetadataSupplier = activeMetadataSupplier;
    }

    /**
     * 纯判定无副作用：true = 本轮 skill 检索旁路（目录置空 + 直接执行短路回退 LLM）。
     * 字典/元数据读失败 → 不旁路（warn 日志），不阻塞主链路。
     */
    public boolean bypassed(String question) {
        try {
            return bypassedInternal(question);
        } catch (RuntimeException e) {
            log.warn("SkillBypassKeywordService: bypass check failed, NOT bypassing: {}", e.getMessage());
            return false;
        }
    }

    private boolean bypassedInternal(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }
        List<SkillBypassKeyword> keywords = keywordSupplier.get();
        if (keywords == null || keywords.isEmpty()) {
            return false;
        }
        String normalized = SkillTermMatcher.normalize(question);
        boolean hit = false;
        for (SkillBypassKeyword keyword : keywords) {
            if (keyword.valid() && SkillTermMatcher.matches(normalized, keyword.keyword())) {
                hit = true;
                break;
            }
        }
        if (!hit) {
            return false;
        }
        List<SkillRoutingMetadata> active = activeMetadataSupplier.get();
        if (active == null) {
            active = List.of();
        }
        for (SkillRoutingMetadata entry : active) {
            if (!entry.active()) {
                continue;
            }
            if (SkillTermMatcher.containsName(normalized, entry.skillName())) {
                log.info("SkillBypassKeywordService: bypass word hit but question explicitly names skill '{}', not bypassing",
                        entry.skillName());
                return false;
            }
        }
        // domain 让位 (2026/10/28, docs/202610/domain_tags命中优先于直接执行重排序方案.md §3.1):
        // 问题命中任一 active skill 的 domain_tags → 走该领域 skill (漏斗第 2 层), 不旁路
        // (漏斗: domain 命中 > 旁路词), 否则「分析一下例会情况」被旁路置空目录, domain 层名存实亡。
        // 归一化与 filter.matchedDomains / selector.matchedTags 同语义 (改一边必须同步另一边)。
        for (SkillRoutingMetadata entry : active) {
            if (!entry.active() || entry.domainTags() == null) {
                continue;
            }
            for (String tag : entry.domainTags()) {
                if (tag != null && !tag.isBlank()
                        && SkillTermMatcher.containsName(normalized, tag)) {
                    log.info("SkillBypassKeywordService: bypass word hit but question hits domain tag '{}', not bypassing",
                            tag);
                    return false;
                }
            }
        }
        log.info("SkillBypassKeywordService: question bypassed by keyword hit, skill retrieval disabled this turn");
        return true;
    }
}
