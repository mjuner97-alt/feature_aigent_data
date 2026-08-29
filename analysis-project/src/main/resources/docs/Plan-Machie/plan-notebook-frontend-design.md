# PlanNotebook + 状态机前端设计方案（路径 A）

**状态**：设计稿，2026-07-20
**作者**：基于 agentscope-examples/agentscope-dataagent/frontend 改造
**目标**：在浏览器里可视化 PlanNotebook（plan + todo）与 AgentState 状态机效果

---

## 一、背景与目标

### 背景
- 框架（agentscope-core 2.0.0-RC5）已具备 `PlanModeContextState`（planActive + currentPlanFile）+ `TaskContextState`（tasks 列表）+ `InterruptControl`，但**无前端可视化**
- `agentscope-extensions-studio` 是 Java push 客户端（推到外部 Studio 云服务），**不是前端**
- `agentscope-examples/agents/agentscope-{builder,dataagent,paw}/frontend` 三个 React 前端**都没有** PlanMode/TodoList/状态机 UI（grep `planMode|todoWrite|TaskContext` 零命中）
- 项目后端 `/v2/ai/chat`（SSE 流）+ `/v2/ai/chat/interrupt`（中断+续跑）已就位，E2E 7/7 PASS（见 [[interrupt_resume_endpoint_verified]]）

### 目标
1. 实时展示当前 session 的 `PlanModeContextState`（planActive + plan 文件内容）
2. 实时展示 `TaskContextState.tasks`（todo 列表 + state 徽章 + blocks/blockedBy 依赖图）
3. 展示 `InterruptControl` 状态（flag + userMessage）
4. 展示 `PermissionContextState`（mode + pendingApprovals）
5. 触发 `POST /v2/ai/chat/interrupt`（中断 + 补充 + 自动续跑）
6. Task 状态机视图：PENDING → IN_PROGRESS → COMPLETED/FAILED 转换可视化

### 非目标
- ❌ 不做用户登录 / 权限管理
- ❌ 不做 admin 后台
- ❌ 不做 PlanNotebook 多计划并发（框架限制，见前文分析）
- ❌ 不引入 Studio 云服务
- ❌ 不做 WebSocket 双向通信（MVP 用 SSE + 轮询）

---

## 二、后端改动

### 2.1 新增 `V2SessionStateController` - 暴露当前 session 状态

路径：`src/main/java/com/agentscopea2a/v2/controller/V2SessionStateController.java`（新增）

```java
@RestController
@RequestMapping("/v2/ai/session")
@CrossOrigin(origins = "*", maxAge = 3600)
public class V2SessionStateController {

    private final ObjectProvider<HarnessA2aRunnerV2> runnerProvider;

    @GetMapping("/state")
    public ResponseEntity<SessionStateResponse> getState(
            @RequestParam String userId,
            @RequestParam String conversationId) {
        // 1. 从 runner.getAgent().getDelegate() 拿 ReActAgent
        // 2. agent.getStateStore().load(userId, sessionId) 拿 AgentState
        // 3. 从 AgentState 取 4 个子上下文：
        //    - PlanModeContextState (planActive + currentPlanFile)
        //    - TaskContextState (tasks list)
        //    - PermissionContextState (mode + pendingApprovals)
        //    - ToolContextState (active tools)
        // 4. 从 InterruptControl 拿 flag + userMessage
        // 5. 读 planFile 内容（如果在 workspace 里）
        // 6. 组装 SessionStateResponse 返回
    }
}
```

### 2.2 响应 DTO

路径：`src/main/java/com/agentscopea2a/v2/dto/SessionStateResponse.java`（新增）

```java
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class SessionStateResponse {
    private String userId;
    private String conversationId;
    private PlanModeState planMode;
    private List<TaskState> tasks;
    private PermissionState permission;
    private InterruptState interruptControl;

    @Data @Builder
    public static class PlanModeState {
        private boolean planActive;
        private String currentPlanFile;
        private String planContent;    // planFile 文件内容
    }

    @Data @Builder
    public static class TaskState {
        private String id;
        private String subject;
        private String description;
        private String state;         // PENDING / IN_PROGRESS / COMPLETED / FAILED
        private String createdAt;
        private String owner;
        private List<String> blocks;
        private List<String> blockedBy;
    }

    @Data @Builder
    public static class PermissionState {
        private String mode;          // WHEN_REQUIRED / ALWAYS / BYPASS / ...
        private int pendingApprovals;
    }

    @Data @Builder
    public static class InterruptState {
        private boolean flag;
        private String userMessage;   // 中断时存的 supplement
    }
}
```

