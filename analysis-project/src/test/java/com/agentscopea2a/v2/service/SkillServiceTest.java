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
package com.agentscopea2a.v2.service;

import com.agentscopea2a.entity.Skill;
import com.agentscopea2a.mapper.mysql.SkillLikeMapper;
import com.agentscopea2a.mapper.mysql.SkillManageMapper;
import com.agentscopea2a.mapper.mysql.SkillReferenceMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SkillService} CRUD 单元测试。setUp 注入 3 参构造(Task 4 版本)。
 */
class SkillServiceTest {

    private SkillManageMapper mapper;
    private SkillService service;

    @BeforeEach
    void setUp() {
        mapper = mock(SkillManageMapper.class);
        service = new SkillService(mapper,
                mock(SkillLikeMapper.class),
                mock(SkillReferenceMapper.class));
    }

    @Test
    void create_sets_owner_status_active_likeCount0_and_inserts() {
        when(mapper.existsByName("SQL优化")).thenReturn(false);
        Skill input = Skill.builder().name("SQL优化").description("d").content("c")
                .category("数据").tags("#sql").build();
        Skill persisted = Skill.builder().id(1L).name("SQL优化").ownerUserId("u1")
                .status("ACTIVE").likeCount(0L).build();
        when(mapper.selectById(any())).thenReturn(persisted);

        Skill result = service.create(input, "u1");

        ArgumentCaptor<Skill> captor = ArgumentCaptor.forClass(Skill.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getOwnerUserId()).isEqualTo("u1");
        assertThat(captor.getValue().getStatus()).isEqualTo("ACTIVE");
        assertThat(captor.getValue().getLikeCount()).isZero();
        assertThat(result.getId()).isEqualTo(1L);
    }

    @Test
    void create_rejects_duplicate_name() {
        when(mapper.existsByName("SQL优化")).thenReturn(true);
        Skill input = Skill.builder().name("SQL优化").build();
        assertThatThrownBy(() -> service.create(input, "u1"))
                .hasMessageContaining("SkillNameConflict");
    }

    @Test
    void update_denies_non_owner() {
        Skill s = Skill.builder().id(1L).name("n").ownerUserId("u1").status("ACTIVE").build();
        when(mapper.selectById(1L)).thenReturn(s);
        assertThatThrownBy(() -> service.update(1L, Skill.builder().name("n2").build(), "u2"))
                .hasMessageContaining("SkillAccessDenied");
    }

    @Test
    void delete_soft_deletes_for_owner() {
        Skill s = Skill.builder().id(1L).name("n").ownerUserId("u1").status("ACTIVE").build();
        when(mapper.selectById(1L)).thenReturn(s);
        service.delete(1L, "u1");
        verify(mapper).softDelete(eq(1L));
    }
}
