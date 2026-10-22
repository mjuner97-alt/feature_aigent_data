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
package com.agentscopea2a.v2.middleware;

import com.agentscopea2a.v2.dimension.AliasResolver;
import com.agentscopea2a.v2.dimension.DimensionState;
import com.agentscopea2a.v2.dimension.DimensionStateManager;
import com.agentscopea2a.v2.dimension.PersonLedgerRepository;
import com.agentscopea2a.v2.dimension.PersonalDimensionResolver;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.middleware.MiddlewareBase;
import reactor.core.publisher.Mono;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * v2 middleware：在 system prompt 中注入维度状态上下文。
 *
 * <p>替代 v1 的 {@code ResponseCacheHook} 维度注入逻辑（缓存逻辑推迟到阶段 5）。
 *
 * <p>工作流程：
 * <ol>
 *   <li>从 {@link RuntimeContext} 加载上一轮的维度状态</li>
 *   <li>使用规则分析处理用户问题（零 LLM 开销）</li>
 *   <li>将维度上下文作为自然语言前缀追加到 system prompt</li>
 * </ol>
 *
 * <p>v2 版本使用 {@link RuntimeContext#put}/{@link RuntimeContext#get} 进行状态持久化，
 * 替代 v1 的 {@code Session}/{@code SessionKey}。
 */
public class DimensionStateMiddleware implements MiddlewareBase {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(DimensionStateMiddleware.class);

    private static final String STATE_KEY = "dimensionState";

    private final DimensionStateManager dimensionStateManager;
    private final int maxChars;
    /** 人员台账口语解析（人员台账维度方案 §3.3b/§4.4，LLM 回退路径兜底注入）；null 跳过。 */
    private final PersonalDimensionResolver personalResolver;

    public DimensionStateMiddleware(DimensionStateManager dimensionStateManager) {
        this(dimensionStateManager, 2000, null);
    }

    public DimensionStateMiddleware(DimensionStateManager dimensionStateManager, int maxChars) {
        this(dimensionStateManager, maxChars, null);
    }

    public DimensionStateMiddleware(DimensionStateManager dimensionStateManager, int maxChars,
                                    PersonalDimensionResolver personalResolver) {
        this.dimensionStateManager = dimensionStateManager;
        this.maxChars = Math.max(0, maxChars);
        this.personalResolver = personalResolver;
    }

    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String systemPrompt) {
        return Mono.fromCallable(() -> {
            try {
                // 提取用户问题：从 RuntimeContext 获取最后一条用户消息
                String userQuestion = extractUserQuestion(ctx);

                if (userQuestion == null || userQuestion.isBlank()) {
                    return systemPrompt;
                }

                // 规则分析 + 继承 + 指代消解 + 组装（纯本地，零 LLM）
                // 用 processQuestionInContext 而非 loadFrom + processQuestion + getCurrentState + saveTo
                // 序列，避免单例 DimensionStateManager 的 currentState 实例字段被并发请求覆盖（P1-2）
                DimensionStateManager.ProcessResult result =
                        dimensionStateManager.processQuestionInContext(ctx, userQuestion);
                DimensionState state = result.newState();

                // 人员台账 enrich（§3.3b/§4.4，LLM 回退兜底）：我X/人名 → state + 统一认证号注入行，
                // state 原地变更（ctx 持同引用，跨轮继承自动生效）
                java.util.List<String> perNoLines = enrichPersonal(state, ctx, userQuestion);

                if (state != null && (state.hasDimensions()
                        || (perNoLines != null && !perNoLines.isEmpty()))) {
                    String dimensionPrefix = formatDimensionContext(state, result.resolvedAliases(),
                            perNoLines);
                    if (maxChars == 0) {
                        return systemPrompt;
                    }
                    if (dimensionPrefix.length() > maxChars) {
                        dimensionPrefix = truncateAtLineBoundary(dimensionPrefix, maxChars);
                    }
                    log.debug("[dim-mw] injected chars={}", dimensionPrefix.length());
                    return systemPrompt + "\n\n" + dimensionPrefix;
                }
                return systemPrompt;
            } catch (RuntimeException e) {
                // 中间件异常不允许打断 chat 链路，降级为无维度上下文注入
                log.error("[dim-mw] dimension context injection failed", e);
                return systemPrompt;
            }
        });
    }

    /**
     * 人员台账 enrich（LLM 回退路径，§3.3b/§4.4）：我X → departments/peerDimension（三选一，
     * 先命中者胜 + warn）/persons；人名扫描唯一命中 → persons + 统一认证号注入行。
     * 直接执行路径（参数契约）在 DirectSkillExecutor.plan，这里只是提示性注入。
     */
    private java.util.List<String> enrichPersonal(DimensionState state, RuntimeContext ctx,
                                                  String userQuestion) {
        if (personalResolver == null || state == null) {
            return java.util.List.of();
        }
        String userId = ctx.getUserId();
        PersonalDimensionResolver.PersonalResolution res = personalResolver.resolve(userQuestion, userId);
        if (res.anyHit() && res.ledger() != null) {
            if (res.myDept() && !res.ledger().depts().isEmpty()) {
                state.setDepartments(merge(state.getDepartments(), res.ledger().depts()));
            }
            if (res.myGroup() && !res.ledger().groups().isEmpty()) {
                setPeerIfAbsent(state, DimensionState.PeerDimensionType.TEAM, res.ledger().groups());
            }
            if (res.myApp() && !res.ledger().apps().isEmpty()) {
                setPeerIfAbsent(state, DimensionState.PeerDimensionType.APPLICATION, res.ledger().apps());
            }
            if (res.myProductLine() && !res.ledger().productLines().isEmpty()) {
                setPeerIfAbsent(state, DimensionState.PeerDimensionType.PRODUCT_LINE,
                        res.ledger().productLines());
            }
            if (res.myself() && res.ledger().name() != null) {
                state.setPersons(merge(state.getPersons(), List.of(res.ledger().name())));
            }
        }
        // §4.4 人名扫描（唯一命中直出统一认证号；重名不反问——直接执行轨已覆盖主场景）
        java.util.List<String> perNoLines = new java.util.ArrayList<>();
        String masked = DimensionStateManager.maskForPersonScan(userQuestion);
        for (String name : personalResolver.scanNames(masked)) {
            java.util.List<PersonLedgerRepository.PersonIdentity> identities =
                    personalResolver.resolveName(name);
            if (identities.size() == 1 && identities.get(0).perNo() != null) {
                PersonLedgerRepository.PersonIdentity identity = identities.get(0);
                perNoLines.add("「" + name + "」→ 统一认证号 " + identity.perNo()
                        + (identity.depts().isEmpty() ? "" : "（" + String.join(" / ", identity.depts()) + "）"));
                state.setPersons(merge(state.getPersons(), List.of(name)));
            } else if (identities.size() > 1) {
                perNoLines.add("「" + name + "」存在重名（" + identities.size() + "人），"
                        + "如用户指具体人员请先向其确认");
            }
        }
        return perNoLines;
    }

    private static void setPeerIfAbsent(DimensionState state, DimensionState.PeerDimensionType type,
                                        java.util.List<String> values) {
        DimensionState.PeerDimension existing = state.getPeerDimension();
        if (existing != null && !existing.isEmpty()) {
            log.warn("[dim-mw] peerDimension 已有 {}={}, 我X命中 {} 不覆盖（三选一先命中者胜）",
                    existing.getType(), existing.getValues(), type);
            return;
        }
        state.setPeerDimension(new DimensionState.PeerDimension(type, values));
    }

    private static java.util.List<String> merge(java.util.List<String> base, java.util.List<String> additions) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        if (base != null) {
            base.forEach(v -> {
                if (v != null && !v.isBlank()) {
                    out.add(v);
                }
            });
        }
        for (String v : additions) {
            if (v != null && !v.isBlank()) {
                out.add(v);
            }
        }
        return new java.util.ArrayList<>(out);
    }

    /**
     * 从 RuntimeContext 提取用户问题文本。
     *
     * <p>v2 RuntimeContext 不提供 getLastUserMessage()，通过 {@code ctx.get("lastQuestion", String.class)}
     * 读取（由 {@code V2ChatStreamServiceImpl.buildRuntimeContext} 在请求开始时写入）。fallback 返回空
     * 字符串，使调用方跳过维度状态处理。
     */
    private String extractUserQuestion(RuntimeContext ctx) {
        // 方式1: 从 RuntimeContext extra map 获取（V2ChatStreamServiceImpl put "lastQuestion"）
        String input = ctx.get("lastQuestion", String.class);
        if (input != null && !input.isBlank()) {
            return input;
        }
        // 方式2: 从 conversationId 获取（仅作 fallback）
        String conversationId = ctx.getSessionId();
        return conversationId != null ? "" : null;
    }

    private String formatDimensionContext(
            DimensionState state, java.util.List<AliasResolver.ResolvedAlias> resolvedAliases,
            java.util.List<String> perNoLines) {
        StringBuilder sb = new StringBuilder("<dimension_context>\n");
        sb.append("当前对话维度上下文。以下维度值是系统根据用户口语化表述确定性解析出的标准名（非猜测），"
                + "视为用户本轮提问的确定查询条件：构造查询参数时必须直接采用，"
                + "用户原文中的口语词/简称一律视为已映射到此（口语词与标准名的对应关系见下方映射列表）；"
                + "禁止向用户追问「具体指哪个部门/产品线/应用」或要求用户确认口径。\n\n");

        if (resolvedAliases != null && !resolvedAliases.isEmpty()) {
            sb.append("同义词解析映射（口语词 → 维度：标准名）：\n");
            Set<String> seen = new LinkedHashSet<>();
            for (AliasResolver.ResolvedAlias hit : resolvedAliases) {
                String label = switch (hit.dimension()) {
                    case TEAM -> "组";
                    case APPLICATION -> "应用";
                    case PRODUCT_LINE -> "产品线";
                    case REQUIREMENT -> "需求项";
                    case SUB_REQUIREMENT -> "需求子条目";
                };
                seen.add("- 口语词「" + hit.alias() + "」→ " + label + "维度：" + hit.standardName());
            }
            seen.forEach(line -> sb.append(line).append("\n"));
            sb.append("\n");
        }

        if (state.getTimeDimension() != null && !state.getTimeDimension().isEmpty()) {
            String label = state.getTimeDimension().getType()
                    == DimensionState.TimeDimensionType.QUARTER ? "季度" : "版本计划";
            sb.append(label).append("：")
                    .append(String.join("、", state.getTimeDimension().getValues()))
                    .append("\n");
        }
        if (state.getDepartments() != null && !state.getDepartments().isEmpty()) {
            sb.append("部门：").append(String.join("、", state.getDepartments())).append("\n");
        }
        if (state.getPeerDimension() != null && !state.getPeerDimension().isEmpty()) {
            String label = switch (state.getPeerDimension().getType()) {
                case TEAM -> "组";
                case APPLICATION -> "应用";
                case PRODUCT_LINE -> "产品线";
                case REQUIREMENT -> "需求项";
                case SUB_REQUIREMENT -> "需求子条目";
            };
            sb.append(label).append("：")
                    .append(String.join("、", state.getPeerDimension().getValues()))
                    .append("\n");
        }
        if (state.getPersons() != null && !state.getPersons().isEmpty()) {
            sb.append("人：").append(String.join("、", state.getPersons())).append("\n");
        }
        if (perNoLines != null && !perNoLines.isEmpty()) {
            sb.append("\n人员解析（确定性结果，非猜测）：\n");
            perNoLines.forEach(line -> sb.append("- ").append(line).append("\n"));
            sb.append("涉及上述人员的查询，参数 perNo 直接使用对应统一认证号，"
                    + "禁止向用户追问或要求确认。\n");
        }
        sb.append("</dimension_context>");
        return sb.toString() + "\n\n";
    }

    private static String truncateAtLineBoundary(String value, int maxChars) {
        int boundary = value.lastIndexOf('\n', maxChars);
        if (boundary <= 0) {
            return value.substring(0, maxChars) + "\n...(维度上下文已按预算截断)";
        }
        return value.substring(0, boundary) + "\n...(维度上下文已按预算截断)";
    }
}
