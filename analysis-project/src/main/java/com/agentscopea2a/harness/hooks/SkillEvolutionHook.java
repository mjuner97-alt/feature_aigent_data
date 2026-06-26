/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.agentscopea2a.harness.hooks;

import com.agentscopea2a.harness.cache.ResponseCacheService;
import com.agentscopea2a.harness.skills.SkillEvolutionRunner;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostCallEvent;
import io.agentscope.core.hook.PreCallEvent;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * PR4 — failure-feedback closed loop. Pairs with {@link SkillRetrievalHook} (PR3) which writes
 * {@code skills.retrieved} into {@link RuntimeContext} for every successful retrieval, and with
 * {@link SkillEvolutionRunner} which owns the SQL writes + async evolve dispatch.
 *
 * <p>Two responsibilities:
 *
 * <ol>
 *   <li><b>PreCall (cross-turn rejection)</b> — pop any pending judgement cached at the end of
 *       the previous turn for this session; if the current user message matches a rejection
 *       pattern ("不对", "错了" ...) credit those skills as failures, otherwise as successes.
 *   <li><b>PostCall (this-turn signal)</b> — read {@code skills.retrieved} from the runtime
 *       context, scan the agent's memory for python_exec failures (≥2 retries) and any tool
 *       result that crashed; if any failed signal fires, credit failures *and* cache a pending
 *       judgement for the next turn (so the user can still override via rejection feedback).
 *       Otherwise just cache the pending judgement and let the cross-turn path decide.
 * </ol>
 *
 * <p>Priority +60 so we run after every framework-internal PostCall hook but before any cleanup.
 *
 * <p>The hook never blocks or throws — SQL / runtime errors are logged at debug and swallowed,
 * because PR4 is best-effort and must not affect user-facing latency.
 */
public class SkillEvolutionHook implements Hook {

    private static final Logger log = LoggerFactory.getLogger(SkillEvolutionHook.class);

    /** RuntimeContext key written by {@link SkillRetrievalHook} on every successful retrieval. */
    public static final String ATTR_SKILLS_RETRIEVED = "skills.retrieved";

    /** Threshold for treating a turn as failed via python_exec retry signals. */
    private static final int PYTHON_EXEC_FAILURE_THRESHOLD = 2;

    /** Token in PythonExecTool's banner that marks a non-zero exit. */
    private static final Pattern PYTHON_EXEC_FAILURE_TOKEN =
            Pattern.compile("\\[python_exec\\]\\s+exit=(?!0\\b)-?\\d+");

    private final SkillEvolutionRunner runner;
    private final RuntimeContext runtimeContext;
    private final List<String> rejectionKeywords;

    public SkillEvolutionHook(
            SkillEvolutionRunner runner,
            RuntimeContext runtimeContext,
            String rejectionKeywordsCsv) {
        this.runner = runner;
        this.runtimeContext = runtimeContext;
        this.rejectionKeywords =
                rejectionKeywordsCsv == null || rejectionKeywordsCsv.isBlank()
                        ? List.of("不对", "错了", "重算", "重新", "不是这样", "不正确")
                        : Arrays.stream(rejectionKeywordsCsv.split(","))
                                .map(String::trim)
                                .filter(s -> !s.isEmpty())
                                .toList();
        log.info(
                "SkillEvolutionHook constructed rejectionKeywordsCsv=[{}] rejectionKeywords={} (sizes csv={} list={})",
                rejectionKeywordsCsv,
                rejectionKeywords,
                rejectionKeywordsCsv == null ? -1 : rejectionKeywordsCsv.length(),
                rejectionKeywords.size());
        for (int i = 0; i < rejectionKeywords.size(); i++) {
            String kw = rejectionKeywords.get(i);
            StringBuilder hex = new StringBuilder();
            for (char c : kw.toCharArray()) {
                hex.append(String.format("\\u%04x", (int) c));
            }
            log.info("  rejectionKeywords[{}]='{}' hex={}", i, kw, hex);
        }
    }

