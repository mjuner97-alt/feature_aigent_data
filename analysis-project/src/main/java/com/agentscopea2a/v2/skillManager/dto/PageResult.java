package com.agentscopea2a.v2.skillManager.dto;

import java.util.List;

/** Stable page envelope for lightweight management lists. */
public record PageResult<T>(List<T> items, int page, int pageSize, long total) {
    public static int safePage(int value) { return Math.max(1, value); }
    public static int safeSize(int value) { return Math.min(100, Math.max(1, value)); }
}
