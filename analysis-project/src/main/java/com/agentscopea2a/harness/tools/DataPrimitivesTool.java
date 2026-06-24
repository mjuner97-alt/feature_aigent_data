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
package com.agentscopea2a.harness.tools;

import com.agentscopea2a.harness.config.SandboxProperties;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Computation primitives that bypass the {@code code_interpreter} subagent entirely.
 *
 * <p>See {@code docs/code-interpreter-optimization.md} §P1-B. 80% of the "analyse this CSV"
 * requests are one of 5 shapes: aggregate, topN, compareRatio, pivot, distribution. Each of
 * these is a single pandas operation that we can shell out to {@code python3 -} on the
 * container — same transport as {@link PythonExecTool}, same security model, but the LLM
 * writes a much shorter, constrained script, cutting the error surface.
 *
 * <p><b>调用模型 — 与项目其他工具一致.</b> 本工具不直接暴露给 subagent.toolkit,而是注册成
 * Spring Bean,由 {@code ToolRoutersIndex} 扫描 {@code @Tool} 方法后,通过元工具
 * {@code router_tool / toolMetaInfo} 统一调用。subagent 通过 skill {@code data_primitives}
 * 查 {@code toolId},再走 {@code toolMetaInfo} → {@code router_tool} 的两步式路由。
 *
 * <p>支持的分组/聚合维度完全由 CSV 列决定 —— 无任何硬编码维度。
 * 部门、应用、组、产品线、需求项、人员等任意单维或多维组合都可走
 * {@code data_aggregate(groupByColumns=[...])} / {@code data_pivot} 等。
 */
@Component
public class DataPrimitivesTool {

    private static final Logger log = LoggerFactory.getLogger(DataPrimitivesTool.class);

    private static final int DEFAULT_TIMEOUT_SECONDS = 60;
    private static final int MAX_OUTPUT_BYTES = 64_000;

    private final SandboxProperties.Sandbox sandbox;

    public DataPrimitivesTool(SandboxProperties sandboxProps) {
        this.sandbox = sandboxProps != null ? sandboxProps.getSandbox() : null;
    }

    // ======================================================================
    // aggregate — GROUP BY + 任意 agg(fn)
    // ======================================================================

    @io.agentscope.core.tool.Tool(
            name = "data_aggregate",
            description = "对 CSV 数据做分组聚合。例: 按 部门 列对 缺陷密度 列求均值。"
                    + "支持 aggFn: mean / sum / std / median / count / min / max。"
                    + "结果直接返回 markdown 表,不需要再派 code_interpreter。")
    public io.agentscope.core.message.ToolResultBlock aggregate(
            @io.agentscope.core.tool.ToolParam(
                            name = "csvPath",
                            description = "CSV artifact 路径,从 handoff 消息的 `路径:` 行复制")
                    String csvPath,
            @io.agentscope.core.tool.ToolParam(
                            name = "groupByColumns",
                            description = "分组列名列表,如 [\"部门\", \"季度\"]")
                    List<String> groupByColumns,
            @io.agentscope.core.tool.ToolParam(
                            name = "valueColumn",
                            description = "要聚合的数值列名,如 \"缺陷密度\"")
                    String valueColumn,
            @io.agentscope.core.tool.ToolParam(
                            name = "aggFn",
                            description = "聚合函数: mean / sum / std / median / count / min / max",
                            required = false)
                    String aggFn) {

        if (csvPath == null || csvPath.isBlank()) {
            return io.agentscope.core.message.ToolResultBlock.text(
                    "data_aggregate: csvPath 为空,请从 handoff 消息复制路径。");
        }
        if (groupByColumns == null || groupByColumns.isEmpty()) {
            return io.agentscope.core.message.ToolResultBlock.text(
                    "data_aggregate: groupByColumns 为空,至少需要一个分组列。");
        }
        if (valueColumn == null || valueColumn.isBlank()) {
            return io.agentscope.core.message.ToolResultBlock.text(
                    "data_aggregate: valueColumn 为空,需要指定数值列。");
        }
        String fn = (aggFn == null || aggFn.isBlank()) ? "mean" : aggFn;

        String groupByStr = toPythonList(groupByColumns);
        String code =
                "import pandas as pd\n"
                        + "df = pd.read_csv(" + pyStr(csvPath) + ")\n"
                        + "g = df.groupby(" + groupByStr + ")[" + pyStr(valueColumn) + "]." + fn + "()\n"
                        + "print(g.to_markdown())\n";
        return runPython(code);
    }