    @Override
    public int priority() {
        return 60;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (!runner.enabled()) return Mono.just(event);
        log.info("SkillEvolutionHook.onEvent fired class={} runnerEnabled={}", event.getClass().getSimpleName(), runner.enabled());
        try {
            if (event instanceof PreCallEvent e) {
                handlePreCall(e);
            } else if (event instanceof PostCallEvent e) {
                handlePostCall(e);
            }
        } catch (Exception ex) {
            // PR4 must never break a request. Any error here just means a missed accounting tick.
            log.debug("SkillEvolutionHook swallowed error: {}", ex.getMessage());
        }
        return Mono.just(event);
    }

    // -------- PreCall: cross-turn rejection lookback --------

    private void handlePreCall(PreCallEvent event) {
        String sessionKey = sessionKey();
        log.info("PR4 handlePreCall sessionKey={}", sessionKey);
        if (sessionKey == null) return;
        SkillEvolutionRunner.PendingJudgement pending = runner.consumePendingJudgement(sessionKey);
        log.info("PR4 handlePreCall pending={}", pending);
        if (pending == null) return;

        String userInput = ResponseCacheService.extractUserQuestion(event.getInputMessages());
        log.info("PR4 handlePreCall userInput={} matchesRejection={}", userInput, userInput != null && matchesRejection(userInput));
        if (userInput == null || userInput.isBlank()) return;

        if (matchesRejection(userInput)) {
            // The user just told us the previous turn was wrong — credit every retrieved skill
            // with a failure and (since we don't have the previous turn's trace anymore) feed
            // the rejection message itself into the evolve prompt as the failed-trace context.
            log.info(
                    "Cross-turn rejection detected for session {}: skills={} userInput={}",
                    sessionKey,
                    pending.skills(),
                    userInput);
            runner.recordFailure(
                    pending.skills(),
                    pending.exemplarQuestion(),
                    "用户在下一轮否认了答案: " + userInput);
        } else {
            // No rejection signal in this turn's user input — credit the previous turn's skills
            // as successes. Note: this fires even when the previous turn already counted as a
            // PostCall-detected failure (retry≥2). That's intentional double-bookkeeping: the
            // PostCall failure already triggered the threshold check, and a "user did not
            // complain" success can balance it out for a borderline skill.
            runner.recordSuccess(pending.skills());
        }
    }

    private boolean matchesRejection(String userInput) {
        String lower = userInput.toLowerCase();
        StringBuilder inHex = new StringBuilder();
        for (char c : userInput.toCharArray()) {
            inHex.append(String.format("\\u%04x", (int) c));
        }
        log.info("matchesRejection input='{}' hex={} kwCount={}", userInput, inHex, rejectionKeywords.size());
        for (String kw : rejectionKeywords) {
            boolean hit = lower.contains(kw.toLowerCase());
            log.info("  test kw='{}' hit={}", kw, hit);
            if (hit) return true;
        }
        return false;
    }

    // -------- PostCall: this-turn signal --------

    private void handlePostCall(PostCallEvent event) {
        List<String> retrieved = readRetrievedSkills();
        log.info("PR4 handlePostCall retrieved.size={} retrieved={} runtimeContextSession={} runtimeContextUser={}", retrieved == null ? -1 : retrieved.size(), retrieved, runtimeContext == null ? "null-ctx" : runtimeContext.getSessionId(), runtimeContext == null ? "null-ctx" : runtimeContext.getUserId());
        if (retrieved == null || retrieved.isEmpty()) return;

        Memory memory = event.getMemory();
        int pythonFailures = countPythonExecFailures(memory);
        String lastFailedTrace = pythonFailures > 0 ? extractLastFailedTrace(memory) : null;

        boolean failedThisTurn = pythonFailures >= PYTHON_EXEC_FAILURE_THRESHOLD;

        if (failedThisTurn) {
            String exemplar = extractLastUserMessage(memory);
            log.info(
                    "PostCall failure signal: skills={} pythonFailures={}",
                    retrieved,
                    pythonFailures);
            runner.recordFailure(retrieved, exemplar, lastFailedTrace);
            // No need to cache a pending judgement — the failure is already booked. If the
            // user *also* complains next turn we'd double-count, so we deliberately skip the
            // cache here.
            return;
        }

        // No this-turn failure signal — defer the success/failure decision to the next turn's
        // user message via the pending-judgement cache. This is what lets users still vote
        // "wrong" on an answer that didn't trip retry≥2.
        String sessionKey = sessionKey();
        if (sessionKey == null) return;
        runner.cachePendingJudgement(sessionKey, retrieved, extractLastUserMessage(memory));
    }

