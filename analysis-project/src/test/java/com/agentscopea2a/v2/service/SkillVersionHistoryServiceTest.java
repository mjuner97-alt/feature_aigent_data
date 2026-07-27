package com.agentscopea2a.v2.service;

import com.agentscopea2a.entity.Skill;
import com.agentscopea2a.entity.SkillVersionHistory;
import com.agentscopea2a.mapper.mysql.SkillVersionHistoryMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SkillVersionHistoryServiceTest {

    @Test
    void saveVersion_increments_version_and_inserts() {
        SkillVersionHistoryMapper vhMapper = mock(SkillVersionHistoryMapper.class);
        when(vhMapper.selectMaxVersion(1L)).thenReturn(2);
        SkillVersionHistoryService svc = new SkillVersionHistoryService(vhMapper);

        Skill skill = Skill.builder().id(1L).name("SQL").description("d").content("c")
                .category("数据").tags("#sql").build();
        svc.saveVersion(skill, "u1", "edit reason");

        ArgumentCaptor<SkillVersionHistory> captor = ArgumentCaptor.forClass(SkillVersionHistory.class);
        verify(vhMapper).insert(captor.capture());
        assertThat(captor.getValue().getVersion()).isEqualTo(3);
        assertThat(captor.getValue().getEditedBy()).isEqualTo("u1");
        assertThat(captor.getValue().getName()).isEqualTo("SQL");
    }

    @Test
    void saveVersion_first_version_is_1() {
        SkillVersionHistoryMapper vhMapper = mock(SkillVersionHistoryMapper.class);
        when(vhMapper.selectMaxVersion(1L)).thenReturn(null);
        SkillVersionHistoryService svc = new SkillVersionHistoryService(vhMapper);

        Skill skill = Skill.builder().id(1L).name("SQL").build();
        svc.saveVersion(skill, "u1", null);

        ArgumentCaptor<SkillVersionHistory> captor = ArgumentCaptor.forClass(SkillVersionHistory.class);
        verify(vhMapper).insert(captor.capture());
        assertThat(captor.getValue().getVersion()).isEqualTo(1);
    }
}
