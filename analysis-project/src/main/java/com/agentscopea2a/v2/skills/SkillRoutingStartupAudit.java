/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.agentscopea2a.v2.skills;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Skill 路由启动期重叠审计 (docs/history/skill-skill-overlap-plan.md §3.3.1)。
 *
 * <p>行为对齐 Tool↔Tool 的"自愈优先、阻断兜底"终态, 但只服务重叠检测 (Skill 侧无其他
 * 启动审计类别), 因此 {@code block-skill-overlap=false} (默认) 时直接 return —— 不做
 * O(n²) 配对也不预热向量, 避免每次启动白付 embedding 成本。
 *
 * <p>{@code block-skill-overlap=true} 时:
 * <ol>
 * <li>先同步预热 Skill 描述向量 (启动审计先于后台预热完成, 不预热会因向量缺失漏判
 *     cosine 证据);</li>
 * <li>取报告, 只处置 <b>HIGH 且双方 active</b> 的对 (单侧未 active 不在路由候选,
 *     无选型风险, 走页面预警);</li>
 * <li>规模 ≤ block-skill-overlap-max-skills (默认 4) 时自愈: 自动停用 skillName
 *     字典序较大一侧 (无 priority 字段, 确定性规则重启幂等), upsert 失败的对走阻断兜底;
 *     处置后失效报告缓存;</li>
 * <li>超阈值视为批量数据问题, 不自动变更元数据, 降级告警;</li>
 * <li>存在自愈失败的对 → 抛 IllegalStateException 阻断启动 (ApplicationRunner 阶段,
 *     端口已短暂监听后进程退出, 探活以进程退出码/日志为准)。</li>
 * </ol>
 */
