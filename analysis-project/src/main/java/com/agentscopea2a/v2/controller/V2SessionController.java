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
package com.agentscopea2a.v2.controller;

import com.agentscopea2a.v2.config.HarnessRunnerProperties;
import com.agentscopea2a.v2.dto.SessionStateResponse;
import com.agentscopea2a.v2.runner.HarnessA2aRunnerV2;
import com.agentscopea2a.v2.util.PermissionModeHelper;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.interruption.InterruptControl;
import io.agentscope.core.message.Msg;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.PlanModeContextState;
import io.agentscope.core.state.Task;
import io.agentscope.core.state.TaskContextState;
import io.agentscope.harness.agent.HarnessAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * User-facing session-level operations.
 *
 * <p>Hosts the migrated {@code /permission/mode} endpoints (previously under {@code /debug/}).
 * The debug paths remain functional but emit RFC 7234 deprecation headers pointing here -
 * see {@code DebugController}.
 *
 * <ul>
 *   <li>{@code GET /v2/ai/session/permission/mode?userId=X&sessionId=Y} - get current mode
 *   <li>{@code POST /v2/ai/session/permission/mode?userId=X&sessionId=Y&mode=bypass} - switch mode
 *   <li>{@code GET /v2/ai/session/state?userId=X&conversationId=Y} - PlanNotebook + AgentState
 *       snapshot for frontend polling (see {@link SessionStateResponse})
 * </ul>
 *
 * <p>Valid modes: {@code default}, {@code accept_edits}, {@code explore}, {@code bypass},
 * {@code dont_ask}. Use {@code bypass} to auto-approve HITL prompts (e.g. {@code plan_exit}
 * approval) for E2E tests that need execution to continue without manual approval.
 *
 * <p>The actual logic lives in {@link PermissionModeHelper} so the deprecated
 * {@code /debug/permission/mode} endpoint stays behaviorally identical during migration.
 */
@RestController
@RequestMapping("/v2/ai/session")
@CrossOrigin(origins = "*", maxAge = 3600)
public class V2SessionController {

    private static final Logger log = LoggerFactory.getLogger(V2SessionController.class);

    private final PermissionModeHelper permissionModeHelper;
    private final ObjectProvider<HarnessA2aRunnerV2> runnerProvider;
    private final HarnessRunnerProperties runnerProperties;

    public V2SessionController(PermissionModeHelper permissionModeHelper,
                               ObjectProvider<HarnessA2aRunnerV2> runnerProvider,
                               HarnessRunnerProperties runnerProperties) {
        this.permissionModeHelper = permissionModeHelper;
        this.runnerProvider = runnerProvider;
        this.runnerProperties = runnerProperties;
    }

    @GetMapping("/permission/mode")
    public ResponseEntity<Map<String, Object>> getPermissionMode(
            @RequestParam("userId") String userId,
            @RequestParam("sessionId") String sessionId) {
        return permissionModeHelper.getPermissionMode(userId, sessionId);
    }

    @PostMapping("/permission/mode")
    public ResponseEntity<Map<String, Object>> setPermissionMode(
            @RequestParam("userId") String userId,
            @RequestParam("sessionId") String sessionId,
            @RequestParam("mode") String modeStr) {
        return permissionModeHelper.setPermissionMode(userId, sessionId, modeStr);
    }

    /**
     * Snapshot of PlanNotebook + AgentState sub-contexts + InterruptControl for
     * the frontend's Plan/Todo/State panels. Polled every 2s by the React UI;
     * see {@code docs/Plan-Machie/plan-notebook-frontend-design.md}.
     *
     * <p>Read-only - {@link ReActAgent#getAgentState(String, String)} loads from
     * the stateCache (in-memory) or {@code stateStore.get(...)} (MySQL), creating
     * a fresh empty state in memory only if neither exists (no persistence side-effect).
     */
    @GetMapping("/state")
    public ResponseEntity<SessionStateResponse> getState(
            @RequestParam("userId") String userId,
            @RequestParam("conversationId") String conversationId) {

        HarnessA2aRunnerV2 runner = runnerProvider.getIfAvailable();
        if (runner == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "HarnessA2aRunnerV2 bean not available");
        }

        HarnessAgent agent = runner.getAgent();
        AgentState state = agent.getDelegate().getAgentState(userId, conversationId);

        if (state == null) {
            return ResponseEntity.ok(SessionStateResponse.builder()
                    .userId(userId).conversationId(conversationId).exists(false)
                    .planMode(SessionStateResponse.PlanModeState.builder().planActive(false).build())
                    .tasks(Collections.emptyList())
                    .permission(SessionStateResponse.PermissionState.builder().mode("default").build())
                    .interruptControl(SessionStateResponse.InterruptState.builder().flag(false).build())
                    .build());
        }

        PlanModeContextState pm = state.getPlanModeContext();
        TaskContextState tc = state.getTasksContext();
        PermissionContextState pc = state.getPermissionContext();
        InterruptControl ic = state.interruptControl();

        String planContent = readPlanContent(pm);

        List<SessionStateResponse.TaskState> tasks = new ArrayList<>();
        if (tc != null && tc.getTasks() != null) {
            for (Task t : tc.getTasks()) {
                tasks.add(SessionStateResponse.TaskState.builder()
                        .id(t.getId())
                        .subject(t.getSubject())
                        .description(t.getDescription())
                        .state(t.getState() != null ? t.getState().name() : null)
                        .createdAt(t.getCreatedAt())
                        .owner(t.getOwner())
                        .blocks(t.getBlocks())
                        .blockedBy(t.getBlockedBy())
                        .build());
            }
        }

        return ResponseEntity.ok(SessionStateResponse.builder()
                .userId(userId)
                .conversationId(conversationId)
                .exists(true)
                .planMode(SessionStateResponse.PlanModeState.builder()
                        .planActive(pm != null && pm.isPlanActive())
                        .currentPlanFile(pm != null ? pm.getCurrentPlanFile() : null)
                        .planContent(planContent)
                        .build())
                .tasks(tasks)
                .permission(SessionStateResponse.PermissionState.builder()
                        .mode(pc != null && pc.getMode() != null ? pc.getMode().name() : "default")
                        .build())
                .interruptControl(SessionStateResponse.InterruptState.builder()
                        .flag(ic != null && ic.isInterrupted())
                        .userMessage(extractMsgText(ic != null ? ic.getUserMessage() : null))
                        .build())
                .build());
    }

    private String readPlanContent(PlanModeContextState pm) {
        if (pm == null || !pm.isPlanActive() || pm.getCurrentPlanFile() == null) {
            return null;
        }
        try {
            Path workspace = Paths.get(runnerProperties.getWorkspace().getPath()).toAbsolutePath();
            Path planFile = workspace.resolve(pm.getCurrentPlanFile()).normalize();
            if (!planFile.startsWith(workspace)) {
                log.warn("getState: plan file escapes workspace: {}", planFile);
                return null;
            }
            if (!Files.exists(planFile)) {
                return null;
            }
            return Files.readString(planFile);
        } catch (Exception e) {
            log.warn("getState: failed to read plan file {}: {}", pm.getCurrentPlanFile(), e.getMessage());
            return null;
        }
    }

    private static String extractMsgText(Msg msg) {
        if (msg == null) {
            return null;
        }
        try {
            return msg.getTextContent();
        } catch (Exception e) {
            return null;
        }
    }
}
