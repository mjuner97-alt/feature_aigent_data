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
package com.agentscopea2a.v2.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses text-format tool calls that some model channels emit instead of native function
 * calling. The model writes plain text shaped like:
 *
 * <pre>
 * MARKER
 * &lt;function=toolName&gt;
 * &lt;parameter=paramKey&gt;paramValue&lt;/parameter&gt;
 * &lt;/function&gt;
 * </pre>
 *
 * where MARKER is the agentscope text tool-call delimiter (U+2042). The framework has no
 * parser for this format, so the markup otherwise reaches the user verbatim and the ReAct
 * loop spins to MAX_ITERATIONS with zero tool executions. {@link TextToolCallFallbackModel}
 * uses this class to convert such segments into real tool calls.
 */
public final class TextToolCallParser {

    /** The agentscope text tool-call delimiter emitted by some channels. */
    public static final char MARKER = '⁂';

    private static final String OPEN_FUNCTION = "<function=";
    private static final String CLOSE_FUNCTION = "</function>";
    /** Max chars allowed between the marker and the {@code <function} tag before treating the marker as plain text. */
    private static final int MAX_MARKER_GAP = 16;

    private static final Pattern FUNCTION_BLOCK =
            Pattern.compile("<function=([\\w.\\-]+)>(.*?)</function>", Pattern.DOTALL);
    private static final Pattern PARAM_BLOCK =
            Pattern.compile("<parameter=([\\w.\\-]+)>(.*?)</parameter>", Pattern.DOTALL);

    private TextToolCallParser() {
    }

    /** A parsed tool call: tool name plus its parameters. */
    public record ParsedToolCall(String name, Map<String, Object> input) {
    }

    /**
     * Extracts all {@code <function=...>...</function>} blocks from the given text.
     *
     * @return parsed calls; empty if the text contains no complete function block
     */
    public static List<ParsedToolCall> parseFunctionBlocks(String text) {
        List<ParsedToolCall> calls = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return calls;
        }
        Matcher fm = FUNCTION_BLOCK.matcher(text);
        while (fm.find()) {
            String name = fm.group(1).trim();
            if (name.isEmpty()) {
                continue;
            }
            Map<String, Object> input = new LinkedHashMap<>();
            Matcher pm = PARAM_BLOCK.matcher(fm.group(2));
            while (pm.find()) {
                input.put(pm.group(1).trim(), pm.group(2).trim());
            }
            calls.add(new ParsedToolCall(name, input));
        }
        return calls;
    }

    /**
     * Compares {@code s} against {@code prefix}.
     *
     * @return 1 if s starts with prefix, 0 if s is a non-empty proper prefix of prefix (need
     *         more data), -1 on mismatch or when s is empty
     */
    public static int matchPrefix(String s, String prefix) {
        if (s == null || s.isEmpty()) {
            return -1;
        }
        int n = Math.min(s.length(), prefix.length());
        for (int i = 0; i < n; i++) {
            if (s.charAt(i) != prefix.charAt(i)) {
                return -1;
            }
        }
        return s.length() >= prefix.length() ? 1 : 0;
    }

    /**
     * Decides what follows a marker char.
     *
     * @param afterMarker text immediately after the marker char
     * @return 1 if whitespace + {@code <function=} definitively follows (enter markup mode),
     *         0 if the tag may still arrive (hold and wait for more deltas),
     *         -1 if the marker is definitively plain text
     */
    public static int classifyAfterMarker(String afterMarker) {
        int ws = 0;
        while (ws < afterMarker.length() && Character.isWhitespace(afterMarker.charAt(ws))) {
            ws++;
        }
        if (ws > MAX_MARKER_GAP) {
            return -1;
        }
        String rest = afterMarker.substring(ws);
        if (rest.isEmpty()) {
            return 0;
        }
        int r = matchPrefix(rest, OPEN_FUNCTION);
        if (r == 1) {
            return 1;
        }
        return r == 0 ? 0 : -1;
    }

    /**
     * Finds the end of the next {@code </function>} at or after {@code from}.
     *
     * @return index just past the closing tag, or -1 if the tag is not (yet) present
     */
    public static int findFunctionBlockEnd(String text, int from) {
        int idx = text.indexOf(CLOSE_FUNCTION, from);
        return idx < 0 ? -1 : idx + CLOSE_FUNCTION.length();
    }
}
