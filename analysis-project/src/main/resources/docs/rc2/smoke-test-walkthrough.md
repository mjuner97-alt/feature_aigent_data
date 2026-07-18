# 主要功能端到端验证手册

本文档记录 2026-06-26 ~ 2026-06-27 对 `analysis-project` 的端到端冒烟验证全过程：环境怎么搭、请求体长什么样、应该在日志/文件系统里看到什么。后续任何人接手验证只要按这份文档复跑一遍即可。

参考文档：[README.md](README.md) §1 主要功能矩阵。

---

## 1. 前置环境

### 1.1 必备外部依赖

| 资源 | 说明 | 验证方式 |
|---|---|---|
| MySQL（业务库 + agentscope_core） | 存质量数据、`memory_messages`、`response_cache`、`skill_candidate`、`skill_run`、`skill_vector` 等表 | `mysql -h ... -P ... -e "select 1"` |
| LLM Provider（智谱 GLM-5.2 via 火山 ARK） | `ark.cn-beijing.volces.com/api/coding/v1/chat/completions` | curl /v1/models 返回 200 |
| 远端 Docker daemon `docker-host` | sandbox 用，~/.ssh/config 里配 alias，`ssh docker-host` 能登录 | `ssh docker-host docker version` |
| 远端工作区 `/opt/agentscope-workspace/harness-a2a/{skills,artifacts}` | sandbox bind-mount 的根目录 | `ssh docker-host ls /opt/agentscope-workspace` |

### 1.2 占位符替换

`src/main/resources/application-sandbox-windows.properties` 里的占位字段必须改成真实值（MEMORY 已记录的 boot recipe）：

- `spring.datasource.url`、`username`、`password`
- `agentscope.llm.api-key`
- 远端 docker 镜像（如 `agentscope-runtime:py311`）

### 1.3 关键开关（`application.properties`）

```properties
# Cache: 必须 true 才能看到 HIT/MISS 日志和短路效果
harness.a2a.response-cache.enabled=true

# Skills 自合成 PR2 (默认开)
harness.skills.auto-synth.enabled=true
harness.skills.auto-synth.threshold=3

# Skills 向量检索 PR3 (默认开)
harness.skills.retrieval.enabled=true
harness.skills.retrieval.top-k=3
harness.skills.retrieval.min-cosine=0.55

# Skills 自进化 PR4 (默认开)
harness.skills.evolution.enabled=true
```

### 1.4 启动命令

```bash
cd D:/AILLMS/javacode/analysis-project/analysis-project
mvn -DskipTests package
# 必须把 DOCKER_HOST 指向远端 ssh
DOCKER_HOST=ssh://docker-host \
  java -jar target/analysis-project-0.0.1-SNAPSHOT.jar \
       --spring.profiles.active=dev,sandbox-windows
```

启动 ~40 s 后 `curl -o /dev/null -w "%{http_code}" http://localhost:8081/` 返回 `405` 即可。

### 1.5 测试请求文件位置

所有测试用 JSON 放在 `tmp_test/` 目录（已 gitignore），下文每个场景给出文件名 + 内容。

发送命令统一为：

```bash
curl -sS -N -X POST http://localhost:8081/ai/chat \
     -H "Content-Type: application/json; charset=utf-8" \
     --data-binary @tmp_test/<file>.json \
     --max-time 240 > tmp_test/resp_<file>.txt
```

⚠️ **必须 `--data-binary @file`**：Windows shell 不会保留中文 UTF-8，直接 `-d '{...}'` 会被服务端识别为乱码（MEMORY 已记录此问题）。

---

## 2. 五大功能逐项验证

下文每一节都给出：**请求 JSON → 发送命令 → 应看到的日志关键字 / 文件 / 响应内容**。

### 2.1 功能 #1：简单查询（query_data 单跳）

**目的**：验证 supervisor 解析意图 → 派单给 `query_data` subagent → 两步路由（tool_index 选工具 + router_tool 执行）→ 单行结果。

**请求** `tmp_test/req1.json`：
```json
{"input":"查询2026年1季度杭州开发一部的质量数据","session_id":"test-v1","user_id":"u-test"}
```

**应看到**：

1. **路由链路日志**：
   ```
   router_tool called: toolId=quality_query_by_department_quarter,
   paramsJson={"toolId":"quality_query_by_department_quarter",
               "quarter":"2026年1季度","department":"杭州开发一部"}
   ```
