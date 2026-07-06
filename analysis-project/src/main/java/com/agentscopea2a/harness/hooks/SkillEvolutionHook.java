/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.agentscopea2a.harness.hooks;

import com.agentscopea2a.harness.cache.ResponseCacheService;
import com.agentscopea2a.harness.skills.FingerprintCalculator;
import com.agentscopea2a.harness.skills.SkillEvolutionRunner;
import com.agentscopea2a.harness.skills.SkillVectorIndex;
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
import java.util.Optional;
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
 *
 * <h3>Fallback skill resolution (PR4.1)</h3>
 * <p>When PR3 (SkillRetrievalHook) doesn't inject {@code skills.retrieved} — because it's
 * disabled, L1 fingerprint missed, or L2 cosine was below threshold — this hook now falls back
 * to resolving skills by recomputing the fingerprint from the user question and looking up the
 * matching skill in {@code skill_index}. This ensures the failure-feedback closed loop works
 * even when PR3 doesn't fire.
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
    private final FingerprintCalculator fingerprintCalculator;
    private final SkillVectorIndex vectorIndex;
    private final List<String> rejectionKeywords;

    /**
     * Request-scoped cache of skills.retrieved captured in PreCall. The framework may clear
     * RuntimeContext attributes between PreCall and PostCall, so we stash a copy here as a
     * fallback. Reset on every PreCall to avoid leakage across turns.
     */
    private List<String> currentTurnRetrieved = List.of();

    public SkillEvolutionHook(
            SkillEvolutionRunner runner,
            RuntimeContext runtimeContext,
            String rejectionKeywordsCsv,
            FingerprintCalculator fingerprintCalculator,
            SkillVectorIndex vectorIndex) {
        this.runner = runner;
        this.runtimeContext = runtimeContext;
        this.fingerprintCalculator = fingerprintCalculator;
        this.vectorIndex = vectorIndex;
        this.rejectionKeywords = parseRejectionKeywords(rejectionKeywordsCsv);
        log.info("SkillEvolutionHook initialized with {} rejection keywords: {}", rejectionKeywords.size(), rejectionKeywords);
    }

    /**
     * Backwards-compatible constructor without fingerprint fallback.
     * When used, the hook will only attribute outcomes when PR3 populates {@code skills.retrieved}.
     */
    public SkillEvolutionHook(
            SkillEvolutionRunner runner,
            RuntimeContext runtimeContext,
            String rejectionKeywordsCsv) {
        this(runner, runtimeContext, rejectionKeywordsCsv, null, null);
    }

    /**
     * Parse the rejection keywords CSV, repairing any double-encoded UTF-8 strings
     * that result from Java {@link java.util.Properties} loading a UTF-8 properties file
     * via ISO-8859-1. Spring Boot normally reads UTF-8 correctly, but some environments
     * (e.g. Windows with cp1252 default charset) can cause mojibake.
     */
    private static List<String> parseRejectionKeywords(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of("不对", "错了", "重算", "重新", "不是这样", "不正确");
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(SkillEvolutionHook::repairUtf8)
                .toList();
    }

    /**
     * Detect and repair double-encoded UTF-8: if the string looks like it was decoded
     * as ISO-8859-1 when it should have been UTF-8, re-encode as ISO-8859-1 bytes and
     * decode as UTF-8. This is a no-op for strings that are already correct.
     */
    private static String repairUtf8(String s) {
        try {
            byte[] bytes = s.getBytes("ISO-8859-1");
            // Heuristic: if re-decoded as UTF-8 produces valid chars and is different,
            // it was double-encoded.
            String repaired = new String(bytes, "UTF-8");
            // Check if repair actually changed something and the result is valid
            if (!repaired.equals(s) && isValidUtf8Text(repaired)) {
                return repaired;
            }
        } catch (Exception e) {
            // Ignore — return original
        }
        return s;
    }

    /**
     * Quick heuristic: a "valid" repaired string should contain CJK characters
     * (which were the ones that got mangled) and no replacement characters.
     */
    private static boolean isValidUtf8Text(String s) {
        if (s.contains("�")) return false;  // replacement char
        // If it contains at least one CJK character, it's likely a valid repair
        return s.codePoints().anyMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN);
    }

    @Override
    public int priority() {
        return 60;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (!runner.enabled()) return Mono.just(event);
        try {
            if (event instanceof PreCallEvent e) {
                log.debug("SkillEvolutionHook PreCall fired for sessionKey={}", sessionKey());
                handlePreCall(e);
            } else if (event instanceof PostCallEvent e) {
                List<String> retrieved = readRetrievedSkills();
                log.debug(
                        "SkillEvolutionHook PostCall fired: sessionKey={} retrieved={}",
                        sessionKey(),
                        retrieved);
                handlePostCall(e);
            }
        } catch (Exception ex) {
            // PR4 must never break a request. Any error here just means a missed accounting tick.
            log.debug("SkillEvolutionHook swallowed error: {}", ex.getMessage());
        }
        return Mono.just(event);
    }

    // -------- PreCall: cross-turn rejection lookback + cache retrieved skills --------

    private void handlePreCall(PreCallEvent event) {
        String sessionKey = sessionKey();
        if (sessionKey == null) return;

        // Capture retrieved skills from RuntimeContext before it may be cleared
        List<String> retrieved = readRetrievedSkills();
        if (retrieved == null || retrieved.isEmpty()) {
            // PR3 didn't inject skills — try fingerprint-based resolution so
            // the PostCall fallback snapshot and cross-turn rejection both work.
            retrieved = resolveSkillsByFingerprint(
                    event.getInputMessages() != null ? event.getInputMessages() : List.of());
        }
        currentTurnRetrieved = List.copyOf(retrieved);

        SkillEvolutionRunner.PendingJudgement pending = runner.consumePendingJudgement(sessionKey);
        if (pending == null) {
            log.debug("PreCall: no pending judgement for session {}", sessionKey);
            return;
        }
        log.info("PreCall: consumed pending judgement for session {} skills={}", sessionKey, pending.skills());

        String userInput = ResponseCacheService.extractUserQuestion(event.getInputMessages());
        if (userInput == null || userInput.isBlank()) {
            log.debug("PreCall: cannot extract user input");
            return;
        }
        log.info("PreCall: user input='{}' matchesRejection={}", userInput, matchesRejection(userInput));

        if (matchesRejection(userInput)) {
            log.info(
                    "Cross-turn rejection detected for session {}: skills={}",
                    sessionKey,
                    pending.skills());
            runner.recordFailure(
                    pending.skills(),
                    pending.exemplarQuestion(),
                    "用户在下一轮否认了答案: " + userInput);
        }
        // else: No rejection signal — do NOT record success. The previous turn's PostCall
        // already cached a pending judgement. If the user just changed topics or asked a
        // follow-up on a different dimension, we can't know whether they were satisfied.
        // Recording a default success would inflate success_count and make failure_rate
        // never reach the threshold (see review.md §1.4). The pending judgement is now
        // consumed and discarded — the skill gets neither success nor failure credit.
    }

    private boolean matchesRejection(String userInput) {
        String lower = userInput.toLowerCase();
        for (String kw : rejectionKeywords) {
            if (lower.contains(kw.toLowerCase())) {
                log.info("matchesRejection MATCH: input={} keyword={}", userInput, kw);
                return true;
            }
        }
        log.info("matchesRejection NO MATCH: input={} keywords={}", userInput, rejectionKeywords);
        return false;
    }

    // -------- PostCall: this-turn signal --------

    private void handlePostCall(PostCallEvent event) {
        List<String> retrieved = readRetrievedSkills();
        if (retrieved == null || retrieved.isEmpty()) {
            // RuntimeContext was likely cleared between PreCall and PostCall — fall back to
            // the snapshot captured at PreCall.
            retrieved = currentTurnRetrieved;
        }
        if (retrieved == null || retrieved.isEmpty()) {
            // PR3 didn't retrieve skills (disabled, L1 miss + L2 below threshold, etc.).
            // Try to resolve the skill by fingerprint from the user question so that
            // failure attribution still works.
            Memory mem = event.getMemory();
            retrieved = resolveSkillsByFingerprint(
                    mem != null ? mem.getMessages() : List.of());
        }
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

    /**
     * Fallback skill resolution when PR3 didn't inject {@code skills.retrieved}.
     *
     * <p>Computes the metric-level fingerprint from the user question (same logic as PR3's
     * {@code SkillRetrievalHook.fingerprintOf()}) and looks up the matching skill
     * in {@code skill_index} via L1 (fingerprint) query.
     *
     * <p>Returns empty list (not null) when no skill can be found — the caller
     * treats this identically to "no skills to attribute". All errors are swallowed:
     * this is best-effort.
     */
    private List<String> resolveSkillsByFingerprint(List<Msg> messages) {
        if (fingerprintCalculator == null || vectorIndex == null) return List.of();
        try {
            String question = ResponseCacheService.extractUserQuestion(messages);
            if (question == null || question.isBlank()) return List.of();

            // Skill fingerprint uses _global scope — all users share the same skill.
            String intent = ResponseCacheService.classifyIntent(question);
            if (intent == null || intent.isEmpty()) return List.of();
            String fingerprint = fingerprintCalculator.computeMetric(intent, question);

            Optional<String> skillName = vectorIndex.findByFingerprint(fingerprint);
            if (skillName.isPresent()) {
                log.info("Fingerprint fallback resolved skill '{}' for fp={}", skillName.get(), fingerprint);
                return List.of(skillName.get());
            }
            log.debug("Fingerprint fallback: no active skill found for fp={}", fingerprint);
            return List.of();
        } catch (Exception ex) {
            log.debug("resolveSkillsByFingerprint failed: {}", ex.getMessage());
            return List.of();
        }
    }

    private String sessionKey() {
        // Prefer user_id over session_id because the framework generates a new UUID for each
        // request even when the client passes the same session_id. User_id is stable across
        // turns in the same conversation thread.
        if (runtimeContext == null) return null;
        String uid = runtimeContext.getUserId();
        if (uid != null && !uid.isBlank()) return "u:" + uid;
        String sid = runtimeContext.getSessionId();
        if (sid != null && !sid.isBlank()) return "s:" + sid;
        return null;
    }
}