    // ======================================================================
    // topN — sort + limit
    // ======================================================================

    @io.agentscope.core.tool.Tool(
            name = "data_top_n",
            description = "对 CSV 数据按某列排序取前 N 行。例: 按 缺陷密度 降序取 Top-3 部门。"
                    + "结果直接返回 markdown 表。")
    public io.agentscope.core.message.ToolResultBlock topN(
            @io.agentscope.core.tool.ToolParam(
                            name = "csvPath",
                            description = "CSV artifact 路径")
                    String csvPath,
            @io.agentscope.core.tool.ToolParam(
                            name = "sortByColumn",
                            description = "排序依据列,如 \"缺陷密度\"")
                    String sortByColumn,
            @io.agentscope.core.tool.ToolParam(
                            name = "n",
                            description = "取前 N 行,默认 5")
                    Integer n,
            @io.agentscope.core.tool.ToolParam(
                            name = "ascending",
                            description = "是否升序,默认 false(降序,即数值大的排前面)",
                            required = false)
                    Boolean ascending) {

        if (csvPath == null || csvPath.isBlank()) {
            return io.agentscope.core.message.ToolResultBlock.text("data_top_n: csvPath 为空。");
        }
        int limit = (n == null || n <= 0) ? 5 : n;
        boolean asc = ascending != null && ascending;

        String code =
                "import pandas as pd\n"
                        + "df = pd.read_csv(" + pyStr(csvPath) + ")\n"
                        + "result = df.nlargest(" + limit + ", " + pyStr(sortByColumn) + ")"
                        + (asc ? ".iloc[::-1]" : "") + "\n"
                        + "print(result.to_markdown(index=False))\n";
        return runPython(code);
    }

    // ======================================================================
    // compareRatio — 两张表 join + 同比/环比
    // ======================================================================

    @io.agentscope.core.tool.Tool(
            name = "data_compare_ratio",
            description = "对比两张 CSV 表的数值列,计算变化率。"
                    + "例: 对比 2026Q1 和 2026Q2 各部门缺陷密度的环比变化。"
                    + "结果包含原始值 + 变化量 + 变化率(%)。")
    public io.agentscope.core.message.ToolResultBlock compareRatio(
            @io.agentscope.core.tool.ToolParam(
                            name = "csvPathA",
                            description = "基准期 CSV 路径")
                    String csvPathA,
            @io.agentscope.core.tool.ToolParam(
                            name = "csvPathB",
                            description = "对比期 CSV 路径")
                    String csvPathB,
            @io.agentscope.core.tool.ToolParam(
                            name = "joinKeyColumn",
                            description = "用于 join 的键列名,如 \"部门\"")
                    String joinKeyColumn,
            @io.agentscope.core.tool.ToolParam(
                            name = "valueColumn",
                            description = "要对比的数值列名,如 \"缺陷密度\"")
                    String valueColumn,
            @io.agentscope.core.tool.ToolParam(
                            name = "labelA",
                            description = "基准期标签,如 \"2026Q1\"",
                            required = false)
                    String labelA,
            @io.agentscope.core.tool.ToolParam(
                            name = "labelB",
                            description = "对比期标签,如 \"2026Q2\"",
                            required = false)
                    String labelB) {

        if (csvPathA == null || csvPathA.isBlank() || csvPathB == null || csvPathB.isBlank()) {
            return io.agentscope.core.message.ToolResultBlock.text(
                    "data_compare_ratio: csvPathA 和 csvPathB 都需要。");
        }
        String labA = (labelA == null || labelA.isBlank()) ? "基准期" : labelA;
        String labB = (labelB == null || labelB.isBlank()) ? "对比期" : labelB;

        String code =
                "import pandas as pd\n"
                        + "df_a = pd.read_csv(" + pyStr(csvPathA) + ")[[" + pyStr(joinKeyColumn) + ", " + pyStr(valueColumn) + "]]\n"
                        + "df_b = pd.read_csv(" + pyStr(csvPathB) + ")[[" + pyStr(joinKeyColumn) + ", " + pyStr(valueColumn) + "]]\n"
                        + "df_a = df_a.rename(columns={" + pyStr(valueColumn) + ": " + pyStr(labA) + "})\n"
                        + "df_b = df_b.rename(columns={" + pyStr(valueColumn) + ": " + pyStr(labB) + "})\n"
                        + "m = df_a.merge(df_b, on=" + pyStr(joinKeyColumn) + ")\n"
                        + "m['变化量'] = (m[" + pyStr(labB) + "] - m[" + pyStr(labA) + "]).round(2)\n"
                        + "m['变化率(%)'] = ((m[" + pyStr(labB) + "] / m[" + pyStr(labA) + "] - 1) * 100).round(2)\n"
                        + "print(m.to_markdown(index=False))\n";
        return runPython(code);
    }