### 2.3 不改 SSE 协议

**MVP 决策**：前端 2s 轮询 `GET /v2/ai/session/state`，不向 SSE 流加 `state_changed` 事件。

理由：
- 状态变化频率低（每次 LLM 工具调用才变）
- 不改 `V2ChatStreamServiceImpl.handleEvent` 避免回归风险
- 轮询给后端的压力可控（单 session 2s 一次，QPS 与活跃 session 数相等）
- 2s 延迟对人眼可接受

后续优化：若实测轮询延迟感明显，再加 `StateChangeHook` 推 `event:state_changed`。

### 2.4 后端验证
```bash
mvn clean package -Dmaven.test.skip=true
# 启动后冒烟
curl -s 'http://localhost:8081/v2/ai/session/state?user_id=test&conversationId=test-001'
# 预期：200 + JSON，planMode/tasks/permission/interruptControl 字段齐全
```

---

## 三、前端架构（基于 dataagent frontend 改造）

### 3.1 技术栈（沿用 dataagent）
| 项 | 版本 | 来源 |
|---|---|---|
| React | 18.3.1 | dataagent package.json |
| react-dom | 18.3.1 | 同上 |
| react-router-dom | 6.28.0 | 同上 |
| TypeScript | 5.7.2 | 同上 |
| Vite | 6.0.3 | 同上 |
| @vitejs/plugin-react | 4.3.4 | 同上 |

**不引入**：UI 框架（用 inline style 像 dataagent 一样）、状态管理库（用 React hooks）、CSS 框架。

### 3.2 目录结构

```
analysis-project/frontend/                      （新增，独立于后端 src/main/java）
├── package.json
├── vite.config.ts
├── tsconfig.json
├── index.html
└── src/
    ├── main.tsx                                # React 入口
    ├── App.tsx                                 # 路由 + 布局
    ├── api/
    │   ├── chat.ts                             # POST /v2/ai/chat SSE 流式
    │   ├── interrupt.ts                        # POST /v2/ai/chat/interrupt
    │   └── sessionState.ts                     # GET /v2/ai/session/state 轮询
    ├── components/
    │   ├── ChatPanel.tsx                       # 复用 dataagent，改 API 调用
    │   ├── ToolCallBlock.tsx                   # 直接复用 dataagent
    │   ├── PlanPanel.tsx                       # 新增：plan_active + plan 文件内容
    │   ├── TodoListPanel.tsx                   # 新增：tasks 列表 + state 徽章
    │   ├── TaskDependencyGraph.tsx             # 新增：blocks/blockedBy 依赖图
    │   ├── StateMachineView.tsx                # 新增：状态机总览
    │   ├── InterruptButton.tsx                 # 新增：中断+补充+续跑
    │   └── SessionSidebar.tsx                  # 简化版（不做 admin）
    ├── pages/
    │   └── ChatPage.tsx                        # 主页面：左 sidebar + 中 chat + 右 state
    └── types/
        └── sessionState.ts                     # SessionStateResponse TS 类型
```

### 3.3 vite.config.ts 改造

```typescript
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  build: {
    outDir: '../src/main/resources/static',   // 后续可打包进 jar
    emptyOutDir: true,
  },
  server: {
    port: 5173,
    proxy: {
      '/v2': {
        target: 'http://localhost:8081',       // 项目后端端口
        changeOrigin: true,
      },
    },
  },
});
```

注意：
- dataagent 默认 proxy 到 `:8080`，本项目后端在 `:8081`，需改
- proxy 路径 `/api` -> `/v2`（项目用 `/v2/ai/...` 命名空间）

---

## 四、关键组件设计

### 4.1 `PlanPanel.tsx`（新增）

**职责**：展示 `planMode.planActive` + plan 文件内容

