# P1-2 Sandbox 集成方案 — 把 harness-sandbox-docker 融到 harness-example-a2a

> 目的:启用 `--spring.profiles.active=sandbox` 后,supervisor / 子 agent 的工具调用(`FilesystemTool` / `ShellExecuteTool` / `SkillSaveTool`)全部落到 **已经存在的 Docker 镜像** `deepanalyze-vllm:latest` 里跑。
>
> 这是 [enhancement-proposal.md P1-2](enhancement-proposal.md) 的延续 — 第一阶段已经做了 `FilesystemConfig` + `SandboxProperties` + `application-sandbox.yml` 的**装配骨架**,本方案补齐真正能 `docker run` 起来所需的最后一公里。
>
> **前提**:宿主机已经有可用镜像(本机已确认 `deepanalyze-vllm:latest`)。本方案**不做 `docker build`**,也不依赖 `harness-sandbox-docker` 的 Dockerfile。

---

## 1. 当前差距

### 1.1 已经有的东西

| 来自模块 | 资产 | 用途 |
|---|---|---|
| `harness-example-a2a` | [FilesystemConfig.java](../src/main/java/io/agentscope/examples/harness/a2a/config/FilesystemConfig.java) `sandboxFilesystem()` Bean | 当 `harness.a2a.sandbox.enabled=true` 时构造 `DockerFilesystemSpec` |
| `harness-example-a2a` | [SandboxProperties.Sandbox](../src/main/java/io/agentscope/examples/harness/a2a/config/SandboxProperties.java) | image / workspaceRoot / isolationScope 配置项 |
| `harness-example-a2a` | [application-sandbox.yml](../src/main/resources/application-sandbox.yml) | profile,默认 image `alpine:3.20` |
| 宿主机 | **`deepanalyze-vllm:latest` 镜像已存在** | 跟 [DockerPythonSandboxExample.java:44](../../harness-sandbox-docker/src/main/java/io/agentscope/examples/harness/sandbox/DockerPythonSandboxExample.java) 用的同一个;含 python3 / shell / 数据分析常用工具 |
| `harness-sandbox-docker` | [DockerPythonSandboxExample.java](../../harness-sandbox-docker/src/main/java/io/agentscope/examples/harness/sandbox/DockerPythonSandboxExample.java) 里的 `BindMountEntry` / `WorkspaceSpec` / `workspaceProjectionRoots` 模式 | 端到端**怎么把宿主目录挂进容器+把 workspace 投射进去**的参考实现 |

### 1.2 缺什么

1. **a2a 的默认 image 是 `alpine:3.20`,没有 python / 没有数据分析工具**。改成 `deepanalyze-vllm:latest`(或可配置)即可。
2. **没有 bind mount 把宿主 workspace 挂进容器** — 现在的 `DockerFilesystemSpec` 只 `.image().workspaceRoot()`,容器里 `SkillSaveTool` 写的 `skills/*/SKILL.md` 留在容器内,容器销毁就丢。Docker example 用 `BindMountEntry` 解决了这个,a2a 这边没用上。
3. **镜像不在时启动直接 fail**(原生 Docker 行为) — 给一句友好 error message,提示 `docker pull` 或本地构建。**但不在 a2a 启动里跑 `docker build`** — 镜像应当是预制的运维产物,不是 demo 启动副作用。

---

## 2. 集成方式选择

镜像既然是预制的,**不再需要把 `harness-sandbox-docker` 当依赖引进来**(原来引它主要是为了拿 Dockerfile 和 `ensureImage()` 工具类,现在两个都用不上)。改动收敛到 a2a 模块自己内部 — 改 3 个文件、加 ~50 行代码。

| 维度 | 决定 |
|---|---|
| 镜像来源 | 宿主机预制(开发机已经有),a2a 不管 build |
| 镜像名 | `application-sandbox.yml` 里默认 `deepanalyze-vllm:latest`,env `HARNESS_A2A_SANDBOX_IMAGE` 覆盖 |
| 依赖 | **不引** `harness-sandbox-docker` 模块 — 它的 `BindMountEntry` / `WorkspaceSpec` 来自 `agentscope-harness`,a2a 已经间接依赖了 |
| 镜像不在时行为 | Spring 启动失败,error message 提示"请确保 image `<name>` 已在本机:`docker images | grep <name>`" — fail-loud 而不是自动 build |

---

## 3. 实施步骤(2 个文件 + 1 个 yml + 1 个文档,约 30 分钟)

### 3.1 `SandboxProperties` 改默认 image + 加 bind mount 开关

[SandboxProperties.java](../src/main/java/io/agentscope/examples/harness/a2a/config/SandboxProperties.java) 里 `Sandbox` 内部类:

