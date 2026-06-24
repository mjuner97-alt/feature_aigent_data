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
package com.agentscopea2a.harness.artifact;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;
import java.util.regex.Pattern;

/**
 * Tabular extraction over arbitrary tool-result text — recognizes markdown tables (the format
 * that {@code QualityTools} and most LLM-friendly tools emit) and JSON arrays of objects.
 *
 * <p><b>Intended caller:</b> {@code ArtifactHandoffHook}. The hook hands every tool-result
 * string to {@link #tryParse(String)}; a non-null return means "this looks like a table worth
 * artifactizing". Detection is intentionally conservative — false negatives ("we missed a table
 * the hook could have offloaded") just leave the result inline (small cost: token usage),
 * whereas false positives ("we artifactized a non-table") would break the conversation by
 * replacing prose with a file reference (large cost: agent loses context).
 *
 * <p><b>Why parse here, not in {@code QualityTools}?</b> The whole point of P3 (see {@code
 * docs/artifact-handoff-proposal.md} §6) is that tools should NOT know about artifacts. They
 * just return whatever they always returned. Centralizing the parser here is the single place
 * that knows the table conventions; adding a new tool that returns markdown tables is
 * zero-effort.
 */
public final class TabularExtractor {

    /** Minimum data rows (excluding header) for an artifact to be worth the indirection. */
    public static final int MIN_DATA_ROWS = 4;

    /** Pipe-delimited markdown table — first line is the header row, second is the separator. */
    private static final Pattern MD_SEPARATOR_LINE = Pattern.compile("^\\s*\\|?\\s*:?-{2,}.*$");

    /**
     * JSON array of objects — must START with {@code [} (after whitespace) and look like a list
     * of brace-bounded objects. We deliberately don't fully parse; the cost of false-detecting a
     * non-object array (e.g. {@code [1,2,3]}) is just "not artifactized", which is fine.
     */
    private static final Pattern JSON_ARRAY_HEAD =
            Pattern.compile("^\\s*\\[\\s*\\{", Pattern.DOTALL);

    private TabularExtractor() {}