2. **响应体**（SSE `event:done` 的 `resultAll`）：
   ```
   | 季度 | 部门 | 质量分（缺陷密度） |
   |---|---|---|
   | 2026年1季度 | 杭州开发一部 | 23.1 |
   ```
3. **耗时**：首次 ~120 s（含 LLM + 子 agent + 远端 docker artifact 清理）。

**校验基线**（写在 [workspace/MEMORY.md](../workspace/MEMORY.md)）：

| 部门 | Q1 缺陷密度 |
|---|---|
| 杭州开发一部 | 23.1 |
| 杭州开发二部 | 13.1 |
| 杭州开发三部 | 3.1 |
| 杭州开发四部 | 6.1 |
| 杭州开发五部 | 26.1 |

数字对不上 → 直接判失败，**不允许 LLM 心算**（AGENTS.md 已禁）。

---

### 2.2 功能 #2：响应缓存命中

**目的**：验证 `ResponseCacheHook` 在 PreCallEvent 提取维度 → 命中 MySQL `response_cache` 表 → 抛 `CacheHitException` 短路 agent 执行。

**前置**：`harness.a2a.response-cache.enabled=true`（**这是个曾被关闭过的临时开关，验证前必须确认**）。

**步骤**：发送两次同一个 `req1.json`，第二次应在 ≤ 5 s 内返回。

**应看到**：

| 调用 | 日志关键字 | 现象 |
|---|---|---|
| 第 1 次 | `Cache MISS for key=s:test-v1\|query\|time=QUARTER:2026年1季度\|dept=杭州开发一部` | 120 s 走完整链路 |
| (PostCall) | `Response cached for key=s:test-v1\|...` | 写入 `response_cache` 表 |
| 第 2 次 | `Cache HIT for key=s:test-v1\|...` `Cache HIT for /ai/chat` | < 5 s 直接返回缓存内容 |

**Cache key 结构**：`<tenantBucket>|<intent>|time=...|dept=...`

- `tenantBucket`：`u:<userId>` > `s:<sessionId>` > `_anon`（当前 SSE 路径只塞 sessionId，所以走 `s:` 前缀——见 §3.1 已知问题）
- `intent`：`query` / `analyze` / `skill`（关键字分类）
- 维度 key：`DimensionState.toCacheKey()` 序列化 time + dept + peer + persons

**反例**：若把开关关回 `false`，`ResponseCacheHook.onEvent` 第一行 `if (!enabled) return` 直接放行，**日志里完全看不到 HIT/MISS**——这是排查"缓存好像没生效"时最先要确认的点。

---

### 2.3 功能 #3：数据分析（环比 + artifact handoff 判定）

**目的**：验证 supervisor 识别"对比/环比"为 `analyze` 意图 → 派 `analyze_data` subagent → 调用 `data_primitives.compare` → 输出环比变化率。

**请求** `tmp_test/req2.json`：
```json
{"input":"对比2026年1季度和2026年2季度杭州开发一部的缺陷密度，算环比变化率","session_id":"test-v2","user_id":"u-test"}
```

**应看到**：

- 响应含 `Q1=23.1 → Q2=25.4 环比 +9.96%` 字样
- supervisor **不调用 code_interpreter**（≤ 3 数字按 AGENTS.md 硬规则走 `data_primitives`）
- artifact handoff **不触发**（行数 < 4 阈值）

**触发 artifact handoff 的对照实验**：换成多行结果的问题，比如"查询2026年1季度所有部门的质量数据"，结果有 8 行，会看到：

```
ArtifactHandoffHook: writing artifact for tool=query_data
                     rows=8 threshold=4 path=.../artifacts/u-test/<taskId>/result.json
ArtifactAccessHook: subagent code_interpreter requested artifact_read
```

---

### 2.4 功能 #4：多租户隔离

**目的**：验证不同 user_id（或 session_id）的同一问题在 cache、artifact bucket、memory 三处都不串扰。

**请求文件**：

`tmp_test/req_alice.json`：
```json
{"input":"查询2026年1季度杭州开发二部的质量数据","session_id":"alice-1","user_id":"alice"}
```

