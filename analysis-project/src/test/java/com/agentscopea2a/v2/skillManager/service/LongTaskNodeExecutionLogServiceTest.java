package com.agentscopea2a.v2.skillManager.service;

import com.agentscopea2a.mapper.ck.LongTaskNodeExecutionLogMapper;
import com.agentscopea2a.v2.skillManager.entity.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LongTaskNodeExecutionLogServiceTest {
    private final LongTaskNodeExecutionLogMapper mapper = mock(LongTaskNodeExecutionLogMapper.class);
    private final LongTaskNodeExecutionLogService service = new LongTaskNodeExecutionLogService(mapper, null, null);
    private final SkillFlowExecution flow = SkillFlowExecution.builder().id(1L).build();
    private final SkillFlowNodeExecution node = SkillFlowNodeExecution.builder().id(2L).scriptId("test-script").build();

    @Test
    void fractionalTimesBecomeEpochSecondsInBusinessTimezone() {
        var attempt = SkillFlowNodeAttempt.builder().id(3L).attemptNo(1)
                .startedAt(LocalDateTime.parse("2026-11-05T14:26:54.594235"))
                .completedAt(LocalDateTime.parse("2026-11-05T14:26:55.828802"))
                .durationMs(1234L).status(FlowNodeAttemptStatus.SUCCESS).build();

        service.record(flow, node, attempt, "{}", "output", null);

        Map<String, Object> row = insertedRow();
        assertEquals(OffsetDateTime.parse("2026-11-05T14:26:54+08:00").toEpochSecond(), row.get("startedAt"));
        assertEquals(OffsetDateTime.parse("2026-11-05T14:26:55+08:00").toEpochSecond(), row.get("completedAt"));
        assertEquals(1234L, row.get("durationMs"));
    }

    @Test
    void missingTimesUseEpochZero() {
        service.record(flow, node, SkillFlowNodeAttempt.builder().id(3L).build(), null, null, null);

        Map<String, Object> row = insertedRow();
        assertEquals(0L, row.get("startedAt"));
        assertEquals(0L, row.get("completedAt"));
    }

    @Test
    void insertFailureDoesNotInterruptWorkflow() {
        doThrow(new IllegalStateException("simulated ClickHouse failure")).when(mapper).insert(anyMap());
        assertDoesNotThrow(() -> service.record(flow, node,
                SkillFlowNodeAttempt.builder().id(3L).build(), null, null, null));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Map<String, Object> insertedRow() {
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass((Class) Map.class);
        verify(mapper).insert(captor.capture());
        return captor.getValue();
    }
}
