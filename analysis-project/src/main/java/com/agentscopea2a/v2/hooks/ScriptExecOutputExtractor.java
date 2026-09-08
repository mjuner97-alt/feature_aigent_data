package com.agentscopea2a.v2.hooks;

/**
 * Parses only the textual protocol between {@code ScriptExecTool} and hooks.
 *
 * <p>This class does not persist results, build an SSE response, or render HTML.
 * It only removes the human-readable stdout/stderr envelope and identifies
 * renderable Markdown fenced blocks. The caller decides whether those blocks
 * are cached, replaced with references, or otherwise consumed.
 */
final class ScriptExecOutputExtractor {

    /** Markers emitted by ScriptExecTool.formatResult(). */
    private static final String STDOUT_MARKER = "─── stdout ";
    private static final String STDERR_MARKER = "─── stderr ";

    /**
     * Matches complete renderable blocks produced by Python scripts.
     * The HTML itself is intentionally kept opaque here; DOM parsing and
     * sanitization happen in the frontend Markdown renderer.
     */
    static final java.util.regex.Pattern RENDERABLE_BLOCK_PATTERN = java.util.regex.Pattern.compile(
            "(?:```\\s*(echarts?|html?|htm)\\s*\\n[\\s\\S]*?```"
                    + "|<(echarts?|echart|html|htm)\\b[^>]*>[\\s\\S]*?</\\2\\s*>"
                    + "|<!doctype\\s+html\\b[^>]*>[\\s\\S]*?</html\\s*>)",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    private ScriptExecOutputExtractor() {
    }

    static String extractStdout(String toolOutput) {
        if (toolOutput == null || toolOutput.isBlank()) {
            return "";
        }

        // Some tools may return plain text without the ScriptExec envelope.
        // In that case preserve the text instead of treating it as empty.
        int stdoutMarker = toolOutput.indexOf(STDOUT_MARKER);
        if (stdoutMarker < 0) {
            return toolOutput.trim();
        }

        int stdoutStart = toolOutput.indexOf('\n', stdoutMarker);
        if (stdoutStart < 0) {
            return "";
        }
        stdoutStart++;

        // Only the stdout section is eligible for HTML/ECharts extraction;
        // stderr may contain diagnostics that must never be rendered.
        int stderrMarker = toolOutput.indexOf("\n" + STDERR_MARKER, stdoutStart);
        String stdout = (stderrMarker >= 0
                ? toolOutput.substring(stdoutStart, stderrMarker)
                : toolOutput.substring(stdoutStart)).trim();
        return "(空)".equals(stdout) ? "" : stdout;
    }

    /**
     * Returns renderable blocks as text for the caller to process.
     * This method does not send or persist the returned value.
     */
    static String extractRenderableBlocks(String toolOutput) {
        String stdout = extractStdout(toolOutput);
        if (stdout.isBlank()) return "";
        java.util.regex.Matcher matcher = RENDERABLE_BLOCK_PATTERN.matcher(stdout);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            if (result.length() > 0) result.append("\n\n");
            result.append(matcher.group().trim());
        }
        return result.toString();
    }
}