    private List<String> readRetrievedSkills() {
        if (runtimeContext == null) return List.of();
        try {
            Object raw = runtimeContext.get(ATTR_SKILLS_RETRIEVED);
            if (raw instanceof List<?> list) {
                java.util.List<String> out = new java.util.ArrayList<>(list.size());
                for (Object o : list) {
                    if (o instanceof String s && !s.isBlank()) out.add(s);
                }
                return out;
            }
        } catch (Exception ex) {
            log.debug("readRetrievedSkills failed: {}", ex.getMessage());
        }
        return List.of();
    }

    private static int countPythonExecFailures(Memory memory) {
        if (memory == null) return 0;
        int count = 0;
        for (Msg m : memory.getMessages()) {
            if (m.getContent() == null) continue;
            for (ContentBlock b : m.getContent()) {
                if (!(b instanceof ToolResultBlock tr)) continue;
                if (!"python_exec".equalsIgnoreCase(tr.getName())) continue;
                String text = textOf(tr);
                if (text.isEmpty()) continue;
                if (PYTHON_EXEC_FAILURE_TOKEN.matcher(text).find()) {
                    count++;
                }
            }
        }
        return count;
    }

    private static String extractLastFailedTrace(Memory memory) {
        if (memory == null) return null;
        String last = null;
        for (Msg m : memory.getMessages()) {
            if (m.getContent() == null) continue;
            for (ContentBlock b : m.getContent()) {
                if (!(b instanceof ToolResultBlock tr)) continue;
                if (!"python_exec".equalsIgnoreCase(tr.getName())) continue;
                String text = textOf(tr);
                if (PYTHON_EXEC_FAILURE_TOKEN.matcher(text).find()) {
                    last = text;
                }
            }
        }
        return last;
    }

    private static String extractLastUserMessage(Memory memory) {
        if (memory == null) return null;
        String last = null;
        for (Msg m : memory.getMessages()) {
            if (m.getRole() == io.agentscope.core.message.MsgRole.USER) {
                String text = collectText(m);
                if (!text.isBlank()) last = text;
            }
        }
        return last;
    }

    private static String textOf(ToolResultBlock tr) {
        if (tr.getOutput() == null) return "";
        StringBuilder sb = new StringBuilder();
        for (ContentBlock b : tr.getOutput()) {
            if (b instanceof TextBlock tb && tb.getText() != null) {
                sb.append(tb.getText()).append('\n');
            }
        }
        return sb.toString();
    }

    private static String collectText(Msg m) {
        if (m == null || m.getContent() == null) return "";
        StringBuilder sb = new StringBuilder();
        for (ContentBlock b : m.getContent()) {
            if (b instanceof TextBlock tb && tb.getText() != null) {
                sb.append(tb.getText()).append('\n');
            }
        }
        return sb.toString().trim();
    }

    private String sessionKey() {
        if (runtimeContext == null) return null;
        String sid = runtimeContext.getSessionId();
        if (sid != null && !sid.isBlank()) return "s:" + sid;
        String uid = runtimeContext.getUserId();
        if (uid != null && !uid.isBlank()) return "u:" + uid;
        return null;
    }
}
