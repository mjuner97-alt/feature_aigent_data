package com.agentscopea2a.v2.skills;

import com.agentscopea2a.v2.dimension.DimensionAlias;
import com.agentscopea2a.v2.dimension.PersonLedgerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Skill 词条 × 维度实体名 撞车审计（docs/202610/检索关键词与维度值冲突治理方案.md §5）。
 *
 * <p>term 级近似（配置期没有问题文本）：判断词条与实体名（alias 口语列 ∪ standardName 列
 * ∪ 台账真值三列）的静态关系，仅提示不阻断（对齐直接执行重排序方案拍板④定调）。
 * 运行期真判定由 span 屏蔽（{@link DimensionSpanScanner} + selector）兜住。
 *
 * <ul>
 * <li><b>HIGH</b>：词条完全等于某 alias 口语词（歧义源本体）；</li>
 * <li><b>MED</b>：词条是某实体名的子串（contains 误中面，如 风险 ⊂ 风险组）；</li>
 * <li><b>SKIP</b>：词条含正则元字符，无法静态分析（运行期 span 屏蔽按命中位置判定，不受此限）。</li>
 * </ul>
 *
 * <p>消费点：① 启动审计日志（ApplicationRunner，fail-open 不阻断启动）；
 * ② PUT /api/skill-routing 保存响应 warning 字段；③ 旁路词 × 维度口语撞车同表提示
 * （拍板 P5：bypassed() 语义不动，仅提示）。
 */
