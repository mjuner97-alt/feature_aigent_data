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
package com.agentscopea2a.v2.tools;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Inline arithmetic tool. Forces all add/sub/mul/div/pct operations through BigDecimal
 * to prevent small-parameter LLMs from making arithmetic mistakes on seemingly trivial
 * calculations like "23.1 - 13.1".
 *
 * <p><b>Why BigDecimal.</b> {@code 23.1 + 13.1} in {@code double} yields {@code 36.200000000000004}.
 * The same expression via {@link BigDecimal#valueOf(double)} + {@link BigDecimal#add} yields
 * {@code 36.2}. This is the same class of error the LLM makes when心算 - precision drift -
 * so we eliminate it at the JVM layer.
 *
 * <p><b>Why a dedicated tool.</b> Even with prompt rules saying "don't心算", a small-parameter
 * LLM in an internal network still hallucinates arithmetic results. Routing every arithmetic
 * operation through this tool turns a prompt-engineering problem into a mechanical guarantee:
 * the LLM either calls {@code arith} or it doesn't compute - there's no "I'll just do it in my
 * head" path.
 *
 * <p><b>No sandbox dependency.</b> Pure JVM computation, runs in-process. No SSH, no docker,
 * no filesystem. Returns in microseconds.
 *
 * <p><b>Not in {@code tool_router}.</b> Registered directly on each agent's Toolkit (like
 * {@code python_exec} / {@code skill_save}) so the LLM calls it in one step instead of
 * {@code toolMetaInfo} + {@code router_tool}.
 *
 * <p><b>Bean wiring:</b> Created by {@link com.agentscopea2a.v2.config.V2ToolConfig} - not
 * component-scanned.
 */
public class ArithTool {

    private static final Logger log = LoggerFactory.getLogger(ArithTool.class);

    /** Scale for division / percentage - 10 digits after decimal, half-up rounding. */
    private static final int DIV_SCALE = 10;

    @Tool(
            name = "arith",
            description =
                    "精确算术运算。所有加减乘除/百分比必须走此工具,禁止心算。"
                            + "op=add 求和; op=sub 第一个数减后面所有数; op=mul 求积; "
                            + "op=div 第一个数除以后面所有数; op=pct numbers[0]/numbers[1]*100。"
                            + "内部用 BigDecimal,无浮点误差。"
                            + "返回单行 result,不是表格。")
    public ToolResultBlock arith(
            @ToolParam(
                            name = "op",
                            description = "运算: add / sub / mul / div / pct")
                    String op,
            @ToolParam(
                            name = "numbers",
                            description =
                                    "数字列表。add/mul 至少 1 个; sub/div 至少 2 个; pct 恰好 2 个")
                    List<Double> numbers) {

        if (op == null || op.isBlank()) {
            return ToolResultBlock.text(
                    "arith 拒绝执行: op 为空,必须是 add/sub/mul/div/pct 之一。");
        }
        if (numbers == null || numbers.isEmpty()) {
            return ToolResultBlock.text(
                    "arith 拒绝执行: numbers 为空,至少需要一个数字。");
        }

        String normalizedOp = op.trim().toLowerCase();
        try {
            BigDecimal result = compute(normalizedOp, numbers);
            String formatted = formatResult(result);
            log.info("arith: op={} numbers={} -> {}", normalizedOp, numbers, formatted);
            return ToolResultBlock.text(
                    "[arith] op=" + normalizedOp + " numbers=" + numbers + "\nresult: " + formatted);
        } catch (IllegalArgumentException e) {
            log.warn("arith rejected: op={} numbers={} msg={}", normalizedOp, numbers, e.getMessage());
            return ToolResultBlock.text("arith 拒绝执行: " + e.getMessage());
        }
    }

    // ----------------------------------------------------------------------
    // Computation
    // ----------------------------------------------------------------------

    private static BigDecimal compute(String op, List<Double> numbers) {
        // BigDecimal.valueOf(double) uses Double.toString internally, which gives us
        // the short decimal representation (23.1 -> "23.1") instead of the binary
        // expansion (new BigDecimal(23.1) -> 23.099999999999998...). This is the
        // standard fix for the double->BigDecimal precision trap.
        List<BigDecimal> decimals =
                numbers.stream().map(BigDecimal::valueOf).toList();

        switch (op) {
            case "add":
                // sum of all - identity element is ZERO
                return decimals.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            case "sub":
                if (decimals.size() < 2) {
                    throw new IllegalArgumentException("sub 至少需要 2 个数字,收到 " + decimals.size());
                }
                BigDecimal subResult = decimals.get(0);
                for (int i = 1; i < decimals.size(); i++) {
                    subResult = subResult.subtract(decimals.get(i));
                }
                return subResult;
            case "mul":
                // product of all - identity element is ONE
                return decimals.stream().reduce(BigDecimal.ONE, BigDecimal::multiply);
            case "div":
                if (decimals.size() < 2) {
                    throw new IllegalArgumentException("div 至少需要 2 个数字,收到 " + decimals.size());
                }
                BigDecimal divResult = decimals.get(0);
                for (int i = 1; i < decimals.size(); i++) {
                    BigDecimal d = decimals.get(i);
                    if (d.signum() == 0) {
                        throw new IllegalArgumentException("div 第 " + (i + 1) + " 个数字是 0,除数为 0");
                    }
                    divResult = divResult.divide(d, DIV_SCALE, RoundingMode.HALF_UP);
                }
                return divResult;
            case "pct":
                if (decimals.size() != 2) {
                    throw new IllegalArgumentException(
                            "pct 恰好需要 2 个数字 (part, total),收到 " + decimals.size());
                }
                if (decimals.get(1).signum() == 0) {
                    throw new IllegalArgumentException("pct 第二个数字 (total) 是 0");
                }
                return decimals
                        .get(0)
                        .divide(decimals.get(1), DIV_SCALE, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100));
            default:
                throw new IllegalArgumentException(
                        "未知 op: " + op + ",必须是 add/sub/mul/div/pct 之一");
        }
    }

    // ----------------------------------------------------------------------
    // Formatting
    // ----------------------------------------------------------------------

    /**
     * Strips trailing zeros and avoids scientific notation. {@code 41.20000 -> "41.2"},
     * {@code 10.0 -> "10"}, {@code 0.0000001 -> "0.0000001"}.
     */
    private static String formatResult(BigDecimal result) {
        BigDecimal stripped = result.stripTrailingZeros();
        // stripTrailingZeros can leave a negative scale (e.g. "1E+2"), which toPlainString
        // handles correctly but is ugly. Re-set to 0 for integer-valued results.
        if (stripped.scale() < 0) {
            stripped = stripped.setScale(0, RoundingMode.DOWN);
        }
        return stripped.toPlainString();
    }
}
