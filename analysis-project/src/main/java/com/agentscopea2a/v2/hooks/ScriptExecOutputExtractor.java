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
}