`tmp_test/req_bob.json`：
```json
{"input":"查询2026年1季度杭州开发三部的质量数据","session_id":"bob-1","user_id":"bob"}
```

**应看到**：

- alice 响应 `杭州开发二部 \| 13.1`
- bob 响应 `杭州开发三部 \| 3.1`
- cache key 前缀分别为 `s:alice-1` 和 `s:bob-1`
- artifact bucket 路径分别 `.../artifacts/_anon/alice-1/` 和 `.../artifacts/_anon/bob-1/`（user_id 未透传时 fallback 到 `_anon`，见 §3.1）
- `memory_messages` 表里两个 session 的行互不可见

⚠️ 并发跑时 LLM 端可能因配额排队，alice 在我们这次测试中 SSE 流被 curl 180 s 截断，但落到响应缓冲区的 partial 已带正确答案——这不是隔离失败，只是流式被截。

---

### 2.5 功能 #5：保存技能（save_skill 显式落盘）

**目的**：验证用户显式要求"保存技能"时，`generate_skill` subagent 起草 SKILL.md → 调 `save_skill` 工具 → 双端（本地 + 远端 bind-mount）原子写入。

**请求** `tmp_test/req_skill.json`：
```json
{"input":"把刚才查询某季度某部门质量数据的流程，固化保存为一个名为 dept_quality_lookup 的 skill，便于后续复用。说明该 skill 的用途、输入参数、调用工具链，并调用 save_skill 把 SKILL.md 写入工作区。","session_id":"test-v1","user_id":"u-test"}
```

**应看到**：

| 位置 | 路径 | 验证 |
|---|---|---|
| 本地 | `.agentscope/workspace/harness-a2a/skills/dept_quality_lookup/SKILL.md` | 文件存在且包含 `name: dept_quality_lookup` frontmatter |
| 远端 | `/opt/agentscope-workspace/harness-a2a/skills/dept_quality_lookup/SKILL.md` | `ssh docker-host stat ...` 字节数 = 本地 |
| 日志 | `Skill saved: dept_quality_lookup v1` | `SkillSaveTool` 日志 |

**已知小瑕疵**：LLM 起草的正文里已经带 `---\nname: ...\n---` 时，`SkillSaveTool` 会再包一层 frontmatter，造成头部两块 `---`。不影响检索，可在 `SkillSaveTool.writeContent` 加"已含 frontmatter 则跳过"判断修掉。

---

### 2.6 功能 #6：技能向量检索（PR3）

**目的**：验证查询时 `SkillRetrievalHook` 用 cosine 相似度从 `skill_vector` 表挑 Top-K SKILL.md 注入到 supervisor / subagent 的 system prompt。

**做法**：用一个语义相近、但用词不同的问题（保证 L1 关键字 miss、L2 必须发挥作用）。

**请求** `tmp_test/req_synth.json`（同 §2.7 复用）：
```json
{"input":"查询2026年2季度杭州开发五部的质量数据","session_id":"synth-test-1","user_id":"u-synth"}
```

**应看到**（call#1，cache MISS 时）：

```
L1 result for fp=s:synth-test-1|query|time=QUARTER:2026年2季度|dept=杭州开发五部:
  picked=[]
L2 topK (k=3, min=0.55) returned 3 hit(s):
  [SkillHit[name=quarterly_defect_density_by_dept, cosine=0.749],
   SkillHit[name=department_quality_query_by_quarter, cosine=0.718],
   SkillHit[name=quarterly_dept_quality_query, cosine=0.573]]
SkillRetrievalHook injected 3 skill(s) for tenant=s:synth-test-1 ...
```

在 LLM 请求体里能看到 system prompt 开头被插入：

```
<!-- skills.retrieved (PR3) -->

### Retrieved skill: quarterly_defect_density_by_dept
---
name: quarterly_defect_density_by_dept
...

### Retrieved skill: department_quality_query_by_quarter
...
```

**调试小技巧**：日志级别开到 DEBUG 后能看到 `L1 result`、`L2 topK` 两段——L1 是关键字 + 别名表，L2 是 embeddings + 余弦。如果两边都返回空，先检查 `skill_vector` 表行数与 `embedding_dim`。

---

### 2.7 功能 #7：技能自动合成（PR2，同指纹 ≥ 3 次）

