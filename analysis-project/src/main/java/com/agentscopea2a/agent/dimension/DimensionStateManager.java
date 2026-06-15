/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.agentscopea2a.agent.dimension;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.session.Session;
import io.agentscope.core.state.SessionKey;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 维度状态管理器，负责多轮对话中维度上下文的继承、指代消解、组装和提取。
 *
 * <p>实现 {@link io.agentscope.core.state.StateModule} 接口，支持通过 Session 进行持久化。
 *
 * <p>使用方式：
 *
 * <pre>{@code
 * DimensionStateManager manager = new DimensionStateManager(llmService);
 *
 * // 处理用户问题（继承维度 + 组装）
 * String fullQuestion = manager.processQuestion(userQuestion);
 *
 * // 调用 Agent ...
 *
 * // 回答后更新维度状态
 * manager.updateFromAnswer(answer);
 *
 * // 持久化
 * sessionManager.saveSession();
 * }</pre>
 */
public class DimensionStateManager implements io.agentscope.core.state.StateModule {

    private static final String STATE_KEY = "dimensionState";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final LlmDimensionService llmService;
    private DimensionState currentState;

    /**
     * 创建维度状态管理器。
     *
     * @param llmService LLM 维度分析服务，可为 null（仅 {@link #updateFromAnswer} 需要）
     */
    public DimensionStateManager(LlmDimensionService llmService) {
        this.llmService = llmService;
    }

    // ==================== StateModule 实现 ====================

    @Override
    public void saveTo(Session session, SessionKey sessionKey) {
        if (currentState != null) {
            session.save(sessionKey, STATE_KEY, currentState);
        }
    }

    @Override
    public void loadFrom(Session session, SessionKey sessionKey) {
        Optional<DimensionState> loaded = session.get(sessionKey, STATE_KEY, DimensionState.class);
        loaded.ifPresent(state -> this.currentState = state);
    }

    // ==================== 核心流程 ====================

    /**
     * 处理用户问题：规则分析 → 继承 → 指代消解 → 组装
     *
     * <p>使用纯正则规则分析问题，零 LLM 开销。维度具体值由 {@link #updateFromAnswer(String)} 的 LLM
     * 从回答中提取并修正。
     *
     * @param userQuestion 用户原始问题
     * @return 组装了维度上下文的完整问题
     */
    public String processQuestion(String userQuestion) {
        // 1. Rule-based question analysis (zero LLM overhead)
        QuestionAnalysis analysis = analyzeQuestionRuleBased(userQuestion);

        // 2. inherit dimensions based on detected level
        DimensionState inherited = inheritDimensions(currentState, analysis);

        // 3. update current state
        this.currentState = inherited;

        // 4. reference resolution ("那个组" → "渠道组")
        String resolvedQuestion = resolveReference(inherited, analysis, userQuestion);

        // 5. assemble full question
        return assembleQuestion(inherited, resolvedQuestion);
    }

    /**
     * 异步处理用户问题（内部同步执行，因为规则分析不涉及 IO）。
     *
     * @param userQuestion 用户原始问题
     * @return 组装了维度上下文的完整问题的 Mono
     */
    public Mono<String> processQuestionAsync(String userQuestion) {
        return Mono.fromCallable(() -> processQuestion(userQuestion));
    }

    /**
     * 回答后更新维度状态。
     *
     * @param answer LLM 的回答
     */
    public void updateFromAnswer(String answer) {
        DimensionState extracted = extractDimensions(answer);
        this.currentState = mergeExtracted(this.currentState, extracted);
    }

    /**
     * 异步更新维度状态。
     *
     * @param answer LLM 的回答
     * @return 完成信号的 Mono
     */
    public Mono<Void> updateFromAnswerAsync(String answer) {
        return extractDimensionsAsync(answer)
                .doOnNext(
                        extracted -> {
                            this.currentState = mergeExtracted(this.currentState, extracted);
                        })
                .then();
    }

