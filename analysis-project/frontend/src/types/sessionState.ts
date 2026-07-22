/**
 * TypeScript mirror of the backend SessionStateResponse DTO.
 * Source: com.agentscopea2a.v2.dto.SessionStateResponse.
 *
 * Keep field names in sync with the Java class; the backend uses Jackson
 * default serialization (camelCase), so these are camelCase too.
 */

export interface PlanModeState {
  planActive: boolean;
  currentPlanFile: string | null;
  /** Markdown content of the plan file. null if plan not active or file unreadable. */
  planContent: string | null;
}

export interface TaskState {
  id: string;
  subject: string;
  description: string;
  /** PENDING / IN_PROGRESS / COMPLETED / FAILED - Task.State.name() (uppercase). */
  state: string;
  createdAt: string;
  owner: string;
  blocks: string[];
  blockedBy: string[];
}

export interface PermissionState {
  /** DEFAULT / ACCEPT_EDITS / EXPLORE / BYPASS / DONT_ASK - PermissionMode.name() (uppercase). */
  mode: string;
}

export interface InterruptState {
  flag: boolean;
  /** Text content of the supplement Msg stored at interrupt time. null if never interrupted. */
  userMessage: string | null;
}

export interface SessionStateResponse {
  userId: string;
  conversationId: string;
  /**
   * true even for fresh in-memory states (ReActAgent.getAgentState is load-or-create).
   * The frontend should NOT use this to detect "no active work" - instead check
   * planActive + tasks.length + interruptControl.flag.
   */
  exists: boolean;
  planMode: PlanModeState;
  tasks: TaskState[];
  permission: PermissionState;
  interruptControl: InterruptState;
}

/**
 * Subagent plan mode state inferred from SSE tool_call_start events.
 *
 * The main agent no longer has plan mode (it's a pure router). Plan mode now
 * lives on subagents (e.g. analyze_data). Since subagent AgentState is not
 * accessible via /v2/ai/session/state (sessionId is random, state not persisted),
 * the frontend infers plan mode from SSE events:
 *
 *   tool_call_start + toolCallName="plan_enter" + source="analyze_data" → planActive=true
 *   tool_call_start + toolCallName="plan_exit"  + source="analyze_data" → planActive=false
 *
 * planContent is always null for now — there's no endpoint to read the subagent's
 * PLAN.md file. Future: add GET /v2/ai/session/plan?file=... or persistSession:true.
 */
export interface SubagentPlanState {
  /** Subagent name, e.g. "analyze_data" */
  agentName: string;
  /** true after plan_enter, false after plan_exit */
  planActive: boolean;
  /** Always null for now (no way to read subagent's PLAN.md) */
  planContent: string | null;
}