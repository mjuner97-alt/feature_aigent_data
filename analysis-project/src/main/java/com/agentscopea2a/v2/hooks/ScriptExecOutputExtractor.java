package com.agentscopea2a.v2.hooks;

/** Extracts the report body from ScriptExecTool's human-readable envelope. */
final class ScriptExecOutputExtractor {

    private static final String STDOUT_MARKER = "─── stdout ";
    private static final String STDERR_MARKER = "─── stderr ";

    private ScriptExecOutputExtractor() {
    }

    static String extractStdout(String toolOutput) {
        if (toolOutput == null || toolOutput.isBlank()) {
            return "";
        }

        int stdoutMarker = toolOutput.indexOf(STDOUT_MARKER);
        if (stdoutMarker < 0) {
            return toolOutput.trim();
        }

        int stdoutStart = toolOutput.indexOf('\n', stdoutMarker);
        if (stdoutStart < 0) {
            return "";
        }
        stdoutStart++;

        int stderrMarker = toolOutput.indexOf("\n" + STDERR_MARKER, stdoutStart);
        String stdout = (stderrMarker >= 0
                ? toolOutput.substring(stdoutStart, stderrMarker)
                : toolOutput.substring(stdoutStart)).trim();
        return "(空)".equals(stdout) ? "" : stdout;
    }

    /** Return only fenced ECharts/HTML blocks from a script result. */
    static String extractRenderableBlocks(String toolOutput) {
        String stdout = extractStdout(toolOutput);
        if (stdout.isBlank()) return "";
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "```\\s*(echarts?|html?|htm)\\s*\\n[\\s\\S]*?```",
                java.util.regex.Pattern.CASE_INSENSITIVE).matcher(stdout);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            if (result.length() > 0) result.append("\n\n");
            result.append(matcher.group().trim());
        }
        return result.toString();
    }
}