    /**
     * 获取当前维度状态（只读）。
     */
    public DimensionState getCurrentState() {
        return currentState;
    }

    /**
     * 重置维度状态（清空所有维度）。
     */
    public void reset() {
        this.currentState = null;
    }

    // ==================== 步骤①：规则驱动的问题分析 ====================

    private static final Pattern REF_TIME = Pattern.compile("这个季度|那个季度|这个月|那个月|这个版本|那个版本");
    private static final Pattern REF_DEPT = Pattern.compile("这个部门|那个部门");
    private static final Pattern REF_TEAM = Pattern.compile("这个组|那个组");
    private static final Pattern REF_APP = Pattern.compile("这个应用|那个应用");
    private static final Pattern REF_PRODUCT = Pattern.compile("这个产品线|那个产品线");
    private static final Pattern REF_REQUIREMENT = Pattern.compile("这个需求项|那个需求项");

    private static final Pattern EXPLICIT_VERSION = Pattern.compile("(\\d{4}年\\d{1,2}月份版本)");
    private static final Pattern EXPLICIT_QUARTER = Pattern.compile("(\\d{4}年\\d{1,2}季度)");
    private static final Pattern EXPLICIT_MONTH = Pattern.compile("(\\d{1,2})月(?!份)");
    private static final Pattern EXPLICIT_SHORT_QUARTER = Pattern.compile("(\\d{1,2})季度");
    private static final Pattern EXPLICIT_DEPT = Pattern.compile("(杭州开发[一二三四五]部)");
    private static final Pattern EXPLICIT_APP = Pattern.compile("(F-[A-Za-z][A-Za-z0-9-]*)");
    private static final Pattern EXPLICIT_TEAM = Pattern.compile("([一-龥]{2}组)");
    private static final Pattern EXPLICIT_PRODUCT_LINE = Pattern.compile("([一-龥]{2}产品线)");
    private static final Pattern EXPLICIT_REQUIREMENT = Pattern.compile("([一-龥]{2}需求项)");

    public QuestionAnalysis analyzeQuestionRuleBased(String userQuestion) {
        QuestionAnalysis analysis = new QuestionAnalysis();

        // 1. detect reference
        QuestionAnalysis.ReferenceType refType = detectReferenceType(userQuestion);
        analysis.setHasReference(refType != null);
        analysis.setReferenceType(refType);

        // 2. detect level
        QuestionAnalysis.QuestionLevel level = detectLevel(userQuestion, refType);
        analysis.setLevel(level);
        analysis.setCauseAnalysis(level == QuestionAnalysis.QuestionLevel.CAUSE);

        // 3. extract explicit dimensions
        QuestionAnalysis.ExplicitDimensions explicit = extractExplicitDimensions(userQuestion);
        analysis.setExplicitDimensions(explicit);
        explicit.build();

        return analysis;
    }

    private QuestionAnalysis.ReferenceType detectReferenceType(String q) {
        if (REF_TIME.matcher(q).find()) return QuestionAnalysis.ReferenceType.TIME;
        if (REF_DEPT.matcher(q).find()) return QuestionAnalysis.ReferenceType.DEPARTMENT;
        if (REF_TEAM.matcher(q).find()) return QuestionAnalysis.ReferenceType.TEAM;
        if (REF_APP.matcher(q).find()) return QuestionAnalysis.ReferenceType.APPLICATION;
        if (REF_PRODUCT.matcher(q).find()) return QuestionAnalysis.ReferenceType.PRODUCT_LINE;
        if (REF_REQUIREMENT.matcher(q).find()) return QuestionAnalysis.ReferenceType.REQUIREMENT;
        return null;
    }

