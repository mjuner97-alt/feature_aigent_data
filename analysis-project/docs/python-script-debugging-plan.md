# Python 脚本前端调试环境实施方案

## 1. 目标

在现有 Python 脚本注册表基础上，增加类似 SQL 注册表的前端可调试能力：用户可以查看和编辑已注册脚本的 Python 源码，填写测试参数，在前端启动、观察和停止一次 Python 运行，并查看 stdout、stderr、退出码、耗时和 Python 异常行号。

Python 运行始终由后端编排，执行位置为远程 Docker 主机上的 `analysis-project-test` 容器。前端不直接连接 SSH、Docker 或数据库。

本期定位为“源码编辑 + 参数调试 + 单次运行观测”，不实现 IDE 级断点、变量查看、单步执行或任意命令终端。

## 2. 当前系统基线

### 2.1 已有能力

- 前端 Python 注册表：`frontend/src/pages/ScriptRegistryPage.vue`
- 前端 Python API：`frontend/src/api/scriptRegistry.ts`
- Python 类型：`frontend/src/types/scriptRegistry.ts`
- 后端管理接口：`v2/registry/controller/ScriptRegistryController.java`
- 后端注册服务：`v2/registry/service/ScriptRegistryManageService.java`
- 注册实体：`entity/ScriptRegistryEntry.java`
- GaussDB MyBatis Mapper：`mapper/gauss/ScriptRegistryMapper.java` 与对应 XML
- 现有执行工具：`v2/tools/ScriptExecTool.java`
- 远程执行配置：`application-dev.properties`

### 2.2 当前执行拓扑

```text
浏览器
  -> Spring Boot API
      -> ScriptRegistryMapper / 数据源白名单 / 参数 schema 校验
      -> SSH docker-host
          -> docker exec analysis-project-test python3 /workspace/harness-a2a/scripts/...
              -> stdout / stderr / exit code
```

当前配置已经使用：

- SSH target：`docker-host`
- 容器：`analysis-project-test`
- 容器工作区：`/workspace/harness-a2a`
- 脚本目录：`/workspace/harness-a2a/scripts`
- 远程 Docker：`harness.a2a.sandbox.remote-docker-enabled=true`
- 远程脚本和工作区通过挂载/同步可见

当前 `ssh docker-host` 在方案调研时解析到 `116.148.121.31`，连接超时。该问题不改变设计，但必须在实施验收阶段重新确认 SSH 配置和网络连通性。

## 3. 推荐方案

抽取一个可复用的 Python 执行服务，在其上复用现有 `ScriptExecTool` 的安全校验和 Docker/SSH 命令构造逻辑。

### 3.1 方案选择

#### 方案 A：后端统一编排，复用现有 ScriptExecTool 执行链路（推荐）

- 前端只调用后端 API。
- 后端根据注册记录生成固定脚本路径和数据源环境变量。
- Python 进程仍运行在 `analysis-project-test` 容器内。
- 源码编辑、Agent 工具执行、调试执行共享同一套路径、数据源和参数安全规则。

优点是改动集中、权限边界清晰、与当前部署方式一致。代价是需要将现有同步执行逻辑抽取为支持异步任务和流式输出的服务。

#### 方案 B：新增独立 Python 调试服务

需要新增服务容器、任务队列、鉴权和服务间通信。断点调试扩展性更好，但会重复实现脚本白名单、数据源注入和容器隔离，当前需求不采用。

#### 方案 C：前端直接 SSH/Docker

会把 SSH 或 Docker 操作暴露到浏览器侧，无法稳定复用后端鉴权和数据源最小权限，不采用。

## 4. 功能范围

### 4.1 前端功能

在现有 `ScriptRegistryPage.vue` 的新增/编辑交互中增加源码和调试区域：

1. 加载注册脚本源码。
2. 编辑 Python 源码。
3. 保存源码，并提示保存成功或失败。
4. 展示参数 schema，并编辑调试参数 JSON。
5. 根据参数 schema 做基础表单提示和 JSON 校验。
6. 启动调试运行。
7. 运行中展示状态、已运行时长、stdout 和 stderr。
8. 支持停止运行。
9. 运行结束展示 `SUCCESS`、`FAILED`、`TIMEOUT`、`CANCELLED` 状态、退出码和耗时。
10. 解析 stderr 中常见 Python traceback，显示文件名、行号、异常类型和异常消息。
11. 关闭弹窗或切换脚本时，若存在未保存源码或运行中的任务，要求确认。

本期不要求引入重量级 Monaco 编辑器；可以先使用现有 Element Plus 文本域实现可交付版本。若后续需要语法高亮，再独立替换编辑器组件，不改变后端接口。