public class SkillDimensionCollisionService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SkillDimensionCollisionService.class);

    /** 词条不可静态分析的正则元字符集合。 */
    private static final String REGEX_META = "\\^$.|?*+()[]{}";

    private final Supplier<List<SkillRoutingMetadata>> metadataSupplier;
    private final Supplier<List<SkillBypassKeyword>> bypassSupplier;
    private final Supplier<List<DimensionAlias>> aliasSupplier;
    private final Supplier<PersonLedgerRepository> ledgerSupplier;

    public SkillDimensionCollisionService(Supplier<List<SkillRoutingMetadata>> metadataSupplier,
                                          Supplier<List<SkillBypassKeyword>> bypassSupplier,
                                          Supplier<List<DimensionAlias>> aliasSupplier,
                                          Supplier<PersonLedgerRepository> ledgerSupplier) {
        this.metadataSupplier = metadataSupplier;
        this.bypassSupplier = bypassSupplier;
        this.aliasSupplier = aliasSupplier;
        this.ledgerSupplier = ledgerSupplier;
    }

    /** 单条撞车记录。kind = skill(四层词条) | bypass(旁路词)；source = 实体名来源。 */
    public record CollisionRow(String keyword, String level, String entityName,
                               String entitySource, String kind) {}

    /** 全量审计：active skill 四层词条 + 旁路词。词表读失败 → 空报告（fail-open）。 */
    public List<CollisionRow> auditAll() {
        try {
            Map<String, EntityName> entities = entityNames();
            List<CollisionRow> out = new ArrayList<>();
            List<SkillRoutingMetadata> metadata = metadataSupplier.get();
            if (metadata != null) {
                for (SkillRoutingMetadata m : metadata) {
                    if (m == null || !m.active()) continue;
                    auditTerms(m.skillName(), termsOf(m), entities, "skill", out);
                }
            }
            List<SkillBypassKeyword> bypass = bypassSupplier.get();
            if (bypass != null) {
                for (SkillBypassKeyword b : bypass) {
                    if (b == null || !b.valid()) continue;
                    auditTerms("bypass:" + b.keyword(), List.of(b.keyword()), entities, "bypass", out);
                }
            }
            return out;
        } catch (RuntimeException e) {
            log.warn("SkillDimensionCollisionService: audit failed, skipped (fail-open): {}", e.getMessage());
            return List.of();
        }
    }

    /** 单 skill 保存响应用：对四层词条逐个判级。词表读失败 → 空列表（fail-open）。 */
    public List<CollisionRow> auditSkill(String skillName, List<String> keywords,
                                         List<String> l1, List<String> l2, List<String> l3) {
        try {
            Map<String, EntityName> entities = entityNames();
            List<CollisionRow> out = new ArrayList<>();
            List<String> terms = new ArrayList<>();
            if (l1 != null) terms.addAll(l1);
            if (l2 != null) terms.addAll(l2);
            if (l3 != null) terms.addAll(l3);
            if (keywords != null) terms.addAll(keywords);
            auditTerms(skillName, terms, entities, "skill", out);
            return out;
        } catch (RuntimeException e) {
            log.warn("SkillDimensionCollisionService: auditSkill({}) failed, skipped: {}", skillName, e.getMessage());
            return List.of();
        }
    }

    private record EntityName(String name, String source) {}

    private static List<String> termsOf(SkillRoutingMetadata m) {
        List<String> terms = new ArrayList<>();
        if (m.level1Keywords() != null) terms.addAll(m.level1Keywords());
        if (m.level2Keywords() != null) terms.addAll(m.level2Keywords());
        if (m.level3Keywords() != null) terms.addAll(m.level3Keywords());
        if (m.keywords() != null) terms.addAll(m.keywords());
        return terms;
    }

    /** 实体名清单（normalized，去重，保留首来源）。 */
    private Map<String, EntityName> entityNames() {
        Map<String, EntityName> entities = new LinkedHashMap<>();
        List<DimensionAlias> aliases = aliasSupplier.get();
        if (aliases != null) {
            for (DimensionAlias row : aliases) {
                if (row == null || !row.enabled()) continue;
                put(entities, row.alias(), "alias:" + row.dimension());
                put(entities, row.standardName(), "standard");
            }
        }
        PersonLedgerRepository ledger = ledgerSupplier == null ? null : ledgerSupplier.get();
        if (ledger != null) {
            for (String column : List.of("统计组", "产品线", "应用")) {
                Set<String> truth = ledger.dimensionTruthDictionary(column);
                if (truth != null) {
                    truth.forEach(v -> put(entities, v, "truth:" + column));
                }
            }
        }
        return entities;
    }

    private static void put(Map<String, EntityName> entities, String raw, String source) {
        if (raw == null) return;
        String w = SkillTermMatcher.normalize(raw);
        if (w.length() < 2 || entities.containsKey(w)) return;
        entities.put(w, new EntityName(w, source));
    }

    private void auditTerms(String owner, List<String> terms,
                            Map<String, EntityName> entities, String kind, List<CollisionRow> out) {
        if (terms == null) return;
        Set<String> seen = new java.util.HashSet<>();
        for (String term : terms) {
            if (term == null || term.isBlank()) continue;
            String t = SkillTermMatcher.normalize(term);
            if (t.isEmpty() || containsRegexMeta(t) || !seen.add(t)) continue;
            String level;
            EntityName hit = entities.get(t);
            if (hit != null && "alias".equals(sourceKind(hit.source()))) {
                level = "HIGH";
            } else {
                hit = null;
                for (EntityName e : entities.values()) {
                    if (e.name().contains(t)) {
                        hit = e;
                        break;
                    }
                }
                level = hit == null ? null : "MED";
            }
            if (hit != null) {
                CollisionRow row = new CollisionRow(term, level, hit.name(), hit.source(), kind);
                out.add(row);
                log.warn("SkillDimensionCollision: [{}] {} 词条 '{}' {} 实体名 '{}' ({}), "
                                + "运行期由 span 屏蔽兜底, 建议确认词条语义",
                        level, owner, term, "MED".equals(level) ? "是实体名子串:" : "等于", hit.name(), hit.source());
            }
        }
    }

    private static String sourceKind(String source) {
        int i = source.indexOf(':');
        return i < 0 ? source : source.substring(0, i);
    }

    private static boolean containsRegexMeta(String term) {
        for (int i = 0; i < term.length(); i++) {
            if (REGEX_META.indexOf(term.charAt(i)) >= 0) {
                return true;
            }
        }
        return false;
    }

    /** 启动审计日志（fail-open，绝不阻断启动）。 */
    @Override
    public void run(ApplicationArguments args) {
        List<CollisionRow> rows = auditAll();
        if (rows.isEmpty()) {
            log.info("SkillDimensionCollisionService: startup audit clean (no keyword×dimension-entity collision)");
            return;
        }
        log.warn("SkillDimensionCollisionService: startup audit found {} keyword×dimension-entity collisions "
                + "(提示性质, 行为由 span 屏蔽兜底): skill={}, bypass={}", rows.size(),
                rows.stream().filter(r -> "skill".equals(r.kind())).count(),
                rows.stream().filter(r -> "bypass".equals(r.kind())).count());
    }
}