    private QuestionAnalysis.QuestionLevel detectLevel(
            String q, QuestionAnalysis.ReferenceType refType) {
        if (q.matches(".*原因.*") || q.matches(".*为什么.*"))
            return QuestionAnalysis.QuestionLevel.CAUSE;
        if (q.matches(".*过去.*") || q.matches(".*趋势.*") || q.matches(".*历史.*"))
            return QuestionAnalysis.QuestionLevel.TIME_RANGE;
        if (q.matches(".*谁.*")) return QuestionAnalysis.QuestionLevel.PERSON;
        if (refType == QuestionAnalysis.ReferenceType.TEAM
                || refType == QuestionAnalysis.ReferenceType.APPLICATION
                || refType == QuestionAnalysis.ReferenceType.PRODUCT_LINE
                || refType == QuestionAnalysis.ReferenceType.REQUIREMENT
                || EXPLICIT_APP.matcher(q).find()
                || EXPLICIT_TEAM.matcher(q).find()
                || EXPLICIT_PRODUCT_LINE.matcher(q).find()
                || EXPLICIT_REQUIREMENT.matcher(q).find())
            return QuestionAnalysis.QuestionLevel.PEER;
        if (q.contains("部门") || EXPLICIT_DEPT.matcher(q).find())
            return QuestionAnalysis.QuestionLevel.DEPARTMENT;
        if (refType == QuestionAnalysis.ReferenceType.DEPARTMENT)
            return QuestionAnalysis.QuestionLevel.DEPARTMENT;
        if (EXPLICIT_VERSION.matcher(q).find()
                || EXPLICIT_MONTH.matcher(q).find()
                || EXPLICIT_QUARTER.matcher(q).find()
                || EXPLICIT_SHORT_QUARTER.matcher(q).find()
                || refType == QuestionAnalysis.ReferenceType.TIME)
            return QuestionAnalysis.QuestionLevel.TIME;
        // No dimensions detected — non-dimension question (e.g. "数据服务表清单下载")
        return null;
    }

    private QuestionAnalysis.ExplicitDimensions extractExplicitDimensions(String q) {
        QuestionAnalysis.ExplicitDimensions explicit = new QuestionAnalysis.ExplicitDimensions();
        int year = LocalDate.now().getYear();

        // Full version format: "2026年4月份版本"
        Matcher versionMatcher = EXPLICIT_VERSION.matcher(q);
        if (versionMatcher.find()) {
            explicit.setTimeDimension(
                    new DimensionState.TimeDimension(
                            DimensionState.TimeDimensionType.VERSION,
                            List.of(versionMatcher.group())));
        }

        // Full quarter format: "2026年1季度"
        if (explicit.getTimeDimension() == null) {
            Matcher quarterMatcher = EXPLICIT_QUARTER.matcher(q);
            if (quarterMatcher.find()) {
                explicit.setTimeDimension(
                        new DimensionState.TimeDimension(
                                DimensionState.TimeDimensionType.QUARTER,
                                List.of(quarterMatcher.group())));
            }
        }

        // Short month format: "4月" → construct full version
        if (explicit.getTimeDimension() == null) {
            Matcher monthMatcher = EXPLICIT_MONTH.matcher(q);
            if (monthMatcher.find()) {
                String month = monthMatcher.group(1);
                explicit.setTimeDimension(
                        new DimensionState.TimeDimension(
                                DimensionState.TimeDimensionType.VERSION,
                                List.of(year + "年" + month + "月份版本")));
            }
        }

        // Short quarter format: "1季度" → construct full quarter
        if (explicit.getTimeDimension() == null) {
            Matcher shortQMatcher = EXPLICIT_SHORT_QUARTER.matcher(q);
            if (shortQMatcher.find()) {
                String qNum = shortQMatcher.group(1);
                explicit.setTimeDimension(
                        new DimensionState.TimeDimension(
                                DimensionState.TimeDimensionType.QUARTER,
                                List.of(year + "年" + qNum + "季度")));
            }
        }

        Matcher deptMatcher = EXPLICIT_DEPT.matcher(q);
        if (deptMatcher.find()) {
            explicit.setDepartments(List.of(deptMatcher.group()));
        }

        // 应用（F-xxx）
        Matcher appMatcher = EXPLICIT_APP.matcher(q);
        if (appMatcher.find()) {
            explicit.setPeerDimension(
                    new DimensionState.PeerDimension(
                            DimensionState.PeerDimensionType.APPLICATION,
                            List.of(appMatcher.group())));
        }

        // 组（优先级低于应用）— 排除指代词匹配（"这个组"、"那个组"）
        if (explicit.getPeerDimension() == null) {
            Matcher teamMatcher = EXPLICIT_TEAM.matcher(q);
            if (teamMatcher.find()) {
                String matched = teamMatcher.group(1);
                if (!matched.startsWith("这个") && !matched.startsWith("那个")) {
                    explicit.setPeerDimension(
                            new DimensionState.PeerDimension(
                                    DimensionState.PeerDimensionType.TEAM,
                                    List.of(teamMatcher.group())));
                }
            }
        }

        // 产品线（优先级低于组和应用）— 排除指代词匹配
        if (explicit.getPeerDimension() == null) {
            Matcher plMatcher = EXPLICIT_PRODUCT_LINE.matcher(q);
            if (plMatcher.find()) {
                String matched = plMatcher.group(1);
                if (!matched.startsWith("这个") && !matched.startsWith("那个")) {
                    explicit.setPeerDimension(
                            new DimensionState.PeerDimension(
                                    DimensionState.PeerDimensionType.PRODUCT_LINE,
                                    List.of(plMatcher.group())));
                }
            }
        }

        // 需求项（优先级最低）
        if (explicit.getPeerDimension() == null) {
            Matcher reqMatcher = EXPLICIT_REQUIREMENT.matcher(q);
            if (reqMatcher.find()) {
                String matched = reqMatcher.group(1);
                // Skip demonstrative references ("这个需求项", "那个需求项") — not explicit names
                if (!matched.startsWith("这个") && !matched.startsWith("那个")) {
                    explicit.setPeerDimension(
                            new DimensionState.PeerDimension(
                                    DimensionState.PeerDimensionType.REQUIREMENT,
                                    List.of(reqMatcher.group())));
                }
            }
        }

        return explicit;
    }

    // ==================== 步骤②：维度继承 ====================

    /**
     * 根据问题分析结果和上一轮维度状态，决定维度保留与丢弃。
     */
    DimensionState inheritDimensions(DimensionState previous, QuestionAnalysis analysis) {
        if (previous == null) {
            previous = new DimensionState();
        }

        DimensionState current = new DimensionState();

        QuestionAnalysis.QuestionLevel level = analysis.getLevel();
        if (level == null) {
            // Non-dimension questions (e.g. greetings) — keep previous state unchanged
            this.currentState = previous;
            return previous;
        }

        switch (level) {
            case TIME:
                // timeDimension 由 LLM 回答后提取
                break;

            case DEPARTMENT:
                current.setTimeDimension(previous.getTimeDimension());
                // departments 由 LLM 回答后提取
                break;

            case PEER:
                current.setTimeDimension(previous.getTimeDimension());
                current.setDepartments(previous.getDepartments());
                // peerDimension 由 LLM 回答后提取
                break;

            case PERSON:
                current.setTimeDimension(previous.getTimeDimension());
                current.setDepartments(previous.getDepartments());
                current.setPeerDimension(previous.getPeerDimension());
                // persons 由 LLM 回答后提取
                break;

            case CAUSE:
                if (analysis.isHasReference()) {
                    current = handleReferenceInCause(previous, analysis);
                } else {
                    current = previous.deepCopy();
                }
                break;

            case TIME_RANGE:
                current.setTimeDimension(previous.getTimeDimension());
                current.setDepartments(previous.getDepartments());
                current.setPeerDimension(previous.getPeerDimension());
                break;

            default:
                break;
        }

        applyExplicitDimensions(current, analysis.getExplicitDimensions());

        return current;
    }

    /**
     * 处理原因分析中的指代：根据指代层级丢弃下级维度。
     */
    private DimensionState handleReferenceInCause(
            DimensionState previous, QuestionAnalysis analysis) {
        DimensionState current = previous.deepCopy();

        if (analysis.getReferenceType() == null) {
            return current;
        }

        switch (analysis.getReferenceType()) {
            case TIME:
                // "这个季度差的原因" → 时间维度是顶层，指代不影响下级维度，全部保留
                break;

            case DEPARTMENT:
                // "这个部门差的原因" → 丢弃 peerDimension 和 persons
                current.setPeerDimension(null);
                current.setPersons(null);
                break;

            case TEAM:
            case APPLICATION:
            case PRODUCT_LINE:
                // "这个组差的原因" → 保留到 peerDimension，丢弃 persons
                current.setPersons(null);
                break;

            default:
                break;
        }

        return current;
    }

    /**
     * 用户显式指定的维度覆盖继承值，并重置被覆盖维度的下级维度。
     */
    private void applyExplicitDimensions(
            DimensionState current, QuestionAnalysis.ExplicitDimensions explicit) {
        if (explicit == null) {
            return;
        }

        // 时间维度：显式指定后，时间维度以下的全部重置
        // 注意：不 return，后续的显式维度（如部门）可继续覆盖
        if (explicit.getTimeDimension() != null && !explicit.getTimeDimension().isEmpty()) {
            current.setTimeDimension(explicit.getTimeDimension());
            current.setDepartments(null);
            current.setPeerDimension(null);
            current.setPersons(null);
        }

        // 部门：显式指定后，部门以下的维度重置
        if (explicit.getDepartments() != null && !explicit.getDepartments().isEmpty()) {
            current.setDepartments(explicit.getDepartments());
            current.setPeerDimension(null);
            current.setPersons(null);
        }

        // 业务同级维度：显式指定后，人维度重置
        if (explicit.getPeerDimension() != null && !explicit.getPeerDimension().isEmpty()) {
            current.setPeerDimension(explicit.getPeerDimension());
            current.setPersons(null);
        }

        // 人：直接覆盖
        if (explicit.getPersons() != null && !explicit.getPersons().isEmpty()) {
            current.setPersons(explicit.getPersons());
        }
    }

    // ==================== 步骤③：指代消解 ====================