### 4.2 后端功能

新增脚本源码管理和调试任务 API。接口应复用现有 `X-User-Id` 请求头和项目当前的鉴权方式。

建议接口：

```text
GET  /api/script-registry/{id}/source
PUT  /api/script-registry/{id}/source
POST /api/script-registry/{id}/debug
GET  /api/script-registry/debug/{runId}/events
POST /api/script-registry/debug/{runId}/cancel
```

源码读取响应：

```json
{
  "scriptId": "q2_1_metrics_by_dept_version",
  "scriptPath": "q2_1_metrics_by_dept_version.py",
  "content": "#!/usr/bin/env python3\\n...",
  "contentHash": "sha256:...",
  "updatedAt": "2026-08-27T12:00:00"
}
```

源码保存请求：

```json
{
  "content": "#!/usr/bin/env python3\\n...",
  "expectedContentHash": "sha256:..."
}
```

`expectedContentHash` 用于防止两个页面互相覆盖。Hash 不匹配时返回 `409 Conflict`，前端必须重新加载后再保存。

调试请求：

```json
{
  "params": {
    "dept": "杭州开发二部",
    "version": "2026年7月份版本"
  },
  "timeoutSeconds": 60,
  "sourceMode": "SAVED"
}
```

第一期只允许 `sourceMode=SAVED`，即先保存源码再运行。接口保留 `sourceMode` 字段，为以后增加临时草稿运行预留扩展，但不允许本期直接执行未落盘的前端任意源码。

调试创建响应：

```json
{
  "runId": "uuid",
  "scriptId": "q2_1_metrics_by_dept_version",
  "status": "QUEUED",
  "createdAt": "2026-08-27T12:00:00"
}
```

事件类型：

```text
run_started
stdout
stderr
run_finished
run_failed
run_cancelled
```

完成事件统一包含：

```json
{
  "runId": "uuid",
  "status": "SUCCESS",
  "exitCode": 0,
  "elapsedMs": 1234,
  "stdout": "...",
  "stderr": "..."
}
```

## 5. 后端架构和职责

### 5.1 源码服务

新增 `ScriptSourceService`，职责为：

- 根据注册记录生成脚本相对路径。
- 将路径解析限制在 `workspace/scripts` 下。
- 读取源码并计算 SHA-256。
- 校验 UTF-8、最大源码大小和 Python 文件后缀。
- 保存前创建最近一次备份，例如同目录下的受控 `.bak` 文件或应用工作区备份目录。
- 使用临时文件写入后原子替换，避免容器读取半截文件。
- 校验 `expectedContentHash`。

源码保存不能接受前端传入 `scriptPath`、绝对路径或容器路径。路径仍由注册记录控制。

### 5.2 调试任务服务

新增 `ScriptDebugService`，职责为：

- 创建 `runId` 并维护任务状态。
- 查询启用的脚本注册记录。
- 复用统一参数 schema 校验。
- 复用数据源白名单和环境变量注入。
- 启动容器内 Python 进程。
- 分离读取 stdout/stderr，避免缓冲区满导致死锁。
- 将输出按行或按大小块发布到事件流。
- 实现超时终止和用户取消。
- 保存最近一轮的有限大小执行结果，避免无限制占用 JVM 内存。
- 在任务完成、失败、超时或取消后释放进程和任务记录。

建议任务状态：

```text
QUEUED -> RUNNING -> SUCCESS
                   -> FAILED
                   -> TIMEOUT
                   -> CANCELLED
QUEUED -> CANCELLED
```

任务状态存储第一期可使用进程内并发 Map，限制单实例并发数；如果生产环境要求跨实例或历史查询，再增加 GaussDB 调试运行表。不要在第一期引入任务队列，避免扩大部署范围。

### 5.3 执行适配器

从 `ScriptExecTool` 抽取 `PythonProcessRunner` 或同等职责的组件，统一提供：

```java
PythonExecutionResult run(PythonExecutionRequest request)
```

请求至少包含：

- 固定脚本路径
- 参数 JSON
- 声明的数据源
- timeout
- stdout/stderr 回调
- cancel signal

适配器根据配置选择：

- 远程模式：`ssh docker-host docker exec -i ... analysis-project-test python3 <containerPath>`
- 本地 Docker 模式：`docker exec -i ... analysis-project-test python3 <containerPath>`
- 本地 Python fallback：仅保留现有开发配置行为，不允许生产绕过容器安全边界

数据库密码只通过现有环境变量注入到容器，不进入 API 响应、SSE 事件、日志或前端状态。

## 6. 参数和脚本校验

### 6.1 参数

调试接口和 `script_exec` 必须共用参数校验器：

