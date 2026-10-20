package com.agentscopea2a.v2.hooks;

import com.agentscopea2a.v2.skills.DirectSkillExecutor;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PreCallEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 追问轮上下文补给注入 (docs/202610/skill-direct-exec-followup-plan.md §3.3):
 * 快照存在时注入 &lt;last_direct_exec&gt; 块; 无快照/关闭/无 RuntimeContext 均零注入。
 */
class LastDirectExecContextHookTest {

    private final PreCallEvent event = mock(PreCallEvent.class);

    private static RuntimeContext context(String userId, String sessionId) {
        return RuntimeContext.builder().userId(userId).sessionId(sessionId).build();
    }

    @Test
    void injectsSnapshotBlockWithAllLines() {
        DirectSkillExecutor executor = mock(DirectSkillExecutor.class);
        java.util.Map<String, Object> params = new java.util.LinkedHashMap<>();
        params.put("dept", "杭州开发一部");
        params.put("version", "2026年7月份版本");
        when(executor.lastExec("alice", "conv-1")).thenReturn(new DirectSkillExecutor.LastDirectExec(
                "杭州开发一部 7月 达标率多少", "dept_skill", "script_a", params,
                "部门|达标率|合格率\n杭州开发一部|98.2%", System.currentTimeMillis()));
        LastDirectExecContextHook hook = new LastDirectExecContextHook(executor, true);
        hook.setRuntimeContext(context("alice", "conv-1"));

        Mono<HookEvent> out = hook.onEvent(event);
        assertEquals(event, out.block());

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(event).appendSystemContent(captor.capture());
        String injected = captor.getValue();
        assertTrue(injected.startsWith("\n\n<last_direct_exec>\n"));
        assertTrue(injected.contains("question: 杭州开发一部 7月 达标率多少"));
        assertTrue(injected.contains("skill: dept_skill"));
        assertTrue(injected.contains("script_id: script_a"));
        assertTrue(injected.contains("params: {dept=杭州开发一部, version=2026年7月份版本}"));
        assertTrue(injected.contains("result: 部门|达标率|合格率\\n杭州开发一部|98.2%"));
        assertTrue(injected.contains("复用未变更参数"));
        assertTrue(injected.trim().endsWith("</last_direct_exec>"));
    }

    @Test
    void digestNewlinesEscaped() {
        DirectSkillExecutor executor = mock(DirectSkillExecutor.class);
        when(executor.lastExec("alice", "conv-1")).thenReturn(new DirectSkillExecutor.LastDirectExec(
                "q", "s", "script_a", Map.of(), "line1\nline2", System.currentTimeMillis()));
        LastDirectExecContextHook hook = new LastDirectExecContextHook(executor, true);
        hook.setRuntimeContext(context("alice", "conv-1"));

        hook.onEvent(event).block();

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(event).appendSystemContent(captor.capture());
        String injected = captor.getValue();
        assertTrue(injected.contains("result: line1\\nline2"));
        // 注入块内部不允许出现裸换行破坏 result 单行结构
        assertTrue(java.util.Arrays.stream(injected.split("\n", -1))
                .anyMatch(line -> line.equals("result: line1\\nline2")));
    }

    @Test
    void blankDigestOmitsResultLine() {
        DirectSkillExecutor executor = mock(DirectSkillExecutor.class);
        when(executor.lastExec("alice", "conv-1")).thenReturn(new DirectSkillExecutor.LastDirectExec(
                "q", "s", "script_a", Map.of(), null, System.currentTimeMillis()));
        LastDirectExecContextHook hook = new LastDirectExecContextHook(executor, true);
        hook.setRuntimeContext(context("alice", "conv-1"));

        hook.onEvent(event).block();

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(event).appendSystemContent(captor.capture());
        assertFalse(captor.getValue().contains("result:"));
    }

    @Test
    void noSnapshotInjectsNothing() {
        DirectSkillExecutor executor = mock(DirectSkillExecutor.class);
        when(executor.lastExec("alice", "conv-1")).thenReturn(null);
        LastDirectExecContextHook hook = new LastDirectExecContextHook(executor, true);
        hook.setRuntimeContext(context("alice", "conv-1"));

        hook.onEvent(event).block();

        verify(event, never()).appendSystemContent(anyString());
    }

    @Test
    void missingRuntimeContextInjectsNothing() {
        DirectSkillExecutor executor = mock(DirectSkillExecutor.class);
        LastDirectExecContextHook hook = new LastDirectExecContextHook(executor, true);

        hook.onEvent(event).block();

        verifyNoInteractions(executor, event);
    }

    @Test
    void disabledHookShortCircuits() {
        DirectSkillExecutor executor = mock(DirectSkillExecutor.class);
        LastDirectExecContextHook hook = new LastDirectExecContextHook(executor, false);
        hook.setRuntimeContext(context("alice", "conv-1"));

        hook.onEvent(event).block();

        verifyNoInteractions(executor, event);
    }

    @Test
    void executorFailureDegradesSilently() {
        DirectSkillExecutor executor = mock(DirectSkillExecutor.class);
        when(executor.lastExec("alice", "conv-1")).thenThrow(new RuntimeException("boom"));
        LastDirectExecContextHook hook = new LastDirectExecContextHook(executor, true);
        hook.setRuntimeContext(context("alice", "conv-1"));

        // 注入失败不阻塞请求
        assertEquals(event, hook.onEvent(event).block());
        verify(event, never()).appendSystemContent(anyString());
    }
}
