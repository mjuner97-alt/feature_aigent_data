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
package com.agentscopea2a.v2.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Response body for {@code GET /v2/ai/session/state} - the PlanNotebook + state
 * machine snapshot exposed for the frontend's Plan/Todo/State panels.
 *
 * <p>Pulls four AgentState sub-contexts (PlanMode / Task / Permission / Tool)
 * plus {@link io.agentscope.core.interruption.InterruptControl} into a flat
 * JSON structure consumable by a single React {@code useEffect} poll.
 *
 * <p>{@code exists=false} means no in-memory or persisted AgentState was found
 * for the session; the frontend should show "no active session" placeholders.
 *
 * <p>See {@code docs/Plan-Machie/plan-notebook-frontend-design.md} for the
 * full design including the 2s polling strategy and the panel mapping.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionStateResponse {

    private String userId;
    private String conversationId;
    private boolean exists;

    private PlanModeState planMode;
    private List<TaskState> tasks;
    private PermissionState permission;
    private InterruptState interruptControl;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PlanModeState {
        private boolean planActive;
        private String currentPlanFile;
        /** Contents of the plan file (Markdown). null if plan not active or file unreadable. */
        private String planContent;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TaskState {
        private String id;
        private String subject;
        private String description;
        /** PENDING / IN_PROGRESS / COMPLETED / FAILED (Task.State.name()). */
        private String state;
        private String createdAt;
        private String owner;
        private List<String> blocks;
        private List<String> blockedBy;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PermissionState {
        /** default / accept_edits / explore / bypass / dont_ask (PermissionMode.name()). */
        private String mode;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InterruptState {
        private boolean flag;
        /** Text content of the supplement Msg stored at interrupt time. null if never interrupted. */
        private String userMessage;
    }
}
