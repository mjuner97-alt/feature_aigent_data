# script_exec Windows 本地执行方案 (ssh+docker exec 路径)

> 创建日期：2026/08/06
> 状态：已实施
> 关联：`docs/prompt/deployment-guide.md` (容器/workspace 部署), memory `python_exec_local_mode.md`

## 背景

dev profile 跑在 Windows JVM 上,`sandbox.enabled=true` + `remote-docker-enabled=true` + `shared-container-name=agentscope-shared-demo`。`python_exec` 通过 `ssh docker-host docker exec -i agentscope-shared-demo python3 -` 走容器内 python3,正常工作。但 `script_exec` 失败:

```
[script_exec] scriptId=q2_1_metrics_by_dept_version  exit=9009  elapsed=233ms
stdout: (空)
```

LLM 被迫降级走 `sql_registry_exec + python_exec` 两步,虽然结果正确,但每次多一次 tool call + 一次 SQL 取数 + 一次 python 聚合,链路长。

## 根因 (3 处 Windows 不兼容)

| # | 位置 | 现状 | 问题 |
|---|---|---|---|
| 1 | `ScriptExecTool.java:267` | `command.add("python3")` 硬编码 | Windows JVM 上不存在 `python3` (本地 `python` 3.13.5 在),CreateProcess 找不到命令直接 exit=9009 |
| 2 | `ScriptExecTool.java:113` | `OPENGAUSS_JAR_PATH = "/root/.m2/..."` 硬编码 | Linux 容器内路径,Windows 宿主机上不存在 (宿主机 `~/.m2/...` 有) |
| 3 | `ScriptExecTool.java:233` | `env.put("LC_ALL", "C.UTF-8")` 无条件注入 | Windows 无 `C.UTF-8` locale |

**核心问题:** `ScriptExecTool` 没有 transport 抽象,不论 sandbox 配置如何都直接 fork `python3 <abspath>`。`PythonExecTool` 已有 `buildCommand()` 按 sandbox config 分 ssh+docker / local-docker / host-python 三模式,直接对齐即可。

## 方案 (ssh+docker exec 跑容器内 python3)

dev profile 配置不变。`ScriptExecTool` 加 transport 抽象,与 `PythonExecTool.buildCommand()` 第 182-208 行完全对齐:

```
JVM (Windows host)
  ├── 查 script_registry 取 script_path + params_schema + datasources
  ├── 校验 params (白名单) + script_path (正则 + 文件存在)
  ├── buildCommand(): 按 sandbox config 决定命令:
  │     sandbox.enabled + shared-container + remote-docker
  │       -> ssh docker-host docker exec -i agentscope-shared-demo python3 /workspace/harness-a2a/scripts/<script>
  │     sandbox.enabled + shared-container (无 ssh)
  │       -> docker exec -i <container> python3 <container-path>
  │     local-python / 无 sandbox
  │       -> python3 <host-path>  (Windows 上 exit=9009, fallback)
  ├── 注入 env: GAUSS_JDBC_URL/USER/PASS/JAR (容器内 /root/.m2/... 路径正确)
  └── ProcessBuilder.start() + stdin 写 JSON params
        ssh 透传 stdin -> docker exec -i stdin -> 容器内 python3.stdin
```

**路径双轨:**
- 宿主机 path (`workspacePath/scripts/<script>`): 用于 `Files.isRegularFile` 检查 + 安全 normalize 检查
- 容器内 path (`containerWorkspacePath/scripts/<script>`,默认 `/workspace/harness-a2a/scripts/<script>`): 作为 `python3` argv 传给容器内进程

bind-mount `/java/analysis-workspace:/workspace/harness-a2a` 已存在 (memory `workspace_persistence_bind_mount.md`),宿主机和容器内的 scripts/ 目录通过 bind-mount 同步。

## 改动文件