    // ======================================================================
    // pivot — 透视表
    // ======================================================================

    @io.agentscope.core.tool.Tool(
            name = "data_pivot",
            description = "对 CSV 数据做透视表。例: 行为部门、列为季度、值为缺陷密度均值。")
    public io.agentscope.core.message.ToolResultBlock pivot(
            @io.agentscope.core.tool.ToolParam(
                            name = "csvPath",
                            description = "CSV artifact 路径")
                    String csvPath,
            @io.agentscope.core.tool.ToolParam(
                            name = "indexColumn",
                            description = "透视表行索引列,如 \"部门\"")
                    String indexColumn,
            @io.agentscope.core.tool.ToolParam(
                            name = "columnsColumn",
                            description = "透视表列名,如 \"季度\"")
                    String columnsColumn,
            @io.agentscope.core.tool.ToolParam(
                            name = "valueColumn",
                            description = "值列,如 \"缺陷密度\"")
                    String valueColumn,
            @io.agentscope.core.tool.ToolParam(
                            name = "aggFn",
                            description = "聚合函数,默认 mean",
                            required = false)
                    String aggFn) {

        if (csvPath == null || csvPath.isBlank()) {
            return io.agentscope.core.message.ToolResultBlock.text("data_pivot: csvPath 为空。");
        }
        String fn = (aggFn == null || aggFn.isBlank()) ? "mean" : aggFn;

        String code =
                "import pandas as pd\n"
                        + "df = pd.read_csv(" + pyStr(csvPath) + ")\n"
                        + "p = df.pivot_table(index=" + pyStr(indexColumn) + ", columns=" + pyStr(columnsColumn)
                        + ", values=" + pyStr(valueColumn) + ", aggfunc='" + fn + "')\n"
                        + "print(p.to_markdown())\n";
        return runPython(code);
    }

    // ======================================================================
    // distribution — 单列统计描述
    // ======================================================================

    @io.agentscope.core.tool.Tool(
            name = "data_distribution",
            description = "计算 CSV 某数值列的分布统计: count / mean / std / min / 25% / 50% / 75% / max。"
                    + "结果直接返回 markdown 表。")
    public io.agentscope.core.message.ToolResultBlock distribution(
            @io.agentscope.core.tool.ToolParam(
                            name = "csvPath",
                            description = "CSV artifact 路径")
                    String csvPath,
            @io.agentscope.core.tool.ToolParam(
                            name = "valueColumn",
                            description = "数值列名,如 \"缺陷密度\"")
                    String valueColumn) {

        if (csvPath == null || csvPath.isBlank()) {
            return io.agentscope.core.message.ToolResultBlock.text("data_distribution: csvPath 为空。");
        }
        String code =
                "import pandas as pd\n"
                        + "df = pd.read_csv(" + pyStr(csvPath) + ")\n"
                        + "print(df[" + pyStr(valueColumn) + "].describe().to_markdown())\n";
        return runPython(code);
    }