- 参数名必须在 `params_schema` 中。
- required 参数必须存在。
- 基础类型校验：`string`、`int`、`date`、`boolean`、数组类型。
- 禁止未知参数。
- 参数 JSON 必须是对象，大小受限。

当前 `ScriptExecTool` 只做参数名和必填校验，实施时应将 schema 解析/类型校验抽取为独立组件，避免 Agent 执行和前端调试行为不一致。

### 6.2 源码

源码保存前校验：

- UTF-8 可解码。
- 最大大小建议为 512 KB。
- 必须是 Python 源码文件内容。
- 不允许通过源码内容修改执行命令、容器名或脚本路径。
- 执行命令永远固定为 `python3 <受控脚本路径>`。

源码内容本身可以执行 Python 语言允许的操作，因此安全边界必须依赖容器、数据库账号权限、工作区隔离和超时限制；不能把简单的字符串禁词检查当作沙箱。

### 6.3 路径

- `script_id` 继续使用注册表唯一约束。
- `script_path` 继续匹配安全字符规则并拒绝 `..`。
- 源码读写路径必须 `normalize` 后仍位于 `workspace/scripts` 内。
- 容器路径只能由后端的容器工作区配置和受控相对路径拼接。

## 7. 前端交互设计

将现有 Python 编辑弹窗扩展为左右或上下两个工作区：

```text
脚本基本信息 / 数据源 / 参数 schema
--------------------------------------
Python 源码编辑区       调试参数 JSON
                         [调试运行] [停止]
--------------------------------------
运行状态 / 退出码 / 耗时
stdout                  stderr / traceback
```

交互规则：

- 打开编辑时同时请求注册详情和源码。
- 源码加载失败时禁止运行，允许重试。
- 参数 JSON 非法时禁止提交。
- 保存源码按钮只保存源码，不改变注册表元数据。
- 调试运行按钮在请求成功后变为运行中状态。
- stdout/stderr 使用追加方式更新，并限制前端展示长度。
- 运行结束后保留结果直到关闭弹窗或再次运行。
- 停止按钮触发后端取消接口；前端不能只停止轮询。
- 关闭弹窗时，运行中的任务继续由后端清理，或明确要求用户先停止；推荐要求停止后再关闭。

## 8. 错误处理

统一返回可读错误码和 message，至少覆盖：

```text
SCRIPT_NOT_FOUND
SCRIPT_DISABLED
SOURCE_NOT_FOUND
SOURCE_HASH_CONFLICT
SOURCE_TOO_LARGE
INVALID_PARAMS_JSON
PARAM_SCHEMA_VIOLATION
UNSUPPORTED_DATASOURCE
SSH_UNAVAILABLE
CONTAINER_UNAVAILABLE
PYTHON_NOT_FOUND
SCRIPT_TIMEOUT
SCRIPT_CANCELLED
SCRIPT_EXIT_NON_ZERO
DEBUG_CONCURRENCY_LIMIT
```

前端显示业务错误 message，但不显示数据库密码、完整连接串或内部主机敏感信息。后端日志保留 `runId`、`scriptId`、transport、exit code、耗时和输出长度，输出正文按需要截断或脱敏。

Python traceback 解析只用于展示定位信息，不依赖它判断任务成功；最终成功标准仍是进程退出码为 0。

## 9. 并发、资源和生命周期

第一期建议限制：

- 单实例同时运行的调试任务不超过 2 个。
- 单个任务 stdout 和 stderr 各限制 1 MB，超出后继续运行但截断展示并记录 `outputTruncated=true`。
- 默认超时沿用注册记录，前端只能在 `1..min(注册超时,300)` 内选择。
- 任务完成后保留内存结果 10 分钟，之后清理。
- 用户取消先发送进程终止，再等待最多 2 秒，仍未结束则强制终止。
- 应用关闭时取消所有调试任务。

## 10. 计划修改文件

### 后端

- 修改：`src/main/java/com/agentscopea2a/v2/registry/controller/ScriptRegistryController.java`
- 修改：`src/main/java/com/agentscopea2a/v2/registry/service/ScriptRegistryManageService.java`
- 修改或抽取：`src/main/java/com/agentscopea2a/v2/tools/ScriptExecTool.java`
- 新增：脚本源码 DTO、调试请求/响应 DTO、运行事件 DTO
- 新增：`ScriptSourceService`
- 新增：`ScriptDebugService`
- 新增：`PythonProcessRunner`
- 新增：参数 schema 统一校验组件
- 新增：对应单元测试和 Controller/Service 测试

### 前端