| 字段 | 改动 |
|---|---|
| `image` 默认值 | `"alpine:3.20"` → `"deepanalyze-vllm:latest"` |
| 新增 `mountSkills` | `boolean`,默认 `true` — 是否把宿主 `<workspace>/skills/` 挂进容器,让 `SkillSaveTool` 写的 SKILL.md 持久化到宿主 |
| 新增 `mountMemory` | `boolean`,默认 `true` — 同理挂 `<workspace>/memory/` 让 `MemoryFlushHook` 写的日记本持久 |

### 3.2 `FilesystemConfig.sandboxFilesystem(...)` 接通 bind mount

[FilesystemConfig.java](../src/main/java/io/agentscope/examples/harness/a2a/config/FilesystemConfig.java):

| 改动 | 怎么做 |
|---|---|
| 注入 a2a 的 `Path workspace` Bean | 已经存在([InfraConfig.java](../src/main/java/io/agentscope/examples/harness/a2a/config/InfraConfig.java)),加构造参数即可 |
| 构造 `WorkspaceSpec` + `BindMountEntry` | 完全照抄 [DockerPythonSandboxExample.java:62-68](../../harness-sandbox-docker/src/main/java/io/agentscope/examples/harness/sandbox/DockerPythonSandboxExample.java#L62-L68);对 `skills` / `memory` 分别 `dataDir = workspace.resolve("skills"); Files.createDirectories(dataDir); BindMountEntry e = new BindMountEntry(); e.setHostPath(dataDir.toAbsolutePath().toString()); e.setReadOnly(false); workspaceSpec.getEntries().put("skills", e);` |
| `DockerFilesystemSpec` 链上 `.workspaceSpec(workspaceSpec)` | 替换原来只有 `.image().workspaceRoot()` 的写法 |
| 加 `.workspaceProjectionRoots(List.of("AGENTS.md", "knowledge", "subagents"))` | 让容器内只读看到 prompt / knowledge / subagent 配置 |
| **不**加 `ensureImage()` 调用 | 按你的要求 — 镜像不在,启动失败即可。`DockerFilesystemSpec` 自己 `docker create` 时会 surface 镜像缺失的 error |

### 3.3 `application-sandbox.yml` 改默认值

[application-sandbox.yml](../src/main/resources/application-sandbox.yml) image 默认值改成 `deepanalyze-vllm:latest`,加注释说明"镜像必须本机已存在,a2a 不做 build"。

### 3.4 README/enhancement-proposal 同步状态

[enhancement-proposal.md P1-2](enhancement-proposal.md) 状态从"✅ 已实施(装配)"升到"✅ 已实施(端到端可演示,需预制镜像)";加一行指向本文档。

---

## 4. 验证脚本(走完上面 4 步后)

```bash
# 0. 确认镜像在
docker images | grep deepanalyze-vllm

# 1. 启动 sandbox profile
mvn -pl agentscope-examples/harness-examples/harness-example-a2a spring-boot:run \
    -Dspring-boot.run.profiles=sandbox

# 2. 另一终端 — 看容器跑起来了
docker ps | grep deepanalyze-vllm

# 3. 调一次会触发文件写的请求
curl -X POST http://localhost:8889/chatA2A \
  -H 'Content-Type: application/json' \
  -d '{"message":"把刚才查询流程保存为 skill","session_id":"sandbox-demo-001"}'

# 4. 关键验证 — bind mount 工作:SKILL.md 应当出现在宿主目录
ls -la .agentscope/workspace/harness-a2a/skills/
# 应能看到新生成的 <skill_name>/SKILL.md
```

如果第 4 步看到宿主目录里有新文件 → sandbox 隔离 + bind mount 工作正常,a2a 在容器里写、宿主能读。

---

## 5. 不做的事(明确划清)

- **不**跑 `docker build` — 镜像是运维产物,a2a 启动不管
- **不**引 `harness-sandbox-docker` 模块依赖 — 用不上它的 Dockerfile / `DockerPythonSandboxImage` 了
- **不**把 sandbox 改成默认 ON — `harness.a2a.sandbox.enabled=false` 仍是默认值
- **不**做"镜像不在自动 fallback 到 local fs" — 显式 fail-loud 让用户知道环境没准备好

---

## 6. 工作量

| 步骤 | 时间 |
|---|---|
| 3.1 SandboxProperties | 10 分钟 |
| 3.2 FilesystemConfig | 15 分钟 |
| 3.3 yml + 3.4 文档 | 5 分钟 |
| 4. 端到端验证 | 5 分钟 |
| **小计** | **约 30 分钟** |
