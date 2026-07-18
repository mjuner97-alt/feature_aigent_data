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

import com.agentscopea2a.v2.cache.ResponseCacheService;
import com.agentscopea2a.v2.skills.FingerprintCalculator;
import com.agentscopea2a.v2.skills.SkillEvolutionRunner;
import com.agentscopea2a.v2.skills.SkillVectorIndex;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostCallEvent;
import io.agentscope.core.hook.PreCallEvent;
import io.agentscope.core.hook.RuntimeContextAware;
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
 * v2 port of the v1 PR4 SkillEvolutionHook (failure-feedback closed loop).
 *
 * <p>Pairs with {@link SkillRetrievalHook} (PR3) which writes {@code skills.retrieved}
 * into {@link RuntimeContext} for every successful retrieval, and with
 * {@link SkillEvolutionRunner} which owns the SQL writes + async evolve dispatch.
 *
 * <p>Two responsibilities:
 *
 * <ol>
 *   <li><b>PreCall (cross-turn rejection)</b> - pop any pending judgement cached at the end of
 *       the previous turn for this session; if the current user message matches a rejection
 *       pattern ("不对", "错了" ...) credit those skills as failures, otherwise discard.
 *   <li><b>PostCall (this-turn signal)</b> - read {@code skills.retrieved} from the runtime
 *       context (or fall back to fingerprint-based resolution), scan the agent's memory for
 *       python_exec failures (&ge;2 retries); if any failed signal fires, credit failures
 *       <em>and</em> skip the pending-judgement cache (avoid double-counting). Otherwise cache
 *       a pending judgement so the next turn's user message can still vote "wrong".
 * </ol>
 *
 * <p>Uses {@link RuntimeContextAware} for per-call context (replaces v1 constructor injection).
 * The JAR pushes the context via {@link #setRuntimeContext(RuntimeContext)} before each call.
 *
 * <p>The hook never blocks or throws - SQL / runtime errors are logged at debug and swallowed,
 * because PR4 is best-effort and must not affect user-facing latency.
 */
public class SkillEvolutionHook implements Hook, RuntimeContextAware {

    private static final Logger log = LoggerFactory.getLogger(SkillEvolutionHook.class);

    /** RuntimeContext key written by {@link SkillRetrievalHook} on every successful retrieval. */
    public static final String ATTR_SKILLS_RETRIEVED = "skills.retrieved";

    /** Threshold for treating a turn as failed via python_exec retry signals. */
    private static final int PYTHON_EXEC_FAILURE_THRESHOLD = 2;

    /** Token in PythonExecTool's banner that marks a non-zero exit. */
    private static final Pattern PYTHON_EXEC_FAILURE_TOKEN =
            Pattern.compile("\\[python_exec\\]\\s+exit=(?!0\\b)-?\\d+");

    private final SkillEvolutionRunner runner;
    private final FingerprintCalculator fingerprintCalculator;
    private final SkillVectorIndex vectorIndex;
    private final List<String> rejectionKeywords;

    /**
     * Request-scoped cache of skills.retrieved captured in PreCall. The framework may clear
     * RuntimeContext attributes between PreCall and PostCall, so we stash a copy here as a
     * fallback. Reset on every PreCall to avoid leakage across turns.
     */
    private List<String> currentTurnRetrieved = List.of();

    private volatile RuntimeContext currentCtx;

    public SkillEvolutionHook(
            SkillEvolutionRunner runner,
            String rejectionKeywordsCsv,
            FingerprintCalculator fingerprintCalculator,
            SkillVectorIndex vectorIndex) {
        this.runner = runner;
        this.fingerprintCalculator = fingerprintCalculator;
        this.vectorIndex = vectorIndex;
        this.rejectionKeywords = parseRejectionKeywords(rejectionKeywordsCsv);
        log.info(
                "SkillEvolutionHook initialized with {} rejection keywords: {}",
                rejectionKeywords.size(),
                rejectionKeywords);
    }

    @Override
    public void setRuntimeContext(RuntimeContext context) {
        this.currentCtx = context;
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
            // PR3 didn't inject skills - try fingerprint-based resolution so the PostCall
            // fallback snapshot and cross-turn rejection both work.
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
        // else: No rejection signal - do NOT record success. The previous turn's PostCall
        // already cached a pending judgement. If the user just changed topics or asked a
        // follow-up on a different dimension, we can't know whether they were satisfied.
        // Recording a default success would inflate success_count and make failure_rate
        // never reach the threshold. The pending judgement is now consumed and discarded -
        // the skill gets neither success nor failure credit.
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
            // RuntimeContext was likely cleared between PreCall and PostCall - fall back to
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
            // No need to cache a pending judgement - the failure is already booked. If the
            // user *also* complains next turn we'd double-count, so we deliberately skip the
            // cache here.
            return;
        }

        // No this-turn failure signal - defer the success/failure decision to the next turn's
        // user message via the pending-judgement cache. This is what lets users still vote
        // "wrong" on an answer that didn't trip retry>=2.
        String sessionKey = sessionKey();
        if (sessionKey == null) return;
        runner.cachePendingJudgement(sessionKey, retrieved, extractLastUserMessage(memory));
    }

    private List<String> readRetrievedSkills() {
        RuntimeContext ctx = this.currentCtx;
        if (ctx == null) return List.of();
        try {
            Object raw = ctx.get(ATTR_SKILLS_RETRIEVED);
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
     * <p>Computes the metric-level fingerprint from the user question and looks up the matching
     * skill in {@code skill_index} via L1 (fingerprint) query. Returns empty list when no skill
     * can be found - the caller treats this identically to "no skills to attribute".
     * All errors are swallowed: this is best-effort.
     */
    private List<String> resolveSkillsByFingerprint(List<Msg> messages) {
        if (fingerprintCalculator == null || vectorIndex == null) return List.of();
        try {
            String question = ResponseCacheService.extractUserQuestion(messages);
            if (question == null || question.isBlank()) return List.of();

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
        RuntimeContext ctx = this.currentCtx;
        if (ctx == null) return null;
        String uid = ctx.getUserId();
        if (uid != null && !uid.isBlank()) return "u:" + uid;
        String sid = ctx.getSessionId();
        if (sid != null && !sid.isBlank()) return "s:" + sid;
        return null;
    }

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
            String repaired = new String(bytes, "UTF-8");
            if (!repaired.equals(s) && isValidUtf8Text(repaired)) {
                return repaired;
            }
        } catch (Exception e) {
            // Ignore - return original
        }
        return s;
    }

    /**
     * Quick heuristic: a "valid" repaired string should contain CJK characters
     * (which were the ones that got mangled) and no replacement characters.
     */
    private static boolean isValidUtf8Text(String s) {
        if (s.contains("�")) return false;
        return s.codePoints().anyMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN);
    }
}
