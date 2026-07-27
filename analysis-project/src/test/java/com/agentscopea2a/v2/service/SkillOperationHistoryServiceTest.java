package com.agentscopea2a.v2.service;

import com.agentscopea2a.entity.SkillOperationHistory;
import com.agentscopea2a.mapper.mysql.SkillOperationHistoryMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SkillOperationHistoryServiceTest {

    @Test
    void record_inserts_history_row() {
        SkillOperationHistoryMapper mapper = mock(SkillOperationHistoryMapper.class);
        SkillOperationHistoryService svc = new SkillOperationHistoryService(mapper);

        svc.record(1L, null, "u1", "CREATE", null, "{\"name\":\"SQL\"}");

        ArgumentCaptor<SkillOperationHistory> captor = ArgumentCaptor.forClass(SkillOperationHistory.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getSkillId()).isEqualTo(1L);
        assertThat(captor.getValue().getOperation()).isEqualTo("CREATE");
        assertThat(captor.getValue().getOperator()).isEqualTo("u1");
    }
}