```
┌────────────────────────────────────┐
│ 📋 Plan Mode    [● ACTIVE]         │  ← planActive=true 绿色徽章
│ File: plans/PLAN.md                │  ← currentPlanFile
├────────────────────────────────────┤
│ # 计划：分析 Q1 质量分              │  ← planContent (Markdown 渲染)
│                                    │
│ 1. 查询 Q1 各部门数据                │
│ 2. 计算质量分                        │
│ 3. 生成趋势图                        │
│ 4. 输出报告                         │
└────────────────────────────────────┘
```

实现：
- 用 `react-markdown`（轻量，~40KB）渲染 planContent
- planActive 切换时徽章闪烁（CSS animation）
- 每 2s 轮询 `GET /v2/ai/session/state`，对比 planContent hash 变化才 re-render

### 4.2 `TodoListPanel.tsx`（新增）

**职责**：展示 `tasks[]` + 状态徽章

```
┌────────────────────────────────────┐
│ ✅ Task List (3)                   │
├────────────────────────────────────┤
│ ✓ 查询 Q1 数据         [COMPLETED] │  ← 绿色
│ ▸ 计算质量分            [IN_PROG]  │  ← 蓝色脉动
│ ○ 生成趋势图            [PENDING]  │  ← 灰色
│ ✗ 上传报告             [FAILED]    │  ← 红色
└────────────────────────────────────┘
```

Task.state 颜色映射：
| State | 颜色 | 图标 |
|---|---|---|
| PENDING | `#94a3b8` 灰 | ○ |
| IN_PROGRESS | `#3b82f6` 蓝 + 脉动 | ▸ |
| COMPLETED | `#10b981` 绿 | ✓ |
| FAILED | `#ef4444` 红 | ✗ |

### 4.3 `TaskDependencyGraph.tsx`（新增）

**职责**：可视化 `blocks` / `blockedBy` 依赖图

实现方案：
- **MVP**：用 SVG 手画（不引图库），节点矩形 + 箭头
- **简化版**：横向时间线，按 createdAt 排序，blocks 关系用箭头连线
- 节点颜色同 TodoListPanel 的 state 颜色

```
[查询数据] ──blocks──> [计算质量分] ──blocks──> [生成趋势图]
   ✓                       ▸                       ○
```

后续优化：若依赖图复杂度上升，引 `reactflow`（~50KB）。

### 4.4 `StateMachineView.tsx`（新增）

**职责**：全局状态机总览，展示 4 个子上下文 + InterruptControl

```
┌─────────────────────────────────────────────┐
│ 🔄 Agent State Machine                       │
├─────────────────────────────────────────────┤
│ PlanMode:        [ACTIVE] ←→ [INACTIVE]      │
│ TaskList:        3 tasks (1 done, 1 prog)    │
│ Permission:      WHEN_REQUIRED (0 pending)   │
│ InterruptControl: flag=false                  │
└─────────────────────────────────────────────┘
```

实现：
- 4 个卡片横排，每个展示子状态 + 状态机箭头
- 状态变化时高亮闪烁
- `InterruptControl.flag=true` 时整张卡片红框

### 4.5 `InterruptButton.tsx`（新增）

**职责**：触发中断 + 补充 + 续跑

```
┌──────────────────────────────────────┐
│ ✋ 中断并补充                          │
│ ┌──────────────────────────────────┐ │
│ │ 改成按产品线分组，重点对比一部...  │ │  ← supplement 输入框
│ └──────────────────────────────────┘ │
│              [取消]  [中断+续跑]      │
└──────────────────────────────────────┘
```

流程：
1. 用户输入 supplement
2. 点击"中断+续跑"
3. POST `/v2/ai/chat/interrupt` body `{user_id, conversationId, supplement}`
4. 收到新 SSE 流（响应头 `X-Resume-Stream: true`）
5. 关闭原 SSE 连接，切换到新流
6. ChatPanel 清空当前回复，开始接收新流

### 4.6 `ChatPanel.tsx`（改造）

复用 dataagent frontend/src/components/ChatPanel.tsx 框架，改动：

1. **`api/chat.ts` 重写**：调 `/v2/ai/chat` 而非 `/api/agents/{agentId}/chat/stream`
2. **SSE 事件映射**：
   | dataagent 事件 | 项目事件 | 映射 |
   |---|---|---|
   | `token` | `text_block_delta` | `evt.data.lineResult` 作 chunk |
   | `tool_call` | (暂无) | TODO: 后续加 hook 暴露 |
   | `tool_result` | (暂无) | TODO |
   | `done` | `done` | 直接映射 |

