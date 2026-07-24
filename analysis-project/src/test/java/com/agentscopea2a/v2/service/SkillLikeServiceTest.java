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

import com.agentscopea2a.dto.LikeStatus;
import com.agentscopea2a.entity.Skill;
import com.agentscopea2a.mapper.mysql.SkillLikeMapper;
import com.agentscopea2a.mapper.mysql.SkillManageMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkillLikeServiceTest {

    private SkillLikeMapper likeMapper;
    private SkillManageMapper manageMapper;
    private SkillService skillService;
    private SkillLikeService service;

    @BeforeEach
    void setUp() {
        likeMapper = mock(SkillLikeMapper.class);
        manageMapper = mock(SkillManageMapper.class);
        skillService = mock(SkillService.class);
        service = new SkillLikeService(likeMapper, manageMapper, skillService);
    }

    private Skill activeSkill() {
        return Skill.builder().id(10L).name("n").status("ACTIVE").build();
    }

    @Test
    void like_when_not_liked_inserts_and_increments() {
        when(skillService.get(10L)).thenReturn(activeSkill());
        when(likeMapper.selectByUserSkill("u1", 10L)).thenReturn(null);
        when(manageMapper.selectLikeCount(10L)).thenReturn(5L);

        LikeStatus status = service.like(10L, "u1");

        assertThat(status.liked()).isTrue();
        assertThat(status.likeCount()).isEqualTo(5L);
        verify(likeMapper).insert(any());
        verify(manageMapper).incrementLikeCount(10L);
    }

    @Test
    void like_when_already_liked_is_idempotent_no_increment() {
        when(skillService.get(10L)).thenReturn(activeSkill());
        when(likeMapper.selectByUserSkill("u1", 10L)).thenReturn(
                com.agentscopea2a.entity.SkillLike.builder().id(1L).skillId(10L).userId("u1").build());
        when(manageMapper.selectLikeCount(10L)).thenReturn(5L);

        LikeStatus status = service.like(10L, "u1");

        assertThat(status.liked()).isTrue();
        verify(likeMapper, never()).insert(any());
        verify(manageMapper, never()).incrementLikeCount(10L);
    }

    @Test
    void like_catches_duplicate_key_race_as_idempotent() {
        when(skillService.get(10L)).thenReturn(activeSkill());
        when(likeMapper.selectByUserSkill("u1", 10L)).thenReturn(null);
        when(likeMapper.insert(any())).thenThrow(new DuplicateKeyException("uk_user_skill"));
        when(manageMapper.selectLikeCount(10L)).thenReturn(5L);

        LikeStatus status = service.like(10L, "u1");

        assertThat(status.liked()).isTrue();
        verify(manageMapper, never()).incrementLikeCount(10L);
    }

    @Test
    void unlike_when_liked_deletes_and_decrements() {
        when(skillService.get(10L)).thenReturn(activeSkill());
        when(likeMapper.selectByUserSkill("u1", 10L)).thenReturn(
                com.agentscopea2a.entity.SkillLike.builder().id(1L).skillId(10L).userId("u1").build());
        when(manageMapper.selectLikeCount(10L)).thenReturn(4L);

        LikeStatus status = service.unlike(10L, "u1");

        assertThat(status.liked()).isFalse();
        assertThat(status.likeCount()).isEqualTo(4L);
        verify(likeMapper).deleteByUserSkill("u1", 10L);
        verify(manageMapper).decrementLikeCount(10L);
    }

    @Test
    void unlike_when_not_liked_is_idempotent_no_decrement() {
        when(skillService.get(10L)).thenReturn(activeSkill());
        when(likeMapper.selectByUserSkill("u1", 10L)).thenReturn(null);
        when(manageMapper.selectLikeCount(10L)).thenReturn(5L);

        LikeStatus status = service.unlike(10L, "u1");

        assertThat(status.liked()).isFalse();
        verify(likeMapper, never()).deleteByUserSkill(eq("u1"), eq(10L));
        verify(manageMapper, never()).decrementLikeCount(10L);
    }
}