public class SkillRoutingStartupAudit implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SkillRoutingStartupAudit.class);

    /** block-skill-overlap 允许自愈的最大重复 Skill 数, 超过则降级为告警。 */
    static final int DEFAULT_MAX_BLOCKING_OVERLAP_SKILLS = 4;

    private final com.agentscopea2a.v2.governance.SkillSkillOverlapService skillSkillOverlapService;
    private final SkillRoutingMetadataRepository metadataRepository;
    private final boolean blockSkillOverlap;
    private final int blockSkillOverlapMaxSkills;

    public SkillRoutingStartupAudit(
            com.agentscopea2a.v2.governance.SkillSkillOverlapService skillSkillOverlapService,
            SkillRoutingMetadataRepository metadataRepository) {
        this(skillSkillOverlapService, metadataRepository, false, DEFAULT_MAX_BLOCKING_OVERLAP_SKILLS);
    }

    public SkillRoutingStartupAudit(
            com.agentscopea2a.v2.governance.SkillSkillOverlapService skillSkillOverlapService,
            SkillRoutingMetadataRepository metadataRepository,
            boolean blockSkillOverlap,
            int blockSkillOverlapMaxSkills) {
        this.skillSkillOverlapService = skillSkillOverlapService;
        this.metadataRepository = metadataRepository;
        this.blockSkillOverlap = blockSkillOverlap;
        this.blockSkillOverlapMaxSkills = Math.max(1, blockSkillOverlapMaxSkills);
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!blockSkillOverlap || skillSkillOverlapService == null) {
            return;
        }
        // 同步预热 Skill 向量: 审计先于后台预热完成, 不预热会因向量缺失漏判 cosine 证据
        skillSkillOverlapService.ensureSkillVectorsWarmed();

        List<com.agentscopea2a.v2.governance.SkillSkillOverlapView> highPairs =
                skillSkillOverlapService.report().items().stream()
                        .filter(pair -> "HIGH".equals(pair.level()))
                        .filter(pair -> pair.activeA() && pair.activeB())
                        .toList();
        if (highPairs.isEmpty()) {
            log.info("SkillRoutingStartupAudit: no high skill-skill overlap (block-skill-overlap=true)");
            return;
        }

        Set<String> affected = new HashSet<>();
        for (var pair : highPairs) {
            affected.add(pair.skillNameA());
            affected.add(pair.skillNameB());
        }
        if (affected.size() > blockSkillOverlapMaxSkills) {
            log.warn("SkillRoutingStartupAudit: {} 组 Skill 功能重复涉及 {} 个 Skill, 超过 "
                            + "block-skill-overlap-max-skills={} 阈值, 视为批量数据问题, 降级为告警不自动变更元数据",
                    highPairs.size(), affected.size(), blockSkillOverlapMaxSkills);
            return;
        }

        Set<String> autoDeactivated = autoDeactivateOverlapLosers(highPairs);
        List<com.agentscopea2a.v2.governance.SkillSkillOverlapView> unresolved = highPairs.stream()
                .filter(pair -> !autoDeactivated.contains(pair.skillNameA())
                        && !autoDeactivated.contains(pair.skillNameB()))
                .toList();
        if (!unresolved.isEmpty()) {
            throw new IllegalStateException("Skill 路由启动检测到 " + unresolved.size()
                    + " 组 Skill 功能重复，block-skill-overlap=true 禁止启动。明细: "
                    + unresolved.stream()
                            .map(pair -> "Skill " + pair.skillNameA() + " 与 Skill " + pair.skillNameB()
                                    + " 有重复，请处理")
                            .toList());
        }
    }

    /**
     * 自愈: 双方 active 的 HIGH 对自动停用 skillName 字典序较大一侧 (确定性规则, 重启幂等)。
     * 返回被停用的 skillName 集合; upsert 失败的对不进入集合, 由调用方走阻断兜底。
     */
    private Set<String> autoDeactivateOverlapLosers(
            List<com.agentscopea2a.v2.governance.SkillSkillOverlapView> pairs) {
        Set<String> deactivated = new HashSet<>();
        List<com.agentscopea2a.v2.governance.SkillSkillOverlapView> sorted = pairs.stream()
                .sorted(java.util.Comparator.comparing(
                                com.agentscopea2a.v2.governance.SkillSkillOverlapView::skillNameA)
                        .thenComparing(com.agentscopea2a.v2.governance.SkillSkillOverlapView::skillNameB))
                .toList();
        for (var pair : sorted) {
            String a = pair.skillNameA();
            String b = pair.skillNameB();
            if (deactivated.contains(a) || deactivated.contains(b)) {
                continue; // 一侧已因前序对被停用, 该对已满足"至多一个启用"
            }
            String loser = a.compareTo(b) <= 0 ? b : a;
            SkillRoutingMetadata meta = metadataRepository.findBySkillName(loser).orElse(null);
            if (meta == null) {
                log.warn("SkillRoutingStartupAudit: [skill_overlap] Skill {} 自动停用失败 (元数据行缺失), 该重叠对走阻断兜底", loser);
                continue;
            }
            boolean updated = metadataRepository.upsert(new SkillRoutingMetadata(
                    meta.skillName(), meta.shortSummary(), meta.keywords(), meta.domainTags(),
                    meta.topicTags(), meta.creator(), false, null,
                    meta.level1Keywords(), meta.level2Keywords(), meta.level3Keywords()));
            if (updated) {
                deactivated.add(loser);
                log.warn("SkillRoutingStartupAudit: [skill_overlap] Skill {} 与 {} 功能重复, "
                                + "自动停用 {} 保留 {} (同一候选集内至多启用一个, 不阻断启动; "
                                + "可在页面停用保留侧后重新启用被停用侧, 或合并两个 Skill)",
                        a, b, loser, loser.equals(a) ? b : a);
            } else {
                log.warn("SkillRoutingStartupAudit: [skill_overlap] Skill {} 自动停用失败, 该重叠对走阻断兜底", loser);
            }
        }
        if (!deactivated.isEmpty()) {
            // 报告基于停用前快照, 失效让页面/审计后续读取反映"一侧已停用"的现状
            skillSkillOverlapService.invalidate();
        }
        return deactivated;
    }
}
