# 待优化问题清单

记录暂不修复、待实际使用场景验证后再决定是否优化的问题。

---

## 1. PLAN 模式下文件工具未隔离

**发现时间**: 2026-07-24
**触发场景**: Plan-Machine 前端测试 `分析2026年Q1各部门质量分趋势，生成详细报告`

### 现象

`analyze_data` 子 agent 在 PLAN 模式（`plan_enter` 到 `plan_exit` 之间）调用了：

- `read_file` 读 `.skills-cache/filesystem-harness-a2a_skills/tool_index/SKILL.md` → file_not_found
- `read_file` 读 `.skills-cache/filesystem-harness-a2a_skills/data_primitives/SKILL.md` → file_not_found
- `glob_files` → no matching files
- `list_files` 列 `.skills-cache/filesystem-harness-a2a_skills/` 目录
- `list_files` 列 `.skills-cache/filesystem-harness-a2a_skills/data_primitives/`
- `read_file` 读 `.skills-cache/.../q1_data.csv`（成功，699 字节 skill 示例数据）

### 当前行为

- `ArtifactAccessMiddleware` 只对 `/workspace/...` 工件树内的路径强制按 `userId/sessionId` 隔离
- `.skills-cache/` 目录是**全局共享**的（skill 定义是跨用户的，设计如此）
- PLAN 模式当前不限制 `read_file`/`glob_files`/`list_files` 工具的调用

### 用户的两个顾虑

1. **隔离**：这些工具没有按 `userId/session` 隔离 — 已确认 `.skills-cache/` 内是 skill 定义而非用户数据，工件树内已隔离，**此条非问题**
2. **PLAN 模式调用**：plan 阶段本该只规划、不读文件 — 设计层面问题，待决策

### 待决策点

PLAN 模式下是否屏蔽 `read_file`/`glob_files`/`list_files`？选项：

| 方案 | 优点 | 缺点 |
|---|---|---|
| A. 不限制（现状） | agent 能探查数据样本帮助规划 | 读文件浪费时间，与 PLAN 语义冲突 |
| B. PLAN 模式屏蔽这些工具 | 符合 PLAN 语义，加快规划 | LLM 可能因看不到数据样本而规划不准 |
| C. PLAN 模式只允许 `list_files`，禁止 `read_file` | 折中 | 实现复杂 |

### 决策

**暂不修改**。等真实使用场景验证后再决定。

### 相关代码

- `src/main/java/com/agentscopea2a/v2/middleware/ArtifactAccessMiddleware.java` — 工件树路径隔离
- `src/main/java/com/agentscopea2a/v2/subagent/SubagentRegistrar.java` — 子 agent 工具注册（plan mode 启用位置）
- memory: `[[plan-mode-migrated-to-subagent]]` — PLAN mode 已迁移到 analyze_data 子 agent