| 文件 | 改动 |
|---|---|
| `src/main/java/com/agentscopea2a/v2/tools/ScriptExecTool.java` | 构造函数加 `SandboxPropertiesV2` + `containerWorkspacePath` 参数;新增 `buildCommand(hostPath, containerPath)` + `describeTransport()` + `stripExeExt()` + `isBlank()`;LC_ALL 改条件注入;`scriptExec` 主体加 containerScriptPath 解析 |
| `src/main/java/com/agentscopea2a/v2/config/V2ToolConfig.java` | `scriptExecTool` bean 新增 `SandboxPropertiesV2` + `@Value("${harness.a2a.sandbox.workspace-container-path:/workspace/harness-a2a}")` 两个参数,传给新构造函数 |

## 不改动的部分

- `OPENGAUSS_JAR_PATH` 硬编码 `/root/.m2/...` - ssh+docker 模式下 python3 跑在容器内,路径正确
- `application-dev.properties` - dev profile 配置不变
- `_gauss_jdbc.py` / `q2_1_metrics_by_dept_version.py` - 脚本本身不动
- `ScriptListTool.java` - 不调 python,无此问题
- 新增配置项 `harness.a2a.sandbox.workspace-container-path` 默认值 `/workspace/harness-a2a`,无需在 properties 显式设

## 脚本部署 (前置条件)

ssh+docker 模式下 python3 在容器内,需要 `/workspace/harness-a2a/scripts/` 下有 `.py` 文件。bind-mount 已存在,只需在 docker-host 上:

```bash
ssh docker-host "mkdir -p /java/analysis-workspace/scripts"
scp src/main/resources/workspace/scripts/_gauss_jdbc.py docker-host:/java/analysis-workspace/scripts/
scp src/main/resources/workspace/scripts/q2_1_metrics_by_dept_version.py docker-host:/java/analysis-workspace/scripts/
```

宿主机 Windows 上 `.agentscope/workspace/harness-a2a/scripts/` 也要有这两个文件 (WorkspaceMaterializer 启动时 seed, 若没 seed 手动 cp)。

## 验证

1. `mvn -q compile` BUILD SUCCESS
2. 后端重启,log 出现 `ScriptExecTool: wired ... containerWorkspacePath=/workspace/harness-a2a`
3. 前端问"杭州开发二部 7月版 Q2-1 达标率多少?"
4. 期望 trace:
   - `script_exec` SUCCESS exit=0
   - stdout 含 `total=80, scored=80, passed=80` + markdown 表
   - 不再出现 `sql_registry_exec + python_exec` 降级路径

## 风险与限制

| 风险 | 缓解 |
|---|---|
| 宿主机 `.agentscope/workspace/harness-a2a/scripts/` 缺文件 | WorkspaceMaterializer 启动 seed;若没 seed 手动 cp |
| 容器内 `/workspace/harness-a2a/scripts/` 缺文件 | 上文 scp 部署 |
| ssh 命令 Windows CreateProcess 8KB 上限 | 命令本身 < 200 字节,不触发 |
| params JSON 跨 ssh stdin 传输 | `docker exec -i` stdin 直通容器内 python3.stdin,与 python_exec 同链路,已验证 |
| local-python 模式 (sandbox.enabled=false + local-python-enabled=true) 仍会 exit=9009 | 本期不解决,留作后续:GAUSS_JAR 改可配 + python 命令改可配 + 装 pandas |

## 后续 (local-python 模式 Windows 适配)

若未来要在 Windows 上完全不依赖远端 docker (切 `sandbox.enabled=false` + `local-python-enabled=true`),还需:

1. `OPENGAUSS_JAR_PATH` 改成 `@Value("${harness.a2a.opengauss-jar-path:/root/.m2/...}")`,Windows 配 `C:/Users/<user>/.m2/...`
2. `command.add("python3")` fallback 改成 `@Value("${harness.a2a.python-binary:python3}")`,Windows 配 `python` 或 `py`
3. `pip install pandas jpype1` (jpype1 已装)
4. 移除 LC_ALL 注入 (已在本期改为条件注入,local-python 模式不会设)

本期不做,因为 dev profile 主路径走 ssh+docker 已足够。