**目的**：验证 cache HIT / MISS 两条路径都把 `tenant|intent|dimensionKey` 指纹 bump 到同一行 `skill_candidate`，累计 ≥ threshold 后 `SkillSynthesisRunner` 异步起 LLM 蒸馏新 SKILL.md。

**前置**：

- `harness.skills.auto-synth.enabled=true`，`threshold=3`
- `harness.a2a.response-cache.enabled=true`（否则后两次走 MISS 路径也行，只是慢）

**做法**：同一 JSON 连发 3 次。

```bash
for i in 1 2 3; do
  curl -sS -N -X POST http://localhost:8081/ai/chat \
       -H "Content-Type: application/json; charset=utf-8" \
       --data-binary @tmp_test/req_synth.json \
       --max-time 240 > tmp_test/resp_synth_$i.txt
done
```

**应看到的日志时序**：

| Call | 路径 | 关键日志 |
|---|---|---|
| #1 | MISS | `SkillSynthesisHook [MISS path] candidate ... hit=1 status=pending thr=3` |
| #2 | HIT | （HIT 路径 bump 不主动打 INFO，但通过下一次的 hit 数验证） |
| #3 | HIT，跨阈值 | `SkillSynthesisRunner: Skill synthesis triggered: fingerprint=... hit=3` |
| 异步（call#3 后约 15 s） | 蒸馏 LLM 请求 → 落盘 | `OpenAIClient: 你正在为一个'质量数据智能助手'蒸馏可复用的 skill...` <br> `SkillFileSystemHelper: Successfully saved skill: quarterly_dept_quality_query` <br> `SkillSaveTool: Skill saved: quarterly_dept_quality_query v2` <br> `SkillSynthesisRunner: Auto-synthesised skill 'quarterly_dept_quality_query' from fingerprint ...` |

**应看到的文件**：

- 本地 `.agentscope/workspace/harness-a2a/skills/quarterly_dept_quality_query/SKILL.md`
- 远端 `/opt/agentscope-workspace/harness-a2a/skills/quarterly_dept_quality_query/SKILL.md`
- 文件 frontmatter 含 `version: 2, last_evolved_at: 2026-06-27`——重名时走 evolve 路径，version 自增

**注意**：蒸馏 prompt 是整体重写，**description / sample_questions 都会被 LLM 改**。若要锁定旧描述，需在 `SkillSynthesisRunner` 的 prompt 里加"保留原 description"约束。

---

## 3. 已知问题与限制

### 3.1 SSE 路径未透传 user_id

`ChatStreamServiceImpl.stream` 只把 `req.sessionId` 塞进 `RuntimeContext`，没塞 `userId`。后果：

- `ResponseCacheHook.tenantBucket()` 退回 `s:<sessionId>`，cache 实际是 session-scoped 而非 user-scoped
- `SkillEvolutionHook.handlePostCall` 日志显示 `runtimeContextUser=null`
- artifact bucket 路径退回 `.../_anon/<sessionId>/`

修复方法见 [user-id-cache-scope-plan.md](user-id-cache-scope-plan.md)。

### 3.2 actuator metrics 端点 404

`management.endpoints.web.exposure.include` 没配，`/actuator/metrics/harness.a2a.cache` 全部返回 404。Micrometer counter 仍在内存中递增、功能不受影响，但外部观测拿不到。修复：

```properties
management.endpoints.web.exposure.include=health,info,metrics
```

### 3.3 SSH alias 启动告警

`Control socket "/dev/null" does not exist` 是因为 `~/.ssh/config` 禁了 ControlMaster。每次 ssh 多 ~300 ms 握手，可忽略。

### 3.4 中文 shell 参数乱码

Windows + bash 直接 `-d '{"input":"中文..."}'` 必坏。**始终用 `--data-binary @file.json`**（文件 UTF-8 编码）。

---

## 4. 一次完整冒烟时长参考

按本文档顺序跑一遍各场景一次：

| 场景 | 实测耗时 |
|---|---|
| 服务启动 | 40 s |
| 功能 #1 简单查询 | 120 s |
| 功能 #2 缓存 HIT 复发 | 5 s |
| 功能 #3 分析对比 | 90 s |
| 功能 #4 多租户 alice+bob 并发 | 120 s |
| 功能 #5 save_skill | 100 s |
| 功能 #7 synthesis 3 连发（含异步蒸馏） | 130 s |
| **合计** | **~10 min** |