3. **`sessionKey` -> `conversationId`**：DTO 字段名对齐
4. **`agentId` 移除**：项目 `/v2/ai/chat` 不需要 agentId（runner 单例）

### 4.7 `ToolCallBlock.tsx`（直接复用）

dataagent 的 ToolCallBlock.tsx 是纯展示组件（props: toolName, toolCallId, result），无项目耦合，直接复用。

---

## 五、API 适配层

### 5.1 `api/chat.ts` 重写

```typescript
import { getToken } from './auth';

export interface ChatRequest {
  input: string;                    // 用户消息
  conversationId: string;
  user_id: string;
}

export interface ChatEvent {
  type: 'token' | 'done' | 'error';
  chunk?: string;                   // token 时的增量文本
  fullText?: string;                 // 累积文本
  conversationId?: string;
}

export async function* streamChat(req: ChatRequest): AsyncGenerator<ChatEvent> {
  const res = await fetch('/v2/ai/chat', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...(getToken() ? { Authorization: `Bearer ${getToken()}` } : {}),
    },
    body: JSON.stringify(req),
  });

  if (!res.ok || !res.body) throw new Error(`Chat stream failed: ${res.status}`);

  const reader = res.body.getReader();
  const dec = new TextDecoder();
  let buf = '';
  while (true) {
    const { value, done } = await reader.read();
    if (done) break;
    buf += dec.decode(value, { stream: true });
    let idx;
    while ((idx = buf.indexOf('\n\n')) >= 0) {
      const evt = buf.slice(0, idx);
      buf = buf.slice(idx + 2);
      const lines = evt.split('\n');
      let eventName = 'message';
      let data = '';
      for (const line of lines) {
        if (line.startsWith('event:')) eventName = line.slice(6).trim();
        else if (line.startsWith('data:')) data += line.slice(5).trim();
      }
      if (eventName === 'text_block_delta') {
        try {
          const json = JSON.parse(data);
          yield { type: 'token', chunk: json.lineResult, fullText: json.resultAll };
        } catch { /* skip malformed */ }
      } else if (eventName === 'done') {
        try {
          const json = JSON.parse(data);
          yield { type: 'done', fullText: json.resultAll, conversationId: json.conversationId };
        } catch { /* skip */ }
      }
    }
  }
}
```

### 5.2 `api/interrupt.ts`（新增）

```typescript
export interface InterruptRequest {
  user_id: string;
  conversationId: string;
  supplement: string;
}

export async function interruptAndResume(
  req: InterruptRequest,
  onChunk: (chunk: string, fullText: string) => void,
  onDone: () => void,
  onError: (err: Error) => void,
): Promise<void> {
  const res = await fetch('/v2/ai/chat/interrupt', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(req),
  });

  if (!res.ok || !res.body) throw new Error(`Interrupt failed: ${res.status}`);

  // 同 streamChat 的 SSE 解析逻辑，但事件名可能是 text_block_delta / done
  // 收到 X-Resume-Stream: true header 时切换前端状态
  // ... 实现同 streamChat
}
```

### 5.3 `api/sessionState.ts`（新增）

```typescript
import { SessionStateResponse } from '../types/sessionState';

export async function getSessionState(
  userId: string,
  conversationId: string,
): Promise<SessionStateResponse> {
  const res = await fetch(
    `/v2/ai/session/state?user_id=${encodeURIComponent(userId)}`
    + `&conversationId=${encodeURIComponent(conversationId)}`,
  );
  if (!res.ok) throw new Error(`Get state failed: ${res.status}`);
  return res.json();
}

// Hook: 每 2s 轮询
export function useSessionState(userId: string, conversationId: string | null) {
  const [state, setState] = useState<SessionStateResponse | null>(null);
  useEffect(() => {
    if (!conversationId) return;
    let cancelled = false;
    const tick = async () => {
      try {
        const s = await getSessionState(userId, conversationId);
        if (!cancelled) setState(s);
      } catch (e) { /* ignore transient */ }
    };
    tick();
    const id = setInterval(tick, 2000);
    return () => { cancelled = true; clearInterval(id); };
  }, [userId, conversationId]);
  return state;
}
```

---

## 六、ChatPage 布局

```
┌─────────────┬────────────────────────────┬───────────────────────────┐
│ Sessions    │ Chat                       │ Plan + State              │
│ Sidebar     │                            │                           │
│             │ [user] 分析 Q1 质量分       │ ┌─PlanPanel─────────────┐│
│ • test-001  │ [asst] 正在分析...          │ │ [ACTIVE]              ││
│ • test-002  │   ▸ tool: query_data        │ │ plans/PLAN.md         ││
│ • test-003  │   ...                       │ │ # 计划...             ││
│             │ [InterruptButton]           │ └───────────────────────┘│
│             │ [输入框] [发送]              │ ┌─TodoListPanel────────┐│
│             │                            │ │ ✓ 查询  ▸ 计算       ││
│             │                            │ │ ○ 生成  ✗ 上传       ││
│             │                            │ └───────────────────────┘│
│             │                            │ ┌─StateMachineView─────┐│
│             │                            │ │ 4 个子状态卡片        ││
│             │                            │ └───────────────────────┘│
└─────────────┴────────────────────────────┴───────────────────────────┘
```

- 左 sidebar：会话列表（localStorage 保存 conversationId）
- 中 chat：复用 dataagent ChatPanel，改 API 调用
- 右 state：新增 3 个面板（PlanPanel + TodoListPanel + StateMachineView）

---

## 七、实施步骤

### 7.1 后端（约 1 天）

1. ✅ 新增 `SessionStateResponse.java` DTO
2. ✅ 新增 `V2SessionStateController.java`，从 `runner.getAgent().getDelegate().getStateStore().load()` 拿 AgentState
3. ✅ 解析 4 个子上下文 + InterruptControl
4. ✅ 读 planFile 内容（`workspace.resolve("plans").resolve(currentPlanFile)`）
5. ✅ `mvn clean package -Dmaven.test.skip=true` 编译
6. ✅ 冒烟测试 `GET /v2/ai/session/state`

### 7.2 前端（约 2-3 天）

1. ✅ 复制 `agentscope-dataagent/frontend/` 到 `analysis-project/frontend/`
2. ✅ 删除 admin 页面、auth、channels 等不需要的文件（瘦身到 ~20 个文件）
3. ✅ 改 `vite.config.ts`：proxy 端口 8081，路径 `/v2`
4. ✅ 改 `package.json`：项目名改 `analysis-project-frontend`
5. ✅ 重写 `api/chat.ts`（按上 §5.1）
6. ✅ 新增 `api/interrupt.ts` + `api/sessionState.ts`
7. ✅ 新增 `types/sessionState.ts`
8. ✅ 改造 `ChatPanel.tsx`：用新 `api/chat.ts`
9. ✅ 直接复用 `ToolCallBlock.tsx`
10. ✅ 新增 `PlanPanel.tsx`、`TodoListPanel.tsx`、`TaskDependencyGraph.tsx`、`StateMachineView.tsx`、`InterruptButton.tsx`
11. ✅ 改造 `ChatPage.tsx`：三栏布局
12. ✅ `npm install && npm run dev` 本地启动

### 7.3 端到端验证（约半天）

1. 启动后端：`mvn spring-boot:run`（端口 8081）
2. 启动前端：`cd frontend && npm run dev`（端口 5173）
3. 浏览器打开 `http://localhost:5173`
4. 输入测试 prompt："分析2026年Q1各部门质量分趋势，生成详细报告"
5. 观察：
   - ChatPanel 流式输出文本 ✅
   - PlanPanel 出现 planActive=true + plan 内容 ✅
   - TodoListPanel 出现 Task 列表 + 状态切换 ✅
   - TaskDependencyGraph 出现依赖箭头 ✅
   - StateMachineView 实时更新 ✅
6. 中途点击 InterruptButton，输入"改成按产品线分组"
7. 观察：
   - 原 SSE 流以 recovery msg + done 结尾 ✅
   - 新 SSE 流切换到 ChatPanel ✅
   - 新流基于 supplement 续跑 ✅

---

## 八、验证清单

### 8.1 后端
- [ ] `GET /v2/ai/session/state` 返回 200 + 完整 JSON
- [ ] 无 in-flight 调用时返回最近保存的 state（从 MySQL `agentscope_sessions.state_data`）
- [ ] 有 in-flight 调用时返回实时 state（从 AgentState 内存）
- [ ] conversationId 不存在时返回 404 或空 state

### 8.2 前端
- [ ] `npm run dev` 启动无错误
- [ ] 浏览器打开看到三栏布局
- [ ] 发送消息后 ChatPanel 流式输出
- [ ] PlanPanel 在 plan_enter 工具调用后变 ACTIVE
- [ ] TodoListPanel 在 todo_write 后更新
- [ ] InterruptButton 弹出输入框，提交后切换 SSE 流
- [ ] SessionSidebar 切换 conversationId 后状态面板更新

### 8.3 端到端
- [ ] 7.3 的 7 个观察点全部通过

---

## 九、风险与限制

| 风险 / 限制 | 影响 | 应对 |
|---|---|---|
| `AgentState` 内存读取 vs MySQL 读取不一致 | in-flight 时拿内存，无 in-flight 时拿 MySQL，两者可能短暂不一致 | 文档说明：前端展示的是"最近一次落盘 + 当前内存"的合并视图 |
| planFile 读取失败（文件未创建） | PlanPanel 显示空 | 优雅降级：planActive=false 时显示"未进入 Plan Mode" |
| 轮询 2s 给后端压力 | 单 session QPS=0.5，可接受；高并发需优化 | 后续可加 ETag/If-None-Match 或 SSE 推送 |
| dataagent frontend 复制后依赖冲突 | Node 版本、Vite 版本不匹配 | 文档要求 Node 18+，README 写明 |
| CORS | 跨域请求被拒 | 后端已 `@CrossOrigin(origins="*")`，dev 模式 vite proxy 转发 |
| tool_call / tool_result SSE 事件暂未暴露 | ToolCallBlock 无数据 | 后续加 `ToolCallTrackingHook` 推 `event:tool_call` |
| 前端构建产物是否打包进 jar | 生产部署方式待定 | MVP 不打包，dev 模式独立 vite server；生产用 nginx 反代或打包进 static/ |
| InterruptControl.userMessage 是英文 recovery msg | UX 突兀 | 接受现状（[[interrupt_resume_endpoint_verified]] 已记录）|

---

## 十、不做的事（明确跳过）

- ❌ 不引入 Studio 云服务（路径 B 已否决）
- ❌ 不做 WebSocket 双向通信（SSE + 轮询足够）
- ❌ 不做用户登录 / 权限管理 / 多租户 UI（后端已隔离，前端无需）
- ❌ 不复用 dataagent 的 admin / channels / marketplaces / skills 等 60+ 文件
- ❌ 不做 PlanNotebook 多计划并发 UI（框架不支持，单 session 单 plan）
- ❌ 不做子 agent 内部状态可视化（[[subagent_hook_chain_migration]] 限制）
- ❌ 不引 UI 框架（保持 inline style 风格，与 dataagent 一致）
- ❌ 不做状态管理库（Redux/Zustand 都不要，React hooks 足够）
- ❌ 不做 i18n（MVP 中文优先，后续视需求）

---

## 十一、关联文档与记忆

- **关联记忆**：
  - [[interrupt_resume_endpoint_verified]] - 后端 interrupt 端点已 E2E 验证 7/7
  - [[plan_state_cross_restart_verified]] - plan_mode_context 持久化已验证
  - [[permission_mode_endpoint]] - PermissionMode 切换端点（可前端调用）
  - [[subagent_hook_chain_migration]] - 子 agent 限制
- **关联源码**：
  - 后端 SSE 实现：`src/main/java/com/agentscopea2a/v2/service/V2ChatStreamServiceImpl.java`
  - 后端 interrupt 端点：`src/main/java/com/agentscopea2a/v2/controller/V2ChatInterruptController.java`
  - 后端 runner 配置：`src/main/java/com/agentscopea2a/v2/runner/HarnessA2aRunnerV2.java:146-149`（enablePlanMode + enableTaskList）
- **关联框架源码**：
  - `PlanModeContextState`：`agentscope-core/src/main/java/io/agentscope/core/state/PlanModeContextState.java`
  - `TaskContextState` / `Task`：同上目录
  - `TodoTools`：`agentscope-core/src/main/java/io/agentscope/core/tool/builtin/TodoTools.java`
- **关联前端脚手架**：
  - `agentscope-examples/agents/agentscope-dataagent/frontend/` - 复制改造起点