    /**
     * 指代消解：将"这个X"/"那个X"替换为结构化状态中的具体值。
     * 如果状态中对应维度为 null 或类型不匹配，保持原文不变。
     */
    String resolveReference(DimensionState state, QuestionAnalysis analysis, String userQuestion) {
        if (!analysis.isHasReference() || analysis.getReferenceType() == null) {
            return userQuestion;
        }

        String resolved = userQuestion;

        switch (analysis.getReferenceType()) {
            case TIME:
                if (state.getTimeDimension() != null && !state.getTimeDimension().isEmpty()) {
                    String joined = String.join("、", state.getTimeDimension().getValues());
                    if (state.getTimeDimension().getType()
                            == DimensionState.TimeDimensionType.QUARTER) {
                        resolved = resolved.replaceAll("这个季度|那个季度", joined);
                    } else {
                        resolved = resolved.replaceAll("这个版本|那个版本|这个月|那个月|这个月版本|那个月版本", joined);
                    }
                }
                break;

            case DEPARTMENT:
                if (state.getDepartments() != null && !state.getDepartments().isEmpty()) {
                    resolved =
                            resolved.replaceAll(
                                    "这个部门|那个部门", String.join("、", state.getDepartments()));
                }
                break;

            case TEAM:
                if (state.getPeerDimension() != null
                        && state.getPeerDimension().getType()
                                == DimensionState.PeerDimensionType.TEAM
                        && !state.getPeerDimension().isEmpty()) {
                    resolved =
                            resolved.replaceAll(
                                    "这个组|那个组",
                                    String.join("、", state.getPeerDimension().getValues()));
                }
                break;

            case APPLICATION:
                if (state.getPeerDimension() != null
                        && state.getPeerDimension().getType()
                                == DimensionState.PeerDimensionType.APPLICATION
                        && !state.getPeerDimension().isEmpty()) {
                    resolved =
                            resolved.replaceAll(
                                    "这个应用|那个应用",
                                    String.join("、", state.getPeerDimension().getValues()));
                }
                break;

            case PRODUCT_LINE:
                if (state.getPeerDimension() != null
                        && state.getPeerDimension().getType()
                                == DimensionState.PeerDimensionType.PRODUCT_LINE
                        && !state.getPeerDimension().isEmpty()) {
                    resolved =
                            resolved.replaceAll(
                                    "这个产品线|那个产品线",
                                    String.join("、", state.getPeerDimension().getValues()));
                }
                break;

            case REQUIREMENT:
                if (state.getPeerDimension() != null
                        && state.getPeerDimension().getType()
                                == DimensionState.PeerDimensionType.REQUIREMENT
                        && !state.getPeerDimension().isEmpty()) {
                    resolved =
                            resolved.replaceAll(
                                    "这个需求项|那个需求项",
                                    String.join("、", state.getPeerDimension().getValues()));
                }
                break;

            default:
                break;
        }

        return resolved;
    }

    // ==================== 步骤④：组装完整问题 ====================

    /**
     * 将继承后的维度状态格式化为自然语言前缀，拼接用户问题。
     */
    String assembleQuestion(DimensionState state, String userQuestion) {
        StringBuilder prefix = new StringBuilder();

        if (state.getTimeDimension() != null && !state.getTimeDimension().isEmpty()) {
            String label =
                    state.getTimeDimension().getType() == DimensionState.TimeDimensionType.QUARTER
                            ? "季度"
                            : "版本计划";
            prefix.append(label)
                    .append("：")
                    .append(String.join("、", state.getTimeDimension().getValues()))
                    .append(" ");
        }
        if (state.getDepartments() != null && !state.getDepartments().isEmpty()) {
            prefix.append("部门：").append(String.join("、", state.getDepartments())).append(" ");
        }
        if (state.getPeerDimension() != null && !state.getPeerDimension().isEmpty()) {
            String label =
                    switch (state.getPeerDimension().getType()) {
                        case TEAM -> "组";
                        case APPLICATION -> "应用";
                        case PRODUCT_LINE -> "产品线";
                        case REQUIREMENT -> "需求项";
                    };
            prefix.append(label)
                    .append("：")
                    .append(String.join("、", state.getPeerDimension().getValues()))
                    .append(" ");
        }
        if (state.getPersons() != null && !state.getPersons().isEmpty()) {
            prefix.append("人：").append(String.join("、", state.getPersons())).append(" ");
        }

        String prefixStr = prefix.toString().trim();
        return prefixStr.isEmpty() ? userQuestion : prefixStr + " " + userQuestion;
    }

    // ==================== 步骤⑤：维度提取 ====================

    private DimensionState extractDimensions(String answer) {
        if (llmService == null) {
            throw new DimensionException(
                    "LlmDimensionService is required for updateFromAnswer but was not provided");
        }
        String prompt = DimensionPrompts.EXTRACT_DIMENSIONS_PROMPT.replace("{answer}", answer);
        String response = llmService.call(prompt);
        return parseExtractResponse(response);
    }

    private Mono<DimensionState> extractDimensionsAsync(String answer) {
        if (llmService == null) {
            return Mono.error(
                    new DimensionException(
                            "LlmDimensionService is required for updateFromAnswer but was not"
                                    + " provided"));
        }
        String prompt = DimensionPrompts.EXTRACT_DIMENSIONS_PROMPT.replace("{answer}", answer);
        return llmService.callAsync(prompt).map(this::parseExtractResponse);
    }

