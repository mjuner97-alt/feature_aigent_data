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

import com.agentscopea2a.v2.hooks.SkillFixedToolGuardHook.FixedToolIds;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.hook.PreActingEvent;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Tests for {@link SkillFixedToolGuardHook}: fixed-ID extraction from skill bodies,
 * guard arming on load_skill_through_path, and discovery-result override on
 * tool_index / toolMetaInfo.
 */
class SkillFixedToolGuardHookTest {

    private static final Agent AGENT = mock(Agent.class);

    private SkillFixedToolGuardHook hook;
    private RuntimeContext ctx;

    /** Mirrors the real hy_zhongdianxuqiuxiang_python SKILL.md body (code blocks). */
    private static final String FIXED_ID_SKILL_BODY = """
            ## 执行纪律

            Skill 明确给出的 `toolId`、`sqlId`、`scriptId` 必须原样使用。

            #### Step 2: 直接调 script_exec 取数

            ```
            script_exec(
              scriptId="q2_1_metrics_by_dept_version",
              params={"dept":"杭州开发二部","version":"2026年7月份版本"}
            )
            ```

            #### Step 3: 下载明细

            ```
            sql_registry_exec(
              sqlId="q2_1_metrics_by_dept_version",
              params={"dept":"杭州开发二部", "version":"2026年7月份版本"}
             )
             ```
            """;

    /** Mirrors the _common/SKILL.md rule prose — must NOT arm the guard. */
    private static final String RULE_PROSE = """
            若当前 Skill 正文明确给出固定的 `toolId`、`sqlId` 或 `scriptId`、调用顺序及参数，
            必须严格按正文执行。禁止调用 `tool_index`、`toolMetaInfo` 或 `router_tool`
            验证、替换或重新发现这些固定 ID。
            """;

    @BeforeEach
    void setUp() {
        hook = new SkillFixedToolGuardHook();
        ctx = RuntimeContext.empty();
        hook.setRuntimeContext(ctx);
    }

    // ==================== parseFixedIds ====================

    @Test
    void extractsQuotedSqlAndScriptIdsFromSkillBody() {
        FixedToolIds fixed = SkillFixedToolGuardHook.parseFixedIds(FIXED_ID_SKILL_BODY, "hy_skill");
        assertEquals(java.util.Set.of("q2_1_metrics_by_dept_version"), fixed.sqlIds());
        assertEquals(java.util.Set.of("q2_1_metrics_by_dept_version"), fixed.scriptIds());
        assertTrue(fixed.isArmed());
    }

    @Test
    void ruleProseMentioningIdsWithoutAssignmentDoesNotArm() {
        FixedToolIds fixed = SkillFixedToolGuardHook.parseFixedIds(RULE_PROSE, "_common");
        assertFalse(fixed.isArmed(), "prose mentioning `sqlId` without an =/: assignment must not arm the guard");
    }

    @Test
    void dynamicSkillWithoutFixedIdsStaysDisarmed() {
        FixedToolIds fixed = SkillFixedToolGuardHook.parseFixedIds("按目录主题动态选择工具", "catalog");
        assertFalse(fixed.isArmed());
    }

    @Test
    void unquotedAssignmentAlsoExtracted() {
        FixedToolIds fixed = SkillFixedToolGuardHook.parseFixedIds("调用 toolId: wide_table_query 取数", "t");
        assertEquals(java.util.Set.of("wide_table_query"), fixed.toolIds());
    }

    // ==================== arming via load_skill_through_path ====================

    @Test
    void successfulSkillLoadWithFixedIdsArmsGuardOnContext() {
        fire(loadSkillEvent("hy_skill", FIXED_ID_SKILL_BODY, ToolResultState.SUCCESS));
        FixedToolIds fixed = ctx.get(SkillFixedToolGuardHook.FIXED_IDS_CTX_KEY);
        assertNotNull(fixed);
        assertTrue(fixed.isArmed());
    }

    @Test
    void commonSkillLoadDoesNotArm() {
        fire(loadSkillEvent("_common", RULE_PROSE, ToolResultState.SUCCESS));
        assertNull(ctx.get(SkillFixedToolGuardHook.FIXED_IDS_CTX_KEY));
    }

    @Test
    void failedSkillLoadDoesNotArm() {
        fire(loadSkillEvent("hy_skill", "skill not found", ToolResultState.ERROR));
        assertNull(ctx.get(SkillFixedToolGuardHook.FIXED_IDS_CTX_KEY));
    }

    // ==================== discovery override ====================

