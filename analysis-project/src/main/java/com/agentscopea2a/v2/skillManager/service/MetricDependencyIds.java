package com.agentscopea2a.v2.skillManager.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Normalizes the temporary CSV representation used by metric dependency columns. */
public final class MetricDependencyIds {
    private MetricDependencyIds() {}

    public static List<Long> parse(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        for (String token : csv.split(",")) {
            if (token.isBlank()) continue;
            long id = Long.parseLong(token.trim());
            if (id > 0) ids.add(id);
        }
        return List.copyOf(ids);
    }

    public static String encode(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return null;
        LinkedHashSet<Long> unique = new LinkedHashSet<>();
        for (Long id : ids) if (id != null && id > 0) unique.add(id);
        return unique.isEmpty() ? null : String.join(",", unique.stream().map(String::valueOf).toList());
    }
}
