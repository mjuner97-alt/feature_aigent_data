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
import com.agentscopea2a.mapper.mysql.SkillReferenceMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkillReferenceServiceTest {

    private SkillReferenceMapper refMapper;
    private SkillService skillService;
    private SkillReferenceService service;

    @BeforeEach
    void setUp() {
        refMapper = mock(SkillReferenceMapper.class);
        skillService = mock(SkillService.class);
        service = new SkillReferenceService(refMapper, skillService);
    }

    @Test
    void reference_inserts_when_not_referenced() {
        when(skillService.get(7L)).thenReturn(Skill.builder().id(7L).status("ACTIVE").build());
        when(refMapper.existsByCreatorTarget("u1", 7L)).thenReturn(false);

        service.reference(7L, "u1");

        verify(refMapper).insert(any());
    }

    @Test
    void reference_is_idempotent_when_already_referenced() {
        when(skillService.get(7L)).thenReturn(Skill.builder().id(7L).status("ACTIVE").build());
        when(refMapper.existsByCreatorTarget("u1", 7L)).thenReturn(true);

        service.reference(7L, "u1");

        verify(refMapper, never()).insert(any());
    }

    @Test
    void reference_catches_duplicate_key_race() {
        when(skillService.get(7L)).thenReturn(Skill.builder().id(7L).status("ACTIVE").build());
        when(refMapper.existsByCreatorTarget("u1", 7L)).thenReturn(false);
        when(refMapper.insert(any())).thenThrow(new DuplicateKeyException("uk"));

        service.reference(7L, "u1"); // should not throw
    }

    @Test
    void listMine_returns_referenced_skill_ids() {
        when(refMapper.selectSkillIdsByCreator("u1")).thenReturn(List.of(7L, 8L));
        assertThat(service.listMine("u1")).containsExactly(7L, 8L);
    }
}