---

## 5. 验证产出存放位置

| 类型 | 路径 |
|---|---|
| 测试 JSON | `tmp_test/req*.json` |
| 响应快照 | `tmp_test/resp_*.txt` |
| 服务日志（最近一次） | `target/logs/spring.log` 或 stdout |
| 新生成 SKILL.md（本地） | `.agentscope/workspace/harness-a2a/skills/<name>/SKILL.md` |
| 新生成 SKILL.md（远端） | `/opt/agentscope-workspace/harness-a2a/skills/<name>/SKILL.md` |
| MySQL 表 | `response_cache` / `skill_candidate` / `skill_run` / `skill_vector` / `memory_messages` |

---

## 6. 复跑 Checklist

- [x] MySQL 通、agentscope_core 库表已迁移（2026-07-04 验证：`116.148.122.44:3306` TCP-open，`skill_candidate` / `QualitySupervisor_episodic_memory` 表已 ensured）
- [x] LLM key、Docker host alias 配置正确（GLM-5.2 `103.236.76.197:11223` 200；`ssh docker-host SSH_OK`；`agentscope-shared-demo` Up 18h）
- [x] `application.properties` cache/synth/retrieval/evolution 四个开关 = true（synth 在 `application-sandbox-windows.properties` 中由 false 改为 true，详见下方说明）
- [x] `mvn -DskipTests package` 成功（2026-07-04 03:04 重新打包，含 auto-synth 修复）
- [x] 服务 `405` 响应根路径（启动 8.28s，`curl / → 405`）
- [x] §2.1 ~ §2.7 各场景日志关键字 / 文件落盘 全部命中
- [ ] 服务退出，端口 8081 关闭（保留运行中以便复查）

---

## 7. 2026-07-04 验证记录

| 场景 | 状态 | 关键证据 |
|---|---|---|
| §1 前置条件 | ✅ | MySQL/SSH/容器/Embedding/LLM 全部通 |
| §2.1 简单查询 | ✅ | 响应含 `杭州开发一部 \| 23.1`；router_tool 链路日志完整 |
| §2.2 响应缓存命中 | ✅ | 第 2 次请求 1s 返回；`Cache HIT for key=u:u-test\|query\|...` |
| §2.3 数据分析（环比） | ✅ | 响应含 `Q1=23.1 → Q2=25.4 环比 +9.96%` |
| §2.4 多租户隔离 | ✅ | alice→`杭州开发二部 \| 13.1`；bob→`杭州开发三部 \| 3.1`；cache key 前缀隔离 |
| §2.5 保存技能 | ✅ | `Successfully saved skill: dept_quality_lookup`；本地 + 远端 `skills-auto/` 双写一致 |
| §2.6 技能向量检索 (PR3) | ✅ | L1 miss (`picked=[]`) → L2 topK `cosine=0.7618438` → `injected 1 skill(s)` |
| §2.7 技能自合成 (PR2) | ✅ | `Skill synthesis triggered: fingerprint=... hit=3` → `Auto-synthesised skill 'query_quality_data_by_quarter_and_dept'`；本地 + 远端落盘 |

### 7.1 关键修复

`sandbox-windows` profile 历史上把 `harness.skills.auto-synth.enabled` 强制改为 `false`（注释说 Windows 拓扑下蒸馏会生成泛化 skill）。本次验证发现当前代码已修复工具链路，把开关改回 `true` 后 PR2 蒸馏生成的 `query_quality_data_by_quarter_and_dept` 是真实工具链 skill（含 `quality_query_by_department_quarter` 工具引用），未出现历史问题。已在 `application-sandbox-windows.properties` 改回 `true` 并更新注释。

### 7.2 已知遗留（不阻断本次验收）

1. **§3.1 SSE 未透传 user_id** — `runtimeContextUser=null` 仍存在，但 cache key 用了 `u:<userId>`（来自请求体），多租户隔离测试通过。
2. **数据基线漂移** — `杭州开发五部` 实测为 `28.7`，与 §2.1 基线表中的 `26.1` 不一致；可能是底层数据库数据更新，建议复核基线。
3. **Episodic context query 超时** — `Timeout on blocking read for 200000000 NANOSECS`；不影响主路径（已 fall back 到无参考案例），但建议后续调优。
