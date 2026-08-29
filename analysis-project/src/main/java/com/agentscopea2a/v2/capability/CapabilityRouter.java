package com.agentscopea2a.v2.capability;

import com.agentscopea2a.v2.skills.SkillRoutingMetadata;

import java.util.*;
import java.util.stream.Collectors;

/** Deterministic coarse recall: capability terms select a bounded Skill pool. */
public class CapabilityRouter {
    private final int maxCapabilities;
    private final int maxSkills;

    public CapabilityRouter(int maxCapabilities, int maxSkills) {
        this.maxCapabilities = Math.max(1, maxCapabilities);
        this.maxSkills = Math.max(1, maxSkills);
    }

    public Set<String> recallSkillNames(String question, List<CapabilityMetadata> capabilities,
                                        Map<String, List<String>> bindings) {
        String q = normalize(question);
        if (q.isBlank() || capabilities == null || bindings == null) return Set.of();
        List<CapabilityMetadata> matched = capabilities.stream().filter(CapabilityMetadata::active)
                .filter(c -> termsMatch(q, c.aliases()) || termsMatch(q, c.keywords()) || termsMatch(q, c.domainTags()))
                .sorted(Comparator.comparingInt(CapabilityMetadata::priority).reversed())
                .limit(maxCapabilities).toList();
        return matched.stream().flatMap(c -> bindings.getOrDefault(c.name(), List.of()).stream())
                .limit(maxSkills).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static boolean termsMatch(String q, List<String> terms) {
        return terms != null && terms.stream().map(CapabilityRouter::normalize)
                .anyMatch(term -> !term.isBlank() && q.contains(term));
    }
    private static String normalize(String s) { return s == null ? "" : s.toLowerCase(Locale.ROOT).trim(); }
}
