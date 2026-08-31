package com.agentscopea2a.v2.registry.service;

import com.agentscopea2a.entity.ScriptRegistryEntry;
import com.agentscopea2a.mapper.gauss.ScriptRegistryMapper;
import com.agentscopea2a.v2.tools.ScriptExecTool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Asynchronous, bounded debug runs for registered scripts. */
@Service
public class ScriptDebugService implements AutoCloseable {
    private final ScriptRegistryMapper mapper;
    private final ScriptParamValidator validator;
    private final ScriptInvoker invoker;
    private final ExecutorService executor;
    private final ScheduledExecutorService timeoutScheduler = Executors.newScheduledThreadPool(1);
    private final Map<String, RunState> runs = new ConcurrentHashMap<>();
    private final int maxConcurrent;

    @Autowired
    public ScriptDebugService(ScriptRegistryMapper mapper, ScriptParamValidator validator,
                              ScriptExecTool scriptExecTool,
                              @Value("${harness.a2a.script-debug.max-concurrent:2}") int maxConcurrent) {
        this(mapper, validator, scriptExecTool::executeForDebug, maxConcurrent);
    }

    ScriptDebugService(ScriptRegistryMapper mapper, ScriptParamValidator validator,
                       ScriptInvoker invoker) {
        this(mapper, validator, invoker, 2);
    }

    ScriptDebugService(ScriptRegistryMapper mapper, ScriptParamValidator validator,
                       ScriptInvoker invoker, int maxConcurrent) {
        this.mapper = mapper;
        this.validator = validator;
        this.invoker = invoker;
        this.maxConcurrent = maxConcurrent;
        this.executor = Executors.newFixedThreadPool(maxConcurrent);
    }

    public DebugRun start(Long id, Map<String, Object> params, int requestedTimeoutSeconds) {
        ScriptRegistryEntry entry = mapper.selectById(id);
        if (entry == null) throw new IllegalArgumentException("SCRIPT_NOT_FOUND: 记录不存在");
        if (!Integer.valueOf(1).equals(entry.getEnabled())) throw new IllegalArgumentException("SCRIPT_DISABLED: 脚本未启用");
        validator.validate(entry.getParamsSchema(), params);
        int timeout = Math.max(1, Math.min(300, Math.min(requestedTimeoutSeconds <= 0 ? 60 : requestedTimeoutSeconds,
                entry.getTimeoutSeconds() == null ? 60 : entry.getTimeoutSeconds())));
        long active = runs.values().stream().filter(r -> !r.isTerminal()).count();
        if (active >= maxConcurrent) throw new IllegalStateException("DEBUG_CONCURRENCY_LIMIT: 当前调试任务已达上限");

        String runId = UUID.randomUUID().toString();
        RunState state = new RunState(runId, entry.getScriptId());
        runs.put(runId, state);
        state.startedAt = System.currentTimeMillis();
        state.events.add(new DebugEvent("run_started", runId, "RUNNING", "", "", null, null, 0));
        Future<?> future = executor.submit(() -> execute(state, params, timeout));
        state.future = future;
        state.timeoutFuture = timeoutScheduler.schedule(() -> timeout(state), timeout, TimeUnit.SECONDS);
        return state.snapshot();
    }

    private void timeout(RunState state) {
        if (state.isTerminal()) return;
        state.status = "TIMEOUT";
        state.exitCode = -1;
        state.elapsedMs = elapsed(state.startedAt);
        if (state.future != null) state.future.cancel(true);
        state.events.add(new DebugEvent("run_finished", state.runId, state.status, state.stdout,
                "调试任务超时", -1, state.status, state.elapsedMs));
    }

    private void execute(RunState state, Map<String, Object> params, int timeout) {
        state.status = "RUNNING";
        long started = state.startedAt;
        try {
            String output = invoker.invoke(state.scriptId, params);
            if (state.isTerminal()) return;
            String stdout = output == null ? "" : output;
            state.stdout = stdout;
            java.util.regex.Matcher exit = java.util.regex.Pattern.compile("\\bexit=(-?\\d+)").matcher(stdout);
            if (exit.find() && Integer.parseInt(exit.group(1)) != 0) {
                state.status = stdout.contains("超时") ? "TIMEOUT" : "FAILED";
                state.exitCode = Integer.parseInt(exit.group(1));
                state.stderr = stdout;
            } else if (stdout.contains("拒绝执行") || stdout.contains("启动失败")) {
                state.status = "FAILED";
                state.exitCode = 1;
                state.stderr = stdout;
            } else {
                state.status = "SUCCESS";
                state.exitCode = 0;
            }
            state.events.add(new DebugEvent("stdout", state.runId, state.status, stdout, "", 0, null,
                    elapsed(started)));
        } catch (Exception e) {
            if (state.isTerminal()) return;
            state.status = "FAILED";
            state.exitCode = 1;
            state.stderr = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            state.events.add(new DebugEvent("stderr", state.runId, state.status, "", state.stderr, 1, null,
                    elapsed(started)));
        }
        state.elapsedMs = elapsed(started);
        if (state.timeoutFuture != null) state.timeoutFuture.cancel(false);
        state.events.add(new DebugEvent("run_finished", state.runId, state.status, state.stdout, state.stderr,
                state.exitCode, state.status, state.elapsedMs));
    }

    public DebugRun get(String runId) {
        RunState state = runs.get(runId);
        if (state == null) throw new IllegalArgumentException("DEBUG_RUN_NOT_FOUND: 任务不存在");
        return state.snapshot();
    }

    public List<DebugEvent> events(String runId) {
        RunState state = runs.get(runId);
        if (state == null) throw new IllegalArgumentException("DEBUG_RUN_NOT_FOUND: 任务不存在");
        return List.copyOf(state.events);
    }

    public boolean cancel(String runId) {
        RunState state = runs.get(runId);
        if (state == null) throw new IllegalArgumentException("DEBUG_RUN_NOT_FOUND: 任务不存在");
        if (state.isTerminal()) return false;
        state.status = "CANCELLED";
        if (state.future != null) state.future.cancel(true);
        state.elapsedMs = elapsed(state.startedAt);
        state.events.add(new DebugEvent("run_cancelled", state.runId, state.status, state.stdout, state.stderr,
                null, state.status, state.elapsedMs));
        return true;
    }

    private static long elapsed(long start) { return System.currentTimeMillis() - start; }

    @Override public void close() { executor.shutdownNow(); timeoutScheduler.shutdownNow(); }

    @FunctionalInterface
    interface ScriptInvoker { String invoke(String scriptId, Map<String, Object> params) throws Exception; }

    private static final class RunState {
        final String runId;
        final String scriptId;
        final List<DebugEvent> events = Collections.synchronizedList(new ArrayList<>());
        volatile String status = "QUEUED";
        volatile String stdout = "";
        volatile String stderr = "";
        volatile Integer exitCode;
        volatile Long elapsedMs;
        volatile long startedAt;
        volatile Future<?> future;
        volatile Future<?> timeoutFuture;
        RunState(String runId, String scriptId) { this.runId = runId; this.scriptId = scriptId; }
        boolean isTerminal() { return "SUCCESS".equals(status) || "FAILED".equals(status)
                || "TIMEOUT".equals(status) || "CANCELLED".equals(status); }
        DebugRun snapshot() { return new DebugRun(runId, scriptId, status, stdout, stderr, exitCode, elapsedMs, Instant.now().toString()); }
    }

    public record DebugRun(String runId, String scriptId, String status, String stdout, String stderr,
                           Integer exitCode, Long elapsedMs, String updatedAt) { }
    public record DebugEvent(String type, String runId, String status, String stdout, String stderr,
                             Integer exitCode, String terminalStatus, long elapsedMs) { }
}
