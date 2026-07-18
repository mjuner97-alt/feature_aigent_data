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
package com.agentscopea2a.v2.hooks;

import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Enriches failed {@code python_exec} results with structured retry hints.
 *
 * <p><b>Why this exists.</b> See {@code docs/code-interpreter-optimization.md} §P0-D.
 * The {@code code_interpreter} subagent's default failure mode is "LLM sees stderr →
 * rewrites the ENTIRE script → another remote-SSH round-trip → another error".
 * This hook short-circuits that anti-pattern by parsing Python's traceback and adding
 * an LLM-friendly annotation that says exactly:
 *
 * <ol>
 *   <li>Which line in the original code failed (so the LLM can fix that one line, not rewrite)
 *   <li>What category of error it is (KeyError / NameError / UnicodeDecodeError / FileNotFoundError)
 *   <li>The 1-line fix recipe (when we know it — Chinese column names, encoding, missing file)
 * </ol>
 *
 * <p><b>Why a hook, not folded into PythonExecTool.</b> Tool-side error formatting would
 * couple {@code PythonExecTool} to LLM-prompt-shape concerns — exactly the
 * "tools coupled to validation hooks" anti-pattern called out in the project memory.
 * Keeping it as a hook means the tool stays a thin shell-out and this file owns the
 * retry-prompt semantics in one place.
 *
 * <p><b>Priority.</b> 13 — after {@link ArtifactHandoffHook} (12, which doesn't touch
 * python_exec since it's not in EXCLUDED_TOOLS but won't match because python_exec output
 * isn't tabular). 13 keeps us deterministic right after handoff.
 *
 * <p><b>Note:</b> Uses deprecated Hook/PostActingEvent API because v2 MiddlewareBase.onActing()
 * only intercepts tool calls BEFORE execution. Tool result rewriting requires PostActingEvent.
 */
@SuppressWarnings("deprecation")
public class PythonExecRetryHook implements Hook {

    private static final Logger log = LoggerFactory.getLogger(PythonExecRetryHook.class);

    private static final String TARGET_TOOL = "python_exec";

    /** Matches the "exit=N" line in {@code PythonExecTool}'s output banner. */
    private static final Pattern EXIT_PATTERN =
            Pattern.compile("\\[python_exec\\]\\s+exit=(-?\\d+)\\b");

    /** Matches a Python traceback "File ..., line N" frame referring to the inline stdin script
     * (Python labels stdin script as {@code <stdin>}). */
    private static final Pattern STDIN_FRAME_PATTERN =
            Pattern.compile("File \"<stdin>\", line (\\d+)");

    /** Matches the final exception line, e.g. {@code KeyError: '部门'}. */
    private static final Pattern EXCEPTION_LINE_PATTERN =
            Pattern.compile("(?m)^([A-Z][A-Za-z]*Error|Exception)(:|$).*$");

    @Override
    public int priority() {
        return 13;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (!(event instanceof PostActingEvent post)) {
            return Mono.just(event);
        }
        ToolUseBlock toolUse = post.getToolUse();
        ToolResultBlock result = post.getToolResult();
        if (toolUse == null || result == null) return Mono.just(event);
        if (!TARGET_TOOL.equals(toolUse.getName())) return Mono.just(event);

        String text = extractText(result.getOutput());
        if (text.isBlank()) return Mono.just(event);

        // Only act on failures — successful runs are left untouched.
        int exit = parseExit(text);
        if (exit == 0) return Mono.just(event);

        String code = extractCodeArg(toolUse);
        String hint = buildHint(text, code);
        if (hint == null || hint.isBlank()) return Mono.just(event);

        String enriched =
                text
                        + "\n──── 重试提示(harness 自动追加,不是 python 输出) ────\n"
                        + hint
                        + "\n";

        ToolResultBlock rewritten =
                new ToolResultBlock(
                        result.getId(),
                        result.getName(),
                        List.of(TextBlock.builder().text(enriched).build()),
                        result.getMetadata());
        post.setToolResult(rewritten);
        log.info("PythonExecRetryHook annotated failure (exit={}, hint chars={})", exit, hint.length());

        return Mono.just(event);
    }

    // ------------------------------------------------------------------
    // Parsing
    // ------------------------------------------------------------------

    private static int parseExit(String text) {
        Matcher m = EXIT_PATTERN.matcher(text);
        if (!m.find()) return 0;
        try {
            return Integer.parseInt(m.group(1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Builds the retry hint. Three pieces, all best-effort — when we can't find something we
     * silently drop that section rather than fabricating.
     */
    private static String buildHint(String resultText, String code) {
        StringBuilder sb = new StringBuilder();

        // 1) Locate the failing line in the original code.
        Integer lineNo = findStdinLine(resultText);
        if (lineNo != null && code != null) {
            String snippet = extractLine(code, lineNo);
            if (snippet != null) {
                sb.append("✦ 失败行 (code 第 ").append(lineNo).append(" 行):\n    ")
                        .append(snippet.trim()).append("\n");
            }
        }

        // 2) Classify the exception + emit a 1-line recipe when we recognise it.
        String exceptionLine = findExceptionLine(resultText);
        if (exceptionLine != null) {
            sb.append("✦ 异常类别: ").append(exceptionLine.trim()).append("\n");
            String recipe = recipeFor(exceptionLine);
            if (recipe != null) {
                sb.append("✦ 常见修法: ").append(recipe).append("\n");
            }
        }

        // 3) Retry discipline reminder — short, every time, so the LLM doesn't drift.
        sb.append("✦ 纪律: 不要重写整段代码。把上次的 code 完整复制,只改报错那一行,")
                .append("在那行上方加 `# fix: <一句话>` 注释。连续失败 2 次后停止重试,把");
        sb.append("最后一版 code + 完整 traceback 回报给 analyze_data。");

        return sb.toString();
    }

    private static Integer findStdinLine(String resultText) {
        Matcher m = STDIN_FRAME_PATTERN.matcher(resultText);
        Integer last = null;
        while (m.find()) {
            try {
                last = Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignore) {
                // skip
            }
        }
        return last; // last frame = innermost = the actual failure point
    }

    private static String findExceptionLine(String resultText) {
        Matcher m = EXCEPTION_LINE_PATTERN.matcher(resultText);
        String last = null;
        while (m.find()) {
            last = m.group();
        }
        return last;
    }

    private static String extractLine(String code, int lineNo) {
        if (code == null || lineNo < 1) return null;
        String[] lines = code.split("\\r?\\n", -1);
        if (lineNo > lines.length) return null;
        return lines[lineNo - 1];
    }

    /**
     * Pattern-matched recipes for the handful of failure shapes that bite this codebase repeatedly.
     * Deliberately small + conservative — we'd rather say nothing than wave the LLM toward a
     * wrong fix.
     */
    private static String recipeFor(String exceptionLine) {
        String e = exceptionLine == null ? "" : exceptionLine;
        if (e.startsWith("KeyError")) {
            return "列名拼写错了。**用 handoff 消息里 Schema 段给的列名,逐字符复制**,"
                    + "尤其是括号(全角 vs 半角)、空格、繁简体。";
        }
        if (e.startsWith("UnicodeDecodeError")) {
            return "CSV 编码不是 utf-8。在 pd.read_csv 加 `encoding='utf-8'`(已是默认),"
                    + "若 still 报错改 `encoding='gbk'`。极少数情况下需要 `encoding_errors='replace'`。";
        }
        if (e.startsWith("FileNotFoundError")) {
            return "路径错了。**直接复制 handoff 消息里 `路径:` 那一行**,不要手工拼。"
                    + "若路径里有 /workspace/artifacts/<otherUser>/ 那就是越权,ArtifactAccessHook 已拦下。";
        }
        if (e.startsWith("NameError")) {
            return "用了未定义的名字。检查是否漏了 `import pandas as pd` / `import numpy as np`,"
                    + "或拼写错了变量名。";
        }
        if (e.startsWith("TypeError")) {
            return "类型不匹配。看 Schema 段的 dtype:string 列不能直接做 .mean(),"
                    + "需要先 `pd.to_numeric(s, errors='coerce')` 转 float。";
        }
        if (e.startsWith("ValueError") && e.contains("could not convert")) {
            return "数据里有空字符串或非数字。用 `pd.to_numeric(s, errors='coerce')` 跳过坏值。";
        }
        if (e.startsWith("ModuleNotFoundError")) {
            return "镜像里没装这个包。**沙箱只有 pandas/numpy/openpyxl/matplotlib**,"
                    + "其他库一律告知调用者镜像缺包,不要尝试 pip install。";
        }
        return null;
    }

    // ------------------------------------------------------------------
    // ToolUse argument extraction
    // ------------------------------------------------------------------

    /**
     * Pulls the {@code code} argument out of the tool use block. The argument map shape depends on
     * the framework's marshalling — we coerce reasonably, falling back to {@code null} if we
     * can't find it (in which case the hint just won't include the failing line snippet).
     */
    private static String extractCodeArg(ToolUseBlock toolUse) {
        Object args = toolUse.getInput();
        if (args == null) return null;
        if (args instanceof java.util.Map<?, ?> map) {
            Object v = map.get("code");
            return v == null ? null : v.toString();
        }
        return null;
    }

    private static String extractText(List<ContentBlock> blocks) {
        if (blocks == null) return "";
        StringBuilder sb = new StringBuilder();
        for (ContentBlock b : blocks) {
            if (b instanceof TextBlock tb) {
                sb.append(tb.getText()).append('\n');
            }
        }
        return sb.toString();
    }
}