    @Test
    void toolIndexResultIsOverriddenWhenGuardArmed() {
        fire(loadSkillEvent("hy_skill", FIXED_ID_SKILL_BODY, ToolResultState.SUCCESS));

        PostActingEvent discovery = toolIndexEvent("{\"matchedCount\":0}");
        fire(discovery);

        String text = ((TextBlock) discovery.getToolResult().getOutput().get(0)).getText();
        assertTrue(text.contains("已被拦截"), "discovery result should be replaced with corrective message");
        assertTrue(text.contains("q2_1_metrics_by_dept_version"), "corrective message should echo the fixed IDs");
        assertNotNull(discovery.getToolResultMsg(), "toolResultMsg must also be replaced or the LLM never sees it");
        assertEquals(discovery.getToolResult(), discovery.getToolResultMsg().getContent().get(0));
    }

    @Test
    void toolMetaInfoResultIsAlsoOverridden() {
        fire(loadSkillEvent("hy_skill", FIXED_ID_SKILL_BODY, ToolResultState.SUCCESS));

        PostActingEvent meta = new PostActingEvent(AGENT, null,
                toolUse("toolMetaInfo", Map.of("toolId", "anything")),
                ToolResultBlock.text("{}"));
        fire(meta);

        String text = ((TextBlock) meta.getToolResult().getOutput().get(0)).getText();
        assertTrue(text.contains("已被拦截"));
    }

    @Test
    void toolIndexUnaffectedWhenNoSkillLoaded() {
        PostActingEvent discovery = toolIndexEvent("{\"matchedCount\":3,\"candidates\":[...]}");
        fire(discovery);
        String text = ((TextBlock) discovery.getToolResult().getOutput().get(0)).getText();
        assertEquals("{\"matchedCount\":3,\"candidates\":[...]}", text);
    }

    @Test
    void executorToolsAreNeverOverridden() {
        fire(loadSkillEvent("hy_skill", FIXED_ID_SKILL_BODY, ToolResultState.SUCCESS));

        PostActingEvent exec = new PostActingEvent(AGENT, null,
                toolUse("sql_registry_exec", Map.of("sqlId", "q2_1_metrics_by_dept_version")),
                ToolResultBlock.text("📥 下载链接: /redirect/download?shortCode=abc"));
        fire(exec);

        String text = ((TextBlock) exec.getToolResult().getOutput().get(0)).getText();
        assertEquals("📥 下载链接: /redirect/download?shortCode=abc", text);
    }

    // ==================== PreActing executor-ID correction ====================

    @Test
    void hallucinatedSqlIdIsSilentlyCorrectedToFixedId() {
        fire(loadSkillEvent("hy_skill", FIXED_ID_SKILL_BODY, ToolResultState.SUCCESS));

        PreActingEvent exec = new PreActingEvent(AGENT, null,
                toolUse("sql_registry_exec", Map.of("sqlId", "q2_1_metrics_by_dept_version_detail",
                        "params", Map.of("dept", "杭州开发二部"))));
        fire(exec);

        assertEquals("q2_1_metrics_by_dept_version", exec.getToolUse().getInput().get("sqlId"));
        assertEquals(Map.of("dept", "杭州开发二部"), exec.getToolUse().getInput().get("params"));
    }

    @Test
    void correctFixedIdPassesThroughUnchanged() {
        fire(loadSkillEvent("hy_skill", FIXED_ID_SKILL_BODY, ToolResultState.SUCCESS));

        PreActingEvent exec = new PreActingEvent(AGENT, null,
                toolUse("script_exec", Map.of("scriptId", "q2_1_metrics_by_dept_version")));
        fire(exec);

        assertEquals("q2_1_metrics_by_dept_version", exec.getToolUse().getInput().get("scriptId"));
    }

    @Test
    void noCorrectionWhenGuardNotArmed() {
        PreActingEvent exec = new PreActingEvent(AGENT, null,
                toolUse("sql_registry_exec", Map.of("sqlId", "anything_else")));
        fire(exec);
        assertEquals("anything_else", exec.getToolUse().getInput().get("sqlId"));
    }

    // ==================== helpers ====================

    private void fire(HookEvent event) {
        // No Reactor context in tests -> hook falls back to the ctx set via setRuntimeContext.
        hook.onEvent(event).block();
    }

    private PostActingEvent loadSkillEvent(String skillName, String body, ToolResultState state) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("name", skillName);
        input.put("path", "SKILL.md");
        ToolResultBlock result = ToolResultBlock.of("call-1", "load_skill_through_path",
                java.util.List.of(TextBlock.builder().text(body).build()));
        if (state == ToolResultState.ERROR) {
            result = result.withState(ToolResultState.ERROR);
        }
        return new PostActingEvent(AGENT, null, toolUse("load_skill_through_path", input), result);
    }

    private PostActingEvent toolIndexEvent(String json) {
        return new PostActingEvent(AGENT, null,
                toolUse("tool_index", Map.of("filters", Map.of())),
                ToolResultBlock.text(json));
    }

    private static ToolUseBlock toolUse(String name, Map<String, Object> input) {
        return new ToolUseBlock("call-1", name, input);
    }
}