- 修改：`frontend/src/pages/ScriptRegistryPage.vue`
- 修改：`frontend/src/api/scriptRegistry.ts`
- 修改：`frontend/src/types/scriptRegistry.ts`
- 可新增：`frontend/src/components/PythonSourceEditor.vue`
- 可新增：`frontend/src/components/ScriptDebugPanel.vue`

### 配置和文档

- 修改：`src/main/resources/application-dev.properties`，增加调试并发、输出上限、结果保留时间等配置。
- 检查：`application-docker.properties`，确保生产配置不绕过容器。
- 可新增：GaussDB 调试运行历史表迁移；第一期不强制新增。
- 修改：部署文档，补充 `docker-host`、容器挂载、Python 依赖和连通性检查。

## 11. 测试方案

### 11.1 后端单元测试

- 源码路径不能逃逸出 `workspace/scripts`。
- 源码保存正确计算 hash，并拒绝 hash 冲突。
- 源码大小超限时拒绝保存。
- 参数缺失、未知参数、类型错误均被拒绝。
- 任务正常退出时返回 stdout、stderr、exit code、elapsedMs。
- stdout/stderr 大于管道缓冲区时不会死锁。
- 超时任务进入 `TIMEOUT` 并终止进程。
- 取消任务进入 `CANCELLED` 并终止进程。
- SSH/Docker 不可用时返回可识别错误。
- 数据库密码不会出现在日志和事件对象。
- 并发达到上限时拒绝新任务。

### 11.2 Controller/API 测试

- 无脚本记录返回 404 或业务错误。
- 无编辑权限不能读取或保存源码。
- 保存使用 `expectedContentHash` 时正确处理 409。
- 调试接口返回 `runId`。
- SSE 事件顺序为 started、输出事件、finished/failed/cancelled。
- 取消不存在或已结束的任务返回幂等结果。

### 11.3 前端测试

- 源码加载、保存、hash 冲突提示。
- 非法参数 JSON 阻止运行。
- 调试运行期间按钮状态正确。
- stdout/stderr 事件按顺序追加。
- traceback 行号展示正确。
- 运行中的弹窗关闭有阻止/确认提示。

### 11.4 容器验收

在 Docker host 上执行：

```bash
ssh docker-host 'echo ssh-ok'
ssh docker-host 'docker ps --format "{{.Names}}" | grep -Fx analysis-project-test'
ssh docker-host 'docker exec analysis-project-test python3 --version'
ssh docker-host 'docker exec analysis-project-test python3 -c "import pandas, jpype; print(pandas.__version__)"'
ssh docker-host 'docker exec analysis-project-test test -f /workspace/harness-a2a/scripts/q2_1_metrics_by_dept_version.py'
```

然后使用前端对 `q2_1_metrics_by_dept_version` 输入：

```json
{
  "dept": "杭州开发二部",
  "version": "2026年7月份版本"
}
```

验收结果：

- 页面能读到源码。
- 修改源码并保存后容器内文件内容一致。
- 点击调试后能看到运行中状态。
- 能收到 stdout/stderr。
- 正常脚本展示退出码 0。
- 人为制造 Python 语法错误时，页面展示 stderr 和行号。
- 点击停止后进程结束，状态为 `CANCELLED`。
- 超时测试不会残留 Python 进程。

## 12. 实施顺序

### 阶段 1：执行层抽取

先将 `ScriptExecTool` 中的路径校验、参数校验、数据源环境注入和命令构造抽取为可复用组件，确保现有 Agent `script_exec` 行为不回归。

### 阶段 2：源码读写

增加源码读取、hash 校验、备份和原子保存接口，完成权限与路径测试。

### 阶段 3：调试任务

增加进程生命周期、输出流读取、超时、取消、并发限制和 SSE 事件。

### 阶段 4：前端调试工作区

在 Python 注册表页面增加源码编辑、调试参数、运行控制和结果面板。

### 阶段 5：集成验收

执行后端测试、前端构建、SSH/Docker 检查和目标脚本真实运行；确认远程主机别名 `docker-host` 的 SSH 连接恢复后再标记部署验收通过。

## 13. 非目标和后续扩展

本期不实现：

- 任意 Python 文件浏览。
- 任意 Shell 命令执行。
- 在线安装 pip 依赖。
- 断点、单步、变量和调用栈调试器。
- 多用户同时编辑同一源码的实时协作。
- 跨多实例的调试任务恢复。

后续可在不改变前端基本协议的情况下增加：

- Monaco 语法高亮和行号跳转。
- 临时草稿运行，但仍需服务端生成受控临时文件。
- 调试运行历史和结果下载。
- 基于版本号的源码版本管理和回滚。
- 独立调试容器或更严格的资源限制。