    private DimensionState parseExtractResponse(String response) {
        try {
            String json = extractJson(response);
            ExtractResult result = OBJECT_MAPPER.readValue(json, ExtractResult.class);
            return convertToDimensionState(result);
        } catch (JsonProcessingException e) {
            throw new DimensionException("Failed to parse dimension extraction response", e);
        }
    }

    private DimensionState convertToDimensionState(ExtractResult result) {
        DimensionState state = new DimensionState();

        if (result.timeDimensionType != null
                && result.timeDimensionValues != null
                && !result.timeDimensionValues.isEmpty()) {
            state.setTimeDimension(
                    new DimensionState.TimeDimension(
                            result.timeDimensionType, result.timeDimensionValues));
        }

        if (result.departments != null && !result.departments.isEmpty()) {
            state.setDepartments(result.departments);
        }

        if (result.peerDimensionType != null
                && result.peerDimensionValues != null
                && !result.peerDimensionValues.isEmpty()) {
            state.setPeerDimension(
                    new DimensionState.PeerDimension(
                            result.peerDimensionType, result.peerDimensionValues));
        }

        if (result.persons != null && !result.persons.isEmpty()) {
            state.setPersons(result.persons);
        }

        return state;
    }

    // ==================== 步骤⑥：合并更新 ====================

    /**
     * 合并提取结果到当前状态：提取到新值的维度替换，未提取到的维度保留。
     */
    DimensionState mergeExtracted(DimensionState current, DimensionState extracted) {
        DimensionState merged = current != null ? current.deepCopy() : new DimensionState();

        // 时间维度：如果提取到新值，替换（二选一，新类型自动覆盖旧类型）
        if (extracted.getTimeDimension() != null && !extracted.getTimeDimension().isEmpty()) {
            merged.setTimeDimension(extracted.getTimeDimension());
        }

        // 部门：如果提取到新值，替换；否则保留
        if (extracted.getDepartments() != null && !extracted.getDepartments().isEmpty()) {
            merged.setDepartments(extracted.getDepartments());
        }

        // 业务同级维度：如果提取到新值，替换；否则保留
        if (extracted.getPeerDimension() != null && !extracted.getPeerDimension().isEmpty()) {
            merged.setPeerDimension(extracted.getPeerDimension());
        }

        // 人：如果提取到新值，替换；否则保留
        if (extracted.getPersons() != null && !extracted.getPersons().isEmpty()) {
            merged.setPersons(extracted.getPersons());
        }

        return merged;
    }

    // ==================== 工具方法 ====================

    /**
     * 从 LLM 响应中提取 JSON 内容（兼容 markdown 代码块包裹的情况）。
     */
    private String extractJson(String response) {
        String trimmed = response.trim();

        // 尝试提取 markdown 代码块中的 JSON
        if (trimmed.contains("```json")) {
            int start = trimmed.indexOf("```json") + 7;
            int end = trimmed.indexOf("```", start);
            if (end > start) {
                return trimmed.substring(start, end).trim();
            }
        }
        if (trimmed.contains("```")) {
            int start = trimmed.indexOf("```") + 3;
            int end = trimmed.indexOf("```", start);
            if (end > start) {
                return trimmed.substring(start, end).trim();
            }
        }

        // 尝试提取花括号包裹的 JSON
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }

        return trimmed;
    }

    /**
     * LLM 提取结果的内部反序列化结构。
     */
    private static class ExtractResult {
        public DimensionState.TimeDimensionType timeDimensionType;
        public List<String> timeDimensionValues;
        public List<String> departments;
        public DimensionState.PeerDimensionType peerDimensionType;
        public List<String> peerDimensionValues;
        public List<String> persons;
    }
}
