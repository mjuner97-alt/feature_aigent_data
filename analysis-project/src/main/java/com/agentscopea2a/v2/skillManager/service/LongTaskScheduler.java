package com.agentscopea2a.v2.skillManager.service;

import com.agentscopea2a.v2.skillManager.entity.LongTaskType;
import com.agentscopea2a.v2.skillManager.entity.SkillFlow;
import java.util.*;

public final class LongTaskScheduler {
    private LongTaskScheduler() {}
    public static List<SkillFlow> orderForAutoTrigger(List<SkillFlow> flows, Map<Long, LongTaskType> types) {
        if (flows == null || flows.isEmpty()) return List.of();
        return flows.stream().filter(Objects::nonNull)
                .filter(f -> Boolean.TRUE.equals(f.getEnabled()))
                .filter(f -> f.getTypeId() != null && types != null && types.get(f.getTypeId()) != null)
                .filter(f -> Boolean.TRUE.equals(types.get(f.getTypeId()).getEnabled()))
                .sorted(Comparator
                        .comparingInt((SkillFlow f) -> Optional.ofNullable(types.get(f.getTypeId()).getSortOrder()).orElse(Integer.MAX_VALUE))
                        .thenComparingInt(f -> Optional.ofNullable(f.getPriority()).orElse(0))
                        .thenComparing(SkillFlow::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(SkillFlow::getId, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }
}
