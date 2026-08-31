# 子智能体加载失败修复 — 变更汇总

> 现象:运行期 `agent_spawn(agent_id="analyze_data", ...)` 报 `Unknown agent_id: analyze_data`,LLM 只能看到内置 `general-purpose`。
> 根因:`workspace/` 源码布局曾被嵌套到 `workspace/harness-a2a/...`,与 `WorkspaceMaterializer` 剥前缀逻辑 + `SupervisorService.init()` 读取路径不再对齐 — 物化后实际落地在 `<workspacePath>/harness-a2a/agent-subagents/*`,而 supervisor 启动时去找的是 `<workspacePath>/agent-subagents/*`,目录不存在,`workspaceSubagents` 列表保持空,后续 `parent.subagentFactory(...)` 一次都没注册。
> 同次目录改名(`subagents/` → `agent-subagents/`)还留了两处遗留:`ALWAYS_OVERWRITE_PREFIXES` 与 `workspaceProjectionRoots` 都没跟上,导致 markdown 修改后启动不再覆盖磁盘版本、沙箱投影也不包含 `agent-subagents/`。本次一并修掉。

## 变更文件清单

| # | 类型 | 路径 | 改动摘要 | 影响面 |
|---|---|---|---|---|
| 1 | 🚚 移动 | `src/main/resources/workspace/harness-a2a/AGENTS.md` → `workspace/AGENTS.md` | git mv 去掉嵌套层 | classpath 资源布局 |
| 2 | 🚚 移动 | `workspace/harness-a2a/agent-subagents/*.md`(4 个) → `workspace/agent-subagents/*.md` | git mv 去掉嵌套层 | 4 个子智能体 spec 终于能被 `SupervisorService` 找到 |
| 3 | 🚚 移动 | `workspace/harness-a2a/knowledge/*` → `workspace/knowledge/*` | git mv 去掉嵌套层 | KNOWLEDGE.md / .gitkeep |
| 4 | 🚚 移动 | `workspace/harness-a2a/skills/*` → `workspace/skills/*` | git mv 去掉嵌套层 | 预置 `data_primitives` / `tool_index` 两个 SKILL.md |
| 5 | 🗑️ 删除 | 空目录 `workspace/harness-a2a/` | `rmdir` | — |
| 6 | ✏️ 改造 | `harness/config/WorkspaceMaterializer.java` | `ALWAYS_OVERWRITE_PREFIXES`:`"subagents/"` → `"agent-subagents/"`;Javadoc 同步 | 每次启动重新覆盖 agent-subagents/*.md(否则 seeded-once 语义会让 markdown 热更失效) |
| 7 | ✏️ 改造 | `harness/config/FilesystemConfig.java:196` | `workspaceProjectionRoots(List.of("AGENTS.md", "knowledge", "subagents"))` → `... "agent-subagents")` | 沙箱投影补齐;容器内 subagent 现在能看到自己的 spec 文件 |
| 8 | 🆕 新增 | `docs/subagent-load-fix-changes.md` | 本文 | 文档 |

## 路径对齐复盘(为什么之前会错位)

`WorkspaceMaterializer.ensureMaterialized(target)` 把 `classpath:workspace/**/*` 物化到磁盘的 `target` 目录,**剥前缀只剥到 `/workspace/` 截止**:

```java
int idx = uri.indexOf("/workspace/");
String relative = uri.substring(idx + "/workspace/".length());
Path dest = target.resolve(relative);
```

`target` 由 `harness.a2a.workspace.path` 配,默认 `.agentscope/workspace/harness-a2a`。三方约定原本是这样:

| 角色 | 期望 |
|---|---|
| classpath 源码 | `src/main/resources/workspace/<相对路径>` |
| materializer 输出 | `<target>/<相对路径>` |
| supervisor 读 | `workspace.resolve("agent-subagents")` = `<target>/agent-subagents/` |

之前误把源码再嵌一层 `harness-a2a/` 后(`workspace/harness-a2a/agent-subagents/*.md`),剥前缀仍然只剥到 `/workspace/`,relative 变成 `harness-a2a/agent-subagents/*.md`,落到 `<target>/harness-a2a/agent-subagents/*.md` —— 双层 `harness-a2a/`。supervisor 完全找不到。

本 PR 把源码恢复到扁平布局,让三方重新对齐。

## 行为差异(用户视角)

| 场景 | 修复前 | 修复后 |
|---|---|---|
| 启动日志 | `WARN  Subagents directory not found at .agentscope/workspace/harness-a2a/agent-subagents — no Markdown subagents will be loaded` | `INFO  Loaded 4 Markdown subagent spec(s) from .../agent-subagents: [query_quality_data, analyze_data, generate_skill, code_interpreter]` |
| 主智能体派单 `analyze_data` | `Unknown agent_id: analyze_data`,LLM 退化到只用 `general-purpose` 自救 | 正常派单,链路 `supervisor → analyze_data → tool_router(...) → code_interpreter` 全开 |
| 修改 `agent-subagents/*.md` 后重启 | 不会覆盖磁盘版本(seeded once 命中,因为 `ALWAYS_OVERWRITE_PREFIXES` 还是旧的 `"subagents/"`) | 每次启动从 jar 重新覆盖,与代码同步 |
| 沙箱容器内 subagent 读 spec | `agent-subagents/` 不在投影列表里,容器看不到 | 容器投影到 `agent-subagents/`,subagent 自取其文 |

## 配置项

无新增。`harness.a2a.workspace.path` 保持默认 `.agentscope/workspace/harness-a2a`。

## 升级注意 — 残留旧目录需手工清理

老部署的磁盘上很可能存在一个嵌套的 `<workspacePath>/harness-a2a/` 子目录(就是错位时物化进去的那份)。修复后这条路径不再使用,但 materializer 不会清理已存在的"非当前布局"文件,留着也无害,但磁盘多占一份。可选清理:

```bash
# 本地 dev profile
rm -rf .agentscope/workspace/harness-a2a/harness-a2a

# 远端 sandbox profile(参考 application-sandbox-linux.properties:40)
ssh root@<docker-host> 'rm -rf /opt/agentscope-workspace/harness-a2a/harness-a2a'
```

> `memory/` / `skills/`(用户保存的)/ `sessions/` / `artifacts/` 在两处布局下都是 seeded-once / 用户产物,如果旧布局下已写过内容,清理前请把这些子目录从 `harness-a2a/harness-a2a/` 合并回 `harness-a2a/`,避免丢失。

## 回滚

```bash
git revert <this-commit>
# 残留 .agentscope/workspace/harness-a2a/agent-subagents 等扁平目录可保留,不影响旧布局
```

回滚后会重新出现 `Unknown agent_id` 报错;只有在确认有另一条路径修复时才回滚。

## 验证 checklist

```bash
# 1. 编译
mvn -B -DskipTests clean compile

# 2. 启动后看日志
java -jar target/analysis-project-0.0.1-SNAPSHOT.jar --spring.profiles.active=dev | \
  grep -E 'Loaded .* Markdown subagent|Subagents directory not found'
# 期望: Loaded 4 Markdown subagent spec(s) from .../agent-subagents: [...]

# 3. 派单冒烟
curl -sS http://localhost:8081/chatA2A -H 'Content-Type: application/json' \
  -d '{"message":"对比2026年1季度和2026年2季度各部门的环比变化率","session_id":"smoke-1"}' | jq .reply
# 期望: 不出现 "Unknown agent_id",reply 含 code_interpreter / data_compare_ratio 走通后的数字

# 4. 物化目录
ls .agentscope/workspace/harness-a2a/
# 期望: AGENTS.md  agent-subagents  knowledge  skills  (不再有嵌套 harness-a2a/)
```

## 关联文档同步(本 PR 内已完成)

- `src/main/resources/docs/README.md` 中 5 处 `workspace/subagents/` 引用统一替换为 `workspace/agent-subagents/`(行 21 / 128 / 160 / 198 / 479)。
- 删除了 `disableWorkspaceSubagentAutoDiscovery()` 的过时描述,改成 `WorkspaceMaterializer.ALWAYS_OVERWRITE_PREFIXES` 的实际机制描述(README 行 198)。
- README 中残留的 `/opt/agentscope-workspace/harness-a2a/...` 是远端宿主部署路径,与本次资源嵌套 bug 无关,**保持原样**。
