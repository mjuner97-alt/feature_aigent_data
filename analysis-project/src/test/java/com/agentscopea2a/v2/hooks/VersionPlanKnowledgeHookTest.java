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
package com.agentscopea2a.v2.hooks;

import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PreCallEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link VersionPlanKnowledgeHook}: multi-version ordered de-dup injection,
 * TTL caching, failure backoff and silent degradation.
 */
class VersionPlanKnowledgeHookTest {

    private final PreCallEvent event = mock(PreCallEvent.class);

    private static VersionPlanKnowledgeHook hook(JdbcTemplate jdbc, boolean enabled) {
        return new VersionPlanKnowledgeHook(jdbc, enabled, 60);
    }

    @Test
    @SuppressWarnings("unchecked")
    void injectsWindowVersionsDedupedInOrder() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(String.class))).thenReturn(Arrays.asList(
                "2026年9月份版本", "2026年10月份版本", "2026年10月份版本", "2026年11月份版本"));
        VersionPlanKnowledgeHook hook = hook(jdbc, true);

        Mono<HookEvent> out = hook.onEvent(event);
        assertEquals(event, out.block());

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(event).appendSystemContent(captor.capture());
        String injected = captor.getValue();
        assertTrue(injected.contains("<domain_knowledge_current_version>"));
        assertTrue(injected.contains("2026年9月份版本、2026年10月份版本、2026年11月份版本"));
        assertTrue(injected.contains("当前在建季度（由在建版本推算，可能为多个，按投产时间先后排列）：2026年3季度、2026年4季度"));
        assertTrue(injected.trim().endsWith("</domain_knowledge_current_version>"));

        // within TTL the second call must not hit the DB again
        hook.onEvent(event).block();
        verify(jdbc, times(1)).queryForList(anyString(), eq(String.class));
    }

    @Test
    void skipsNullAndBlankRows() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(String.class)))
                .thenReturn(Arrays.asList(null, "  ", "2026年10月份版本"));
        VersionPlanKnowledgeHook hook = hook(jdbc, true);

        hook.onEvent(event).block();
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(event).appendSystemContent(captor.capture());
        assertEquals("\n\n<domain_knowledge_current_version>\n"
                + "当前在建版本（当前版本计划，实时从火车日历计算，按投产时间先后排列）：2026年10月份版本\n"
                + "当前在建季度（由在建版本推算，可能为多个，按投产时间先后排列）：2026年4季度\n"
                + "</domain_knowledge_current_version>\n", captor.getValue());
    }

    @Test
    void deriveQuartersSkipsUnparsableVersions() {
        assertEquals(List.of("2026年3季度", "2026年4季度", "2027年1季度"),
                VersionPlanKnowledgeHook.deriveQuarters(Arrays.asList(
                        "2026年9月份版本", "2026年10月份版本", "2026年11月份版本",
                        "2026年12月份版本", "2027年1月份版本", "非标准格式", null, "  ")));
    }

    @Test
    void emptyResultInjectsNothing() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(String.class))).thenReturn(Collections.emptyList());
        VersionPlanKnowledgeHook hook = hook(jdbc, true);

        hook.onEvent(event).block();
        verifyNoInteractions(event);
    }

    @Test
    void refreshFailureDegradesSilentlyWithBackoff() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(String.class)))
                .thenThrow(new RuntimeException("gauss down"));
        VersionPlanKnowledgeHook hook = hook(jdbc, true);

        // must not throw, must not inject
        hook.onEvent(event).block();
        verify(event, never()).appendSystemContent(anyString());

        // backoff: the immediate retry must not hit the DB again
        hook.onEvent(event).block();
        verify(jdbc, times(1)).queryForList(anyString(), eq(String.class));
    }

    @Test
    void failureServesStaleValueAfterSuccessfulFirstLoad() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(String.class)))
                .thenReturn(List.of("2026年10月份版本"))
                .thenThrow(new RuntimeException("gauss down"));
        // tiny TTL so the cache expires between calls despite the same clock second
        VersionPlanKnowledgeHook shortTtl = new VersionPlanKnowledgeHook(jdbc, true, 0);

        shortTtl.onEvent(event).block();
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(event).appendSystemContent(captor.capture());
        assertTrue(captor.getValue().contains("2026年10月份版本"));

        // expired cache + failed refresh -> still injects the stale value
        shortTtl.onEvent(event).block();
        verify(event, times(2)).appendSystemContent(captor.capture());
        assertTrue(captor.getValue().contains("2026年10月份版本"));
        verify(jdbc, times(2)).queryForList(anyString(), eq(String.class));

        // backoff window: third call serves stale without a DB attempt
        shortTtl.onEvent(event).block();
        verify(jdbc, times(2)).queryForList(anyString(), eq(String.class));
    }

    @Test
    void priorityRunsBeforeKnowledgeRetrievalHook() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        VersionPlanKnowledgeHook hook = hook(jdbc, true);
        assertEquals(-45, hook.priority());
    }

    @Test
    void disabledHookShortCircuits() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        VersionPlanKnowledgeHook hook = hook(jdbc, false);

        hook.onEvent(event).block();
        verifyNoInteractions(jdbc, event);
    }
}