    /**
     * Attempts to extract tabular structure from {@code text}. Returns {@code null} when the
     * text doesn't clearly look like a table, or when the table is below {@link #MIN_DATA_ROWS}
     * (inline is cheaper for the LLM than an artifact dereference).
     */
    public static TabularData tryParse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        TabularData md = parseMarkdownTable(text);
        if (md != null) {
            return md;
        }
        TabularData json = parseJsonArrayOfObjects(text);
        if (json != null) {
            return json;
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Markdown table parser
    // -------------------------------------------------------------------------

    /**
     * Looks for a markdown table inside {@code text}. A valid table requires:
     *
     * <ul>
     *   <li>a header row whose cells are pipe-delimited</li>
     *   <li>the immediately following line is a separator ({@code |--|--|...})</li>
     *   <li>at least {@link #MIN_DATA_ROWS} subsequent data rows with the same column count</li>
     * </ul>
     *
     * <p>If the text contains multiple tables, only the FIRST is returned — multi-table outputs
     * are rare enough in this app that the simplification is worth the loss in fidelity.
     */
    private static TabularData parseMarkdownTable(String text) {
        String[] lines = text.split("\\R", -1);
        for (int i = 0; i < lines.length - 2; i++) {
            String headerLine = lines[i];
            String sepLine = lines[i + 1];
            if (!isPipeDelimited(headerLine) || !MD_SEPARATOR_LINE.matcher(sepLine).matches()) {
                continue;
            }
            List<String> columns = splitMdRow(headerLine);
            int expectedCols = columns.size();
            if (expectedCols < 1) {
                continue;
            }
            // Collect contiguous data rows after the separator.
            List<List<String>> rows = new ArrayList<>();
            int j = i + 2;
            while (j < lines.length) {
                String row = lines[j];
                if (!isPipeDelimited(row)) {
                    break;
                }
                List<String> cells = splitMdRow(row);
                // Tolerate "| ... |" continuation rows of different width by skipping malformed
                // ones rather than blowing up — keep collecting until a clean break.
                if (cells.size() != expectedCols) {
                    break;
                }
                rows.add(cells);
                j++;
            }
            if (rows.size() >= MIN_DATA_ROWS) {
                return new TabularData(columns, rows);
            }
            // First plausible table was too small — keep scanning in case another table follows.
        }
        return null;
    }

    private static boolean isPipeDelimited(String line) {
        if (line == null) return false;
        String t = line.trim();
        if (!t.startsWith("|") && !t.contains("|")) {
            return false;
        }
        // Require at least one cell boundary so single-pipe noise doesn't match.
        return t.contains("|") && t.replace("|", "").length() < t.length() - 1;
    }

    private static List<String> splitMdRow(String line) {
        String t = line.trim();
        if (t.startsWith("|")) t = t.substring(1);
        if (t.endsWith("|")) t = t.substring(0, t.length() - 1);
        List<String> cells = new ArrayList<>();
        for (String cell : t.split("\\|", -1)) {
            cells.add(cell.trim());
        }
        return cells;
    }

    // -------------------------------------------------------------------------
    // JSON array-of-objects parser
    // -------------------------------------------------------------------------

    /**
     * Lightweight JSON array detection — does NOT use Jackson because the cost of a full parse on
     * every tool result is real, and the hook would need to swallow parse errors anyway. We do a
     * regex sniff first, then a brace-balanced scan to lift out objects. If anything looks
     * inconsistent, we return null and the hook leaves the result inline.
     */
    private static TabularData parseJsonArrayOfObjects(String text) {
        String trimmed = text.trim();
        if (!JSON_ARRAY_HEAD.matcher(trimmed).find()) {
            return null;
        }
        List<String> objects = sliceTopLevelObjects(trimmed);
        if (objects.size() < MIN_DATA_ROWS) {
            return null;
        }
        // Infer columns from the FIRST object's keys (in declared order). All other objects
        // must agree on the column set, otherwise we bail (heterogeneous arrays aren't tabular).
        List<String> columns = extractTopLevelKeys(objects.get(0));
        if (columns.isEmpty()) {
            return null;
        }
        List<List<String>> rows = new ArrayList<>();
        for (String obj : objects) {
            List<String> rowKeys = extractTopLevelKeys(obj);
            if (!rowKeys.equals(columns)) {
                return null;
            }
            List<String> row = new ArrayList<>(columns.size());
            for (String key : columns) {
                row.add(extractValue(obj, key));
            }
            rows.add(row);
        }
        return new TabularData(columns, rows);
    }

    /**
     * Walks {@code arr} (starts with {@code [}) and returns each top-level brace-balanced object
     * as a raw substring. Naive brace counting — does NOT understand escaped quotes inside
     * strings, which is fine for our use case (we're inferring shape, not parsing values).
     */
    private static List<String> sliceTopLevelObjects(String arr) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        int objStart = -1;
        boolean inString = false;
        char prev = 0;
        for (int i = 0; i < arr.length(); i++) {
            char c = arr.charAt(i);
            if (c == '"' && prev != '\\') {
                inString = !inString;
            } else if (!inString) {
                if (c == '{') {
                    if (depth == 0) objStart = i;
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0 && objStart >= 0) {
                        out.add(arr.substring(objStart, i + 1));
                        objStart = -1;
                    }
                }
            }
            prev = c;
        }
        return out;
    }

    /**
     * Extracts top-level keys from a single JSON object string. Preserves declaration order.
     *
     * <p>Walks the string char by char. Top-level keys are strings ({@code "..."}) that appear
     * when we're at depth 1 (inside the outer braces, not inside any nested object / array) and
     * a {@code :} hasn't been seen yet for the current entry (so we know the string is a key,
     * not a string value).
     */
    private static List<String> extractTopLevelKeys(String obj) {
        List<String> keys = new ArrayList<>();
        int depth = 0;
        boolean inString = false;
        boolean expectingValue = false; // true after we've seen `:` for the current entry
        StringBuilder currentKey = null;
        char prev = 0;
        for (int i = 0; i < obj.length(); i++) {
            char c = obj.charAt(i);
            if (inString) {
                if (c == '"' && prev != '\\') {
                    // Closing quote — if we were collecting a top-level key, commit it.
                    if (currentKey != null) {
                        keys.add(currentKey.toString());
                        currentKey = null;
                    }
                    inString = false;
                } else if (currentKey != null) {
                    currentKey.append(c);
                }
            } else {
                if (c == '"') {
                    // Opening quote — only the FIRST string at depth 1 before a `:` is a key.
                    if (depth == 1 && !expectingValue) {
                        currentKey = new StringBuilder();
                    }
                    inString = true;
                } else if (c == '{' || c == '[') {
                    depth++;
                } else if (c == '}' || c == ']') {
                    depth--;
                } else if (c == ':' && depth == 1) {
                    expectingValue = true;
                } else if (c == ',' && depth == 1) {
                    expectingValue = false;
                }
            }
            prev = c;
        }
        return keys;
    }

    /**
     * Extracts a single value from a JSON object for the given key. Returns the raw substring
     * (without outer quotes for strings); for nested objects / arrays / numbers, the raw token.
     * Good enough for CSV — pandas re-parses on the other end.
     */
    private static String extractValue(String obj, String key) {
        // Find "key" followed by colon at depth 1.
        String needle = "\"" + key + "\"";
        int idx = obj.indexOf(needle);
        if (idx < 0) return "";
        int colon = obj.indexOf(':', idx + needle.length());
        if (colon < 0) return "";
        int i = colon + 1;
        // Skip whitespace
        while (i < obj.length() && Character.isWhitespace(obj.charAt(i))) i++;
        if (i >= obj.length()) return "";
        char first = obj.charAt(i);
        if (first == '"') {
            // String value — collect until unescaped closing quote
            StringBuilder sb = new StringBuilder();
            char prev = 0;
            for (int j = i + 1; j < obj.length(); j++) {
                char c = obj.charAt(j);
                if (c == '"' && prev != '\\') break;
                sb.append(c);
                prev = c;
            }
            return sb.toString();
        }
        // Number / bool / null / nested — collect until top-level , or }
        int depth = 0;
        StringBuilder sb = new StringBuilder();
        for (int j = i; j < obj.length(); j++) {
            char c = obj.charAt(j);
            if (c == '{' || c == '[') depth++;
            else if (c == '}' || c == ']') {
                if (depth == 0) break;
                depth--;
            } else if (c == ',' && depth == 0) {
                break;
            }
            sb.append(c);
        }
        return sb.toString().trim();
    }

    // -------------------------------------------------------------------------
    // Output container
    // -------------------------------------------------------------------------

    /** Parsed tabular result — render either as CSV (for the artifact) or markdown (preview). */
    public static final class TabularData {
        private final List<String> columns;
        private final List<List<String>> rows;

        public TabularData(List<String> columns, List<List<String>> rows) {
            this.columns = List.copyOf(columns);
            this.rows = List.copyOf(rows);
        }

        public List<String> columns() {
            return columns;
        }

        public int rowCount() {
            return rows.size();
        }

        public String toCsv() {
            StringJoiner sj = new StringJoiner("\n");
            sj.add(joinCsv(columns));
            for (List<String> r : rows) {
                sj.add(joinCsv(r));
            }
            return sj.toString();
        }

        /**
         * Per-column type / non-null / sample-value inference for the handoff schema block.
         *
         * <p>Why this exists: when the handoff message only shows path + 5-row preview, the
         * downstream {@code code_interpreter} burns one extra LLM iteration + one extra
         * {@code shell_execute} round-trip on {@code df.head() / df.dtypes / df.columns} to
         * re-discover what we already know. Inlining the schema here pays for itself many times
         * over per request (each round-trip is ~1.5~3s on remote Docker).
         *
         * <p>Detection rules (intentionally simple — pandas re-parses on the other end, this is
         * just a hint to the LLM):
         * <ul>
         *   <li>{@code int64} — all non-blank cells match {@code -?\d+}
         *   <li>{@code float64} — all non-blank cells match {@code -?\d+(\.\d+)?} and at least one
         *       has a decimal point
         *   <li>{@code string} — otherwise
         * </ul>
         */
        public List<ColumnSchema> inferSchema(int maxSamples) {
            List<ColumnSchema> out = new ArrayList<>(columns.size());
            int total = rows.size();
            for (int c = 0; c < columns.size(); c++) {
                String name = columns.get(c);
                int nonNull = 0;
                boolean allInt = true;
                boolean allFloat = true;
                boolean sawDecimal = false;
                Double min = null;
                Double max = null;
                Set<String> samples = new LinkedHashSet<>();
                for (List<String> row : rows) {
                    String v = c < row.size() ? row.get(c) : "";
                    if (v == null || v.isBlank()) {
                        allInt = false;
                        allFloat = false;
                        continue;
                    }
                    nonNull++;
                    if (samples.size() < maxSamples) {
                        samples.add(v.trim());
                    }
                    if (allFloat || allInt) {
                        String t = v.trim();
                        boolean isInt = t.matches("-?\\d+");
                        boolean isFloat = isInt || t.matches("-?\\d+\\.\\d+");
                        if (!isInt) allInt = false;
                        if (!isFloat) {
                            allFloat = false;
                        } else {
                            if (!isInt) sawDecimal = true;
                            try {
                                double d = Double.parseDouble(t);
                                if (min == null || d < min) min = d;
                                if (max == null || d > max) max = d;
                            } catch (NumberFormatException ignore) {
                                allFloat = false;
                            }
                        }
                    }
                }
                String dtype;
                if (nonNull == 0) {
                    dtype = "string";
                } else if (allInt) {
                    dtype = "int64";
                } else if (allFloat && sawDecimal) {
                    dtype = "float64";
                } else {
                    dtype = "string";
                }
                out.add(
                        new ColumnSchema(
                                name,
                                dtype,
                                nonNull,
                                total,
                                List.copyOf(samples),
                                "string".equals(dtype) ? null : min,
                                "string".equals(dtype) ? null : max));
            }
            return List.copyOf(out);
        }

        /** Markdown preview of the first {@code maxRows} rows. */
        public String previewMarkdown(int maxRows) {
            StringJoiner sj = new StringJoiner("\n");
            sj.add("| " + String.join(" | ", columns) + " |");
            sj.add("|" + "--|".repeat(columns.size()));
            int n = Math.min(maxRows, rows.size());
            for (int i = 0; i < n; i++) {
                sj.add("| " + String.join(" | ", rows.get(i)) + " |");
            }
            if (n < rows.size()) {
                sj.add("| ... | (省略 " + (rows.size() - n) + " 行,完整数据见 CSV artifact) |");
            }
            return sj.toString();
        }

        private static String joinCsv(List<String> values) {
            StringJoiner sj = new StringJoiner(",");
            for (String v : values) {
                sj.add(csvEscape(v));
            }
            return sj.toString();
        }

        private static String csvEscape(String s) {
            if (s == null) return "";
            boolean needsQuote =
                    s.indexOf(',') >= 0
                            || s.indexOf('"') >= 0
                            || s.indexOf('\n') >= 0
                            || s.indexOf('\r') >= 0;
            if (!needsQuote) return s;
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }

        @Override
        public String toString() {
            return "TabularData(cols=" + columns + ", rows=" + rows.size() + ")";
        }

        /** For tests. */
        public List<List<String>> rowsForTest() {
            return rows;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof TabularData other)) return false;
            return columns.equals(other.columns) && rows.equals(other.rows);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(new Object[] {columns, rows});
        }
    }

    /**
     * Single-column inferred schema row for the handoff message. {@code min}/{@code max} are
     * populated only for numeric dtypes; for {@code string} they are {@code null}.
     */
    public record ColumnSchema(
            String name,
            String dtype,
            int nonNullCount,
            int totalCount,
            List<String> samples,
            Double min,
            Double max) {}
}
