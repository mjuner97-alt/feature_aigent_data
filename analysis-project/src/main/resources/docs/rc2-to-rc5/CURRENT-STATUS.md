# 升级完成度快照（2026/07/15）

> 分支：`upgrade/2.0.0-RC5-dual-track`
> 升级路径：1.1.0-RC2 二开 -> 2.0.0-RC5（双轨并行，v2 builder 为新骨架）
> 本文档为当前状态快照。权威主计划见 [UPGRADE_PLAN.md](UPGRADE_PLAN.md)；E2E 运行时验证详情见 [stage1-8-e2e-test-report.md](stage1-8-e2e-test-report.md)

---

## 一、总体结论

| 层次 | 状态 |
|---|---|
| 代码迁移（Stages 0-8） | ✅ 完成 — 79 个 v2 源文件，`io.agentscope` 下 shadow override 全部清空，`mvn clean compile` BUILD SUCCESS |
| 运行时验证（E2E） | ⚠️ 主链路打通，Stage 4 分布式模式完全未测 |
| Stage 9 | ⬜ 未启动 |

---

## 二、各阶段状态

| Stage | 代码 | 运行时验证 | 阻塞 Stage 9 |
|---|---|---|---|
| 1 v2 入口 + PlanMode + TodoTools | ✅ | ✅ TodoTools / PlanMode 实测通过 | 否 |
| 2 stateStore + 多数据源 | ✅ | ✅ 多轮对话 + MySQL 持久化通过 | 否 |
| 3 SkillCurator + Toolkit + Skill 合成 | ✅ | ✅ save_skill 闭环通过（skill_index 落盘）| 否 |
| 4 DistributedStore + RemoteFilesystemSpec | ✅ 编译 | ❌ **完全未测**（`distributed.enabled=false`）| **若切 distributed 部署则阻塞** |
| 5 Artifact v2 + ResponseCache + 多数据源 | ✅ | ✅ SshArtifactIo wired + ResponseCache HIT 通过 | 否 |
| 6 AgentEvent + SSE | ✅ | ✅ SSE 流式 + ToolCallTrackingHook 通过；异常路径 ⚠️ | 否 |
| 7 Episodic + Trace | ✅ | ⚠️ bean wired，cron（21:09）未触发写入 | 否（cron 设计）|
| 8 Memory Mirror + Digestion | ✅ | ⚠️ bean wired，cron 未触发 | 否（cron 设计）|

---

## 三、已实测通过

1. **应用启动**：所有 v2 bean 装配，4 subagent specs 加载，11 tools 注册，无 `IllegalStateException`
2. **C1 简单查询**：`supervisor -> agent_spawn(query_data) -> quality_query_by_department_quarter -> 返回数据`
3. **C2 含计算分析**：并行 `query_data` x2（Q1+Q2）+ 8 次 quality_query SUCCESS + `code_interpreter` + `python_exec` 闭环
4. **C3 多轮对话**：跨轮 TaskContextState 加载、上下文回忆
5. **Session 状态 MySQL 持久化**：session JSON 含完整对话历史 + thinking 字段 + 4 个 sub-context
6. **Sandbox**：SESSION scope 正常创建/复用，无 `__global__` 拒绝，无 "No active sandbox" 级联
7. **ResponseCache HIT**：同问题复测 7s 秒回（首次 30s+），`response_cache` 表命中
8. **SshArtifactIo**：bucket 清理任务正常执行
9. **TodoTools**：`todo_write` x2 + `task_list` x1 全 SUCCESS
10. **Skill 合成闭环**：`save_skill` -> `skills-user/*/SKILL.md` 落盘 + `skill_index` 新增行
11. **PlanMode**：`plan_enter` / `plan_write` / `plan_exit` 全 SUCCESS

E2E 中发现并修复的 9 个 bug（#1-#4 装配层 + #5-#9 多 agent 闭环），详见 e2e 报告 §3 / §10 / §11。

---

## 四、未完成项

### 4.1 Stage 9（未启动）

- **v1 入口整体删除**：`HarnessA2aRunner` + `com/agentscopea2a/{agent,harness,service}/**`（当前 Maven excludes 排除编译，代码仍留源码树）
- **数据迁移脚本**：session / 记忆 / skill / schema 的 v1->v2 格式迁移（一次性脚本，未写）
- **真正的 v1 fallback**：`V2ChatController` 路由到 v1 桶时返回 HTTP 503，非 redirect（v1 controller 仍被 Maven exclude）
- **灰度切换**：`V2SessionRouter` 存在但默认 100% v2，未做 10% -> 30% -> 50% -> 100% 切换
- **回滚演练**（RTO < 5min）+ 内网部署

