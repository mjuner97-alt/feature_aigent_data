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