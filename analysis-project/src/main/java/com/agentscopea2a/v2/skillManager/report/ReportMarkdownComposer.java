package com.agentscopea2a.v2.skillManager.report;

import java.util.LinkedHashSet;
import java.util.List;

/** Combines model Markdown with renderable script blocks without duplicates. */
public final class ReportMarkdownComposer {
    private ReportMarkdownComposer() {}

    public static String compose(String markdown, List<String> scriptBlocks) {
        String result = markdown == null ? "" : markdown.trim();
        if (scriptBlocks == null) return result;
        for (String block : new LinkedHashSet<>(scriptBlocks)) {
            if (block == null || block.isBlank() || result.contains(block)) continue;
            if (!result.isEmpty()) result += "\n\n";
            result += block.trim();
        }
        return result;
    }
}