### 4.2 运行时验证缺口

- **P0（阻塞 distributed 部署）**：Stage 4 `distributed.enabled=true` 启动、双副本收敛（A 写 B 读）、`WorkspaceIndex.open` / `CompositeFilesystem` / `OverlayFilesystem` 全未测；当前 `distributed.enabled=false`，所有 Stage 4 验证仅编译期 + bean 定义
- **cron 触发型**：Stage 7 episodic 写入（cron `0 9 21 * * *`）、Stage 8 `agent_memory` / `MemoryHydrator`、Stage 5 `ArtifactSweeper` GC（cron `0 17 * * * *`）— bean wired 但 cron 时刻未到
- **边缘场景**：异常路径 SSE 错误事件（缺 `@Valid` / `@NotBlank` 校验，畸形请求导致服务端异常 + 客户端无清晰错误，非阻塞）

### 4.3 工作区有 175 个未提交改动

`git status` 显示 Stage 5 补齐（ArtifactSweeper / SshHealthCheck）、Stage 8 digestion 全量迁移、E2E 修复的 4 bug + 3 配置错误、`SubagentRegistrar` 新建等**全部未 commit**。最近 3 个 commit 停在 "Stage 7"（`328df95` / `e06e86f` / `f597aaf`）。E2E 报告 §9.3 明确标注"未 commit（待用户决定提交时机）"。

---

## 五、Stage 9 待办清单

### 5.1 必做（阻塞内网部署）

- [ ] 提交 175 个未提交改动（Stage 5/8 迁移 + E2E 修复 + SubagentRegistrar）
- [ ] v1 入口整体删除（`HarnessA2aRunner` + Maven-excluded 包内代码 + v1 测试源码）
- [ ] 数据迁移脚本编写 + 执行（session / episodic / skill schema 的 v1->v2 格式）
- [ ] `V2SessionRouter` 真正的 v1 fallback（redirect 替换 503）
- [ ] 灰度切换（10% -> 30% -> 50% -> 100%）
- [ ] 回滚演练（RTO < 5min）

### 5.2 视部署形态

- [ ] **单副本部署**：可不补 Stage 4 distributed 验证，直接推进 Stage 9
- [ ] **分布式部署**：必须先补 Stage 4 P0 验证（distributed.enabled=true 启动 + 双副本收敛 + WorkspaceIndex/CompositeFilesystem/OverlayFilesystem）

### 5.3 建议补测（非阻塞，多数已通过）

- [x] Stage 5 ResponseCache HIT — 已通过
- [x] Stage 3 Skill 合成闭环 — 已通过
- [x] Stage 1 PlanMode — 已通过
- [ ] cron 触发型写入（Stage 5/7/8）— 等时刻到自然验证
- [ ] 异常路径 SSE 错误事件 — 加 `@Valid` / `@NotBlank` 校验

---

## 六、判定与建议

按 e2e 报告 §12.4 的建议路径：**Stage 9 单副本部署可不补 Stage 4 直接推进**；distributed 验证作为 Stage 9 后期独立验收项。当前最现实的下一步是：

1. **先提交 175 个未提交改动**（Stage 5/8 迁移 + E2E 修复已编译验证通过）
2. **决定部署形态**：单副本直接走 Stage 9；分布式先补 Stage 4 P0
3. **执行 Stage 9**：v1 删除 + 数据迁移 + 灰度切换 + 回滚演练 + 内网部署

---

## 七、文档索引

| 文档 | 用途 |
|---|---|
| [UPGRADE_PLAN.md](UPGRADE_PLAN.md) | 权威主计划 + 各阶段实施摘要（〇·A 至 〇·H）|
| [CURRENT-STATUS.md](CURRENT-STATUS.md)（本文档）| 当前完成度快照（2026/07/15）|
| [stage1-8-e2e-test-report.md](stage1-8-e2e-test-report.md) | E2E 运行时验证详情 + Bug #1-#9 记录 |
| [business-modules-migration-checklist.md](business-modules-migration-checklist.md) | 业务模块逐文件迁移跟踪表 |
| [shadow-override-customization-points.md](shadow-override-customization-points.md) | 已删 shadow override 的定制点记录（后续按需通过 v2 middleware/builder 重新实现）|
| [adr-7.2-botloopguard-not-applicable.md](adr-7.2-botloopguard-not-applicable.md) | BotLoopGuard / IdempotencyStore 不迁移的架构决策 |