    // ======================================================================
    // Transport (same as PythonExecTool, but we own the code, not the LLM)
    // ======================================================================

    private io.agentscope.core.message.ToolResultBlock runPython(String code) {
        List<String> command = buildCommand();
        if (command == null) {
            return io.agentscope.core.message.ToolResultBlock.text(
                    "data_primitives 不可用: 未启用 sandbox profile 且无本地 python3。");
        }
        long start = System.currentTimeMillis();
        Process p;
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(false);
            p = pb.start();
        } catch (IOException e) {
            return io.agentscope.core.message.ToolResultBlock.text(
                    "data_primitives 启动失败: " + e.getMessage());
        }
        try (OutputStream stdin = p.getOutputStream()) {
            stdin.write(code.getBytes(StandardCharsets.UTF_8));
            stdin.flush();
        } catch (IOException e) {
            log.warn("data_primitives: failed writing code to stdin: {}", e.getMessage());
        }
        boolean finished;
        try {
            finished = p.waitFor(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            return io.agentscope.core.message.ToolResultBlock.text("data_primitives 被中断。");
        }
        if (!finished) {
            p.destroyForcibly();
            return io.agentscope.core.message.ToolResultBlock.text(
                    "data_primitives 超时(" + DEFAULT_TIMEOUT_SECONDS + "s),进程已终止。");
        }
        String stdout = readStream(p.getInputStream());
        String stderr = readStream(p.getErrorStream());
        int exit = p.exitValue();
        long elapsed = System.currentTimeMillis() - start;
        log.info("data_primitives done: exit={} elapsed={}ms", exit, elapsed);
        if (exit != 0) {
            return io.agentscope.core.message.ToolResultBlock.text(
                    "[data_primitives] exit=" + exit + " elapsed=" + elapsed + "ms\n\n"
                            + "─── stdout ──\n" + (stdout.isEmpty() ? "(空)" : stdout) + "\n"
                            + "─── stderr ──\n" + (stderr.isEmpty() ? "(空)" : stderr));
        }
        return io.agentscope.core.message.ToolResultBlock.text(
                "[data_primitives] exit=" + exit + " elapsed=" + elapsed + "ms\n\n" + stdout);
    }

    private List<String> buildCommand() {
        if (sandbox != null && sandbox.isEnabled() && !isBlank(sandbox.getSharedContainerName())) {
            String container = sandbox.getSharedContainerName();
            if (sandbox.isRemoteDockerEnabled() && !isBlank(sandbox.getRemoteDockerSshTarget())) {
                List<String> cmd = new ArrayList<>();
                cmd.add("ssh");
                if (sandbox.getRemoteDockerSshOptions() != null) {
                    cmd.addAll(sandbox.getRemoteDockerSshOptions());
                }
                cmd.add(sandbox.getRemoteDockerSshTarget());
                cmd.add("docker");
                cmd.add("exec");
                cmd.add("-i");
                cmd.add(container);
                cmd.add("python3");
                cmd.add("-");
                return cmd;
            }
            return List.of("docker", "exec", "-i", container, "python3", "-");
        }
        return List.of("python3", "-");
    }

    private static String readStream(java.io.InputStream is) {
        if (is == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            char[] buf = new char[1024];
            int n;
            while ((n = r.read(buf)) > 0) {
                if (sb.length() + n > MAX_OUTPUT_BYTES) {
                    sb.append(buf, 0, MAX_OUTPUT_BYTES - sb.length());
                    sb.append("\n... (输出超过 ").append(MAX_OUTPUT_BYTES).append(" 字节,已截断)");
                    break;
                }
                sb.append(buf, 0, n);
            }
        } catch (IOException ignore) {
            // best effort
        }
        return sb.toString();
    }

    // ======================================================================
    // Python codegen helpers
    // ======================================================================

    /** Quote a string for Python source — single-quoted, escaping single quotes. */
    private static String pyStr(String s) {
        if (s == null) return "''";
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    private static String toPythonList(List<String> items) {
        if (items == null || items.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(pyStr(items.get(i)));
        }
        sb.append("]");
        return sb.toString();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}