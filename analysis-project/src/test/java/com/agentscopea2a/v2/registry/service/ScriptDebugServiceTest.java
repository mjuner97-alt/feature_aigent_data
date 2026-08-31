package com.agentscopea2a.v2.registry.service;

import com.agentscopea2a.entity.ScriptRegistryEntry;
import com.agentscopea2a.mapper.gauss.ScriptRegistryMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ScriptDebugServiceTest {
    @Test
    void runsRegisteredScriptAndPublishesCompletedResult() throws Exception {
        ScriptRegistryMapper mapper = mock(ScriptRegistryMapper.class);
        when(mapper.selectById(7L)).thenReturn(ScriptRegistryEntry.builder()
                .id(7L).scriptId("demo").scriptPath("demo.py")
                .paramsSchema("[]").timeoutSeconds(60).enabled(1).build());
        ScriptDebugService service = new ScriptDebugService(mapper, new ScriptParamValidator(),
                (scriptId, params) -> "[script_exec] scriptId=demo exit=0 elapsed=1ms\\n\\n─── stdout ─────────────────────────\\nok\\n");
        try {
            var run = service.start(7L, Map.of(), 10);
            waitForTerminal(service, run.runId());
            var result = service.get(run.runId());

            assertEquals("SUCCESS", result.status());
            assertEquals(0, result.exitCode());
            assertTrue(result.stdout().contains("ok"));
            assertFalse(service.cancel(run.runId()));
        } finally {
            service.close();
        }
    }

    private static void waitForTerminal(ScriptDebugService service, String runId) throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            if (!"RUNNING".equals(service.get(runId).status()) && !"QUEUED".equals(service.get(runId).status())) return;
            Thread.sleep(20);
        }
        fail("debug run did not finish");
    }

    @Test
    void cancellationRemainsCancelledWhenInvokerIsInterrupted() throws Exception {
        ScriptRegistryMapper mapper = mock(ScriptRegistryMapper.class);
        when(mapper.selectById(8L)).thenReturn(ScriptRegistryEntry.builder()
                .id(8L).scriptId("slow").paramsSchema("[]").timeoutSeconds(60).enabled(1).build());
        ScriptDebugService service = new ScriptDebugService(mapper, new ScriptParamValidator(), (id, params) -> {
            try { Thread.sleep(10_000); } catch (InterruptedException e) { throw e; }
            return "";
        });
        try {
            var run = service.start(8L, Map.of(), 10);
            Thread.sleep(30);
            assertTrue(service.cancel(run.runId()));
            Thread.sleep(30);
            assertEquals("CANCELLED", service.get(run.runId()).status());
        } finally {
            service.close();
        }
    }
}
