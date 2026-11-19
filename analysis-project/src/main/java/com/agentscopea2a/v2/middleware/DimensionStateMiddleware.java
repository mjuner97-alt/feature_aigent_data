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
import com.agentscopea2a.v2.dimension.TimeRangeResolver;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.middleware.MiddlewareBase;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
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

                // 人员台账 enrich（§3.3b/§4.4，LLM 回退兜底）：我X/人名 → state + 确定性身份，
                // state 原地变更（ctx 持同引用，跨轮继承自动生效）
                PersonalEnrich enrich = enrichPersonal(state, ctx, userQuestion);

                if (state != null && (state.hasDimensions()
                        || !enrich.identities().isEmpty() || !enrich.ambiguityHints().isEmpty())) {
                    String dimensionPrefix = formatDimensionContext(state, result.resolvedAliases(),
                            enrich);
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

    /** 人员 enrich 结构化结果（三行属性式渲染方案 §13.4）：身份解析记录供属性行渲染，重名提示行独立。 */
    record PersonalEnrich(java.util.List<PersonLedgerRepository.PersonIdentity> identities,
                          java.util.List<String> ambiguityHints) {
        static final PersonalEnrich EMPTY = new PersonalEnrich(List.of(), List.of());
    }

    /**
     * 人员台账 enrich（LLM 回退路径，§3.3b/§4.4）：我X → departments/peerDimension（三选一，
     * 先命中者胜 + warn）/persons；人名扫描唯一命中 → persons + 确定性身份。
     * 部门一项全量连带（2026/10/30 部门与上级维度带出门控方案 §2.3，与直执 enrichPersonal
     * 同语义）：我部/我组/我的应用/我的产品线/裸我 均注入台账部门；peer 自身值仍 per-type。
     * 身份记录仅两处产出（拍板 §13.6-1 A）：裸我（userId 即认证号）+ 人名扫描唯一命中；
     * 我部/我组/我的应用/我的产品线命中不出人属性行。
     * 直接执行路径（参数契约）在 DirectSkillExecutor.plan，这里只是提示性注入。
     */
    private PersonalEnrich enrichPersonal(DimensionState state, RuntimeContext ctx,
                                          String userQuestion) {
        if (personalResolver == null || state == null) {
            return PersonalEnrich.EMPTY;
        }
        String userId = ctx.getUserId();
        PersonalDimensionResolver.PersonalResolution res = personalResolver.resolve(userQuestion, userId);
        java.util.LinkedHashMap<String, PersonLedgerRepository.PersonIdentity> identities =
                new java.util.LinkedHashMap<>();
        java.util.List<String> ambiguityHints = new java.util.ArrayList<>();
        if (res.anyHit() && res.ledger() != null) {
            if (res.myDept() && !res.ledger().depts().isEmpty()) {
                state.setDepartments(merge(state.getDepartments(), res.ledger().depts()));
            }
            if (res.myGroup() && !res.ledger().groups().isEmpty()) {
                setPeerIfAbsent(state, DimensionState.PeerDimensionType.TEAM, res.ledger().groups());
                state.setDepartments(merge(state.getDepartments(), res.ledger().depts()));
            }
            if (res.myApp() && !res.ledger().apps().isEmpty()) {
                setPeerIfAbsent(state, DimensionState.PeerDimensionType.APPLICATION, res.ledger().apps());
                state.setDepartments(merge(state.getDepartments(), res.ledger().depts()));
            }
            if (res.myProductLine() && !res.ledger().productLines().isEmpty()) {
                setPeerIfAbsent(state, DimensionState.PeerDimensionType.PRODUCT_LINE,
                        res.ledger().productLines());
                state.setDepartments(merge(state.getDepartments(), res.ledger().depts()));
            }
            if (res.myself()) {
                if (res.ledger().name() != null && !res.ledger().name().isBlank()) {
                    state.setPersons(merge(state.getPersons(), List.of(res.ledger().name())));
                    identities.putIfAbsent(res.ledger().name(),
                            new PersonLedgerRepository.PersonIdentity(res.ledger().name(),
                                    res.ledger().userId(), res.ledger().depts(),
                                    res.ledger().groups(), res.ledger().roles()));
                }
                if (!res.ledger().depts().isEmpty()) {
                    state.setDepartments(merge(state.getDepartments(), res.ledger().depts()));
                }
            }
        }
        // §4.4 人名扫描（唯一命中直出统一认证号；重名不反问——直接执行轨已覆盖主场景）
        String masked = DimensionStateManager.maskForPersonScan(userQuestion);
        for (String name : personalResolver.scanNames(masked)) {
            java.util.List<PersonLedgerRepository.PersonIdentity> identities0 =
                    personalResolver.resolveName(name);
            if (identities0.size() == 1 && identities0.get(0).perNo() != null) {
                PersonLedgerRepository.PersonIdentity identity = identities0.get(0);
                identities.putIfAbsent(name, identity);
                state.setPersons(merge(state.getPersons(), List.of(name)));
            } else if (identities0.size() > 1) {
                ambiguityHints.add("「" + name + "」存在重名（" + identities0.size() + "人），"
                        + "如用户指具体人员请先向其确认");
            }
        }
        return new PersonalEnrich(java.util.List.copyOf(identities.values()),
                java.util.List.copyOf(ambiguityHints));
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
            PersonalEnrich enrich) {
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
                    case PROJECT -> "项目编号";
                };
                seen.add("- 口语词「" + hit.alias() + "」→ " + label + "维度：" + hit.standardName());
            }
            seen.forEach(line -> sb.append(line).append("\n"));
            sb.append("\n");
        }

        // 维度值统一为 "- 标签：值" 列表（时间范围段独立置后，不插在维度值中间）
        if (state.getTimeDimension() != null && !state.getTimeDimension().isEmpty()) {
            String label = state.getTimeDimension().getType()
                    == DimensionState.TimeDimensionType.QUARTER ? "季度" : "版本计划";
            sb.append("- ").append(label).append("：")
                    .append(String.join("、", state.getTimeDimension().getValues()))
                    .append("\n");
        }
        if (state.getDepartments() != null && !state.getDepartments().isEmpty()) {
            sb.append("- 部门：").append(String.join("、", state.getDepartments())).append("\n");
        }
        if (state.getPeerDimension() != null && !state.getPeerDimension().isEmpty()) {
            String label = switch (state.getPeerDimension().getType()) {
                case TEAM -> "组";
                case APPLICATION -> "应用";
                case PRODUCT_LINE -> "产品线";
                case REQUIREMENT -> "需求项";
                case SUB_REQUIREMENT -> "需求子条目";
                case PROJECT -> "项目编号";
            };
            sb.append("- ").append(label).append("：")
                    .append(String.join("、", state.getPeerDimension().getValues()))
                    .append("\n");
        }
        // 人维度三行属性式（三行属性式渲染方案 §13.2，拍板③ B）：身份解析出的确定性身份
        // （裸我/人名扫描唯一命中）渲染 姓名/统一认证号/岗位角色；无身份的 persons 退回单行
        appendPersonSection(sb, state.getPersons(), enrich.identities());
        appendTimeRangeSection(sb, state.getTimeRangeAnchors());
        if (enrich.ambiguityHints() != null && !enrich.ambiguityHints().isEmpty()) {
            sb.append("\n人员解析（确定性结果，非猜测）：\n");
            enrich.ambiguityHints().forEach(line -> sb.append("- ").append(line).append("\n"));
        }
        sb.append("</dimension_context>");
        return sb.toString() + "\n\n";
    }

    /**
     * 人维度渲染（三行属性式渲染方案 §13.2，拍板③ B + 拍板④ B）：身份解析出结果的 persons
     * 渲染 姓名/统一认证号/岗位角色 三行属性行（角色空省略岗位角色行）；无身份解析结果的
     * persons（resolver 缺失/台账查不到/兜底降级）退回单行 "- 人：x"，人维度不丢。
     */
    private static void appendPersonSection(StringBuilder sb, java.util.List<String> persons,
                                            java.util.List<PersonLedgerRepository.PersonIdentity> identities) {
        java.util.Set<String> identified = new LinkedHashSet<>();
        if (identities != null) {
            for (PersonLedgerRepository.PersonIdentity identity : identities) {
                if (identity.name() == null || identity.name().isBlank()) {
                    continue;
                }
                identified.add(identity.name());
                sb.append("- 姓名：").append(identity.name()).append("\n");
                if (identity.perNo() != null && !identity.perNo().isBlank()) {
                    sb.append("- 统一认证号：").append(identity.perNo()).append("\n");
                }
                if (identity.roles() != null && !identity.roles().isEmpty()) {
                    sb.append("- 岗位角色：").append(String.join("、", identity.roles())).append("\n");
                }
            }
        }
        if (persons != null && !persons.isEmpty()) {
            java.util.List<String> unidentified = persons.stream()
                    .filter(p -> p != null && !p.isBlank() && !identified.contains(p))
                    .toList();
            if (!unidentified.isEmpty()) {
                sb.append("- 人：").append(String.join("、", unidentified)).append("\n");
            }
        }
    }

    /**
     * 时间范围段（dimension_context 方案 §12，拍板③ 全渲染+标注）：锚点渲染期实时
     * {@link TimeRangeResolver#resolve} 出 [start, end]——日期随"今天"漂移，绝不落状态；
     * 段内统一标注"脚本仅支持单范围，落参只取第一个范围"（模型可见完整上下文但明确知道
     * 第二个范围不会落参，避免自传参/自拼对比）。
     */
    private static void appendTimeRangeSection(StringBuilder sb, List<String> anchors) {
        if (anchors == null || anchors.isEmpty()) {
            return;
        }
        sb.append("\n时间范围（执行期确定性解析，直接使用无需自行换算日期；"
                + "脚本仅支持单范围，落参只取第一个范围）：\n");
        LocalDate today = LocalDate.now(TimeRangeResolver.ZONE);
        for (String anchor : anchors) {
            if (anchor == null || anchor.isBlank()) {
                continue;
            }
            if (TimeRangeResolver.isAmbiguousAnchor(anchor)) {
                // 正常应由系统短路反问（拍板①），此处为锚点漏进状态时的兜底提示行
                sb.append("- ").append(describeAnchor(anchor))
                        .append("——方向待确认（历史/未来），请先向用户反问\n");
                continue;
            }
            String[] range = anchor.startsWith("DATE:")
                    ? TimeRangeResolver.resolveDateRange(anchor)
                    : TimeRangeResolver.resolve(anchor, today);
            if (range != null) {
                sb.append("- ").append(describeAnchor(anchor)).append(" → ")
                        .append(range[0]).append(" ~ ").append(range[1]).append("\n");
            } else {
                // 里程碑透传型（T-n/R-n/投产周/交付周至投产周）：锚点字面量即脚本收到的值
                sb.append("- ").append(describeAnchor(anchor))
                        .append("（执行期由脚本语义解释，勿自行换算日期）\n");
            }
        }
    }

    /** 锚点 → 口语描述（确定性映射，与 TimeRangeResolver 锚点白名单同口径）。 */
    private static String describeAnchor(String anchor) {
        return switch (anchor) {
            case "THIS_WEEK" -> "本周";
            case "LAST_WEEK" -> "上周";
            case "TODAY" -> "今天";
            case "THIS_MONTH" -> "本月";
            case "THIS_YEAR" -> "今年";
            case TimeRangeResolver.PROD_WEEK -> "投产周";
            case TimeRangeResolver.DELIV_TO_PROD -> "交付周至投产周";
            default -> describeParametricAnchor(anchor);
        };
    }

    private static String describeParametricAnchor(String anchor) {
        if (anchor.startsWith("LAST:")) {
            String tail = anchor.substring("LAST:".length());
            if (tail.endsWith("d")) return "近" + trimNum(tail) + "天（历史）";
            if (tail.endsWith("w")) return "近" + trimNum(tail) + "周（历史）";
            if (tail.endsWith("M")) return "近" + trimNum(tail) + "个月（历史）";
        }
        if (anchor.startsWith("NEXT:")) {
            String tail = anchor.substring("NEXT:".length());
            if (tail.endsWith("w")) return "未来" + trimNum(tail) + "周";
            if (tail.endsWith("M")) return "未来" + trimNum(tail) + "个月";
        }
        if (anchor.startsWith("UNTIL:")) {
            return "截至 " + anchor.substring("UNTIL:".length());
        }
        if (anchor.startsWith("DATE:")) {
            return "日期区间";
        }
        if (anchor.startsWith("AMBIGUOUS:WEEKS:")) {
            return "「近" + anchor.substring("AMBIGUOUS:WEEKS:".length()) + "周」";
        }
        if (anchor.startsWith("AMBIGUOUS:MONTHS:")) {
            return "「近" + anchor.substring("AMBIGUOUS:MONTHS:".length()) + "个月」";
        }
        if ("AMBIGUOUS:PROD".equals(anchor)) {
            return "「投产前」（口径待确认）";
        }
        return anchor;
    }

    private static String trimNum(String tailWithUnit) {
        return tailWithUnit.substring(0, tailWithUnit.length() - 1);
    }

    private static String truncateAtLineBoundary(String value, int maxChars) {
        int boundary = value.lastIndexOf('\n', maxChars);
        if (boundary <= 0) {
            return value.substring(0, maxChars) + "\n...(维度上下文已按预算截断)";
        }
        return value.substring(0, boundary) + "\n...(维度上下文已按预算截断)";
    }
}
