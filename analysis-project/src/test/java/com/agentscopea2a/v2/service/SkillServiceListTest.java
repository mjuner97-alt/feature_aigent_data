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

import com.agentscopea2a.dto.SkillListItem;
import com.agentscopea2a.dto.SkillListQuery;
import com.agentscopea2a.entity.Skill;
import com.agentscopea2a.mapper.mysql.SkillLikeMapper;
import com.agentscopea2a.mapper.mysql.SkillManageMapper;
import com.agentscopea2a.mapper.mysql.SkillReferenceMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link SkillService#list} 单元测试:热门榜排名 + liked/used 批量标记 + 空列表。
 */
class SkillServiceListTest {

    private SkillManageMapper manageMapper;
    private SkillLikeMapper likeMapper;
    private SkillReferenceMapper refMapper;
    private SkillService service;

    @BeforeEach
    void setUp() {
        manageMapper = mock(SkillManageMapper.class);
        likeMapper = mock(SkillLikeMapper.class);
        refMapper = mock(SkillReferenceMapper.class);
        service = new SkillService(manageMapper, likeMapper, refMapper);
    }

    @Test
    void list_popular_assigns_rank_and_marks_liked_used() {
        Skill a = Skill.builder().id(1L).name("A").likeCount(120L).status("ACTIVE").build();
        Skill b = Skill.builder().id(2L).name("B").likeCount(80L).status("ACTIVE").build();
        when(manageMapper.selectList(any())).thenReturn(List.of(a, b));
        when(likeMapper.selectLikedSkillIds("u1", List.of(1L, 2L))).thenReturn(Set.of(1L));
        when(refMapper.selectUsedSkillIds("u1", List.of(1L, 2L))).thenReturn(Set.of(2L));

        SkillListQuery q = new SkillListQuery("popular", "likes", null, null, null, 20, 0, "u1");
        List<SkillListItem> items = service.list(q);

        assertThat(items).hasSize(2);
        assertThat(items.get(0).rank()).isEqualTo(1);
        assertThat(items.get(0).liked()).isTrue();
        assertThat(items.get(0).used()).isFalse();
        assertThat(items.get(1).rank()).isEqualTo(2);
        assertThat(items.get(1).liked()).isFalse();
        assertThat(items.get(1).used()).isTrue();
        assertThat(items.get(0).available()).isTrue();
    }

    @Test
    void list_empty_returns_empty() {
        when(manageMapper.selectList(any())).thenReturn(List.of());
        SkillListQuery q = new SkillListQuery("all", "likes", null, null, null, 20, 0, "u1");
        assertThat(service.list(q)).isEmpty();
    }
}
