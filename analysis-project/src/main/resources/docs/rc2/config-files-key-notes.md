# 配置文件关键点注意事项

> 整理 `application.properties` 与各 profile 之间的关键差异、容易踩坑的开关、以及启动前必查项。
> 适用于维护多 profile（dev / prod / sandbox-windows / sandbox-linux / sandbox-linux-remote）的场景。

---

## 1. Profile 文件清单

| 文件 | 用途 | 端口 |
|------|------|------|
| `application.properties` | 主配置（默认值，所有 profile 共享） | 默认 8080 |
| `application-dev.properties` | 开发环境 | **8081** |
| `application-prod.properties` | 生产环境 | 8080 |
| `application-sandbox-windows.properties` | Windows JVM + 远端 Linux Docker | 继承 |
| `application-sandbox-linux.properties` | Linux 本地 Docker | 继承 |
| `application-sandbox-linux-remote.properties` | Linux JVM + 远端 Docker | 继承 |

Profile 组合方式：`spring.profiles.active=dev,sandbox-windows`

---

## 2. Skill 自进化开关 — 最容易踩坑

| 配置项 | application.properties | application-dev.properties | application-sandbox-windows.properties |
|--------|------------------------|---------------------------|---------------------------------------|
| `harness.skills.auto-synth.enabled` | **true** | **true** | **false** ⚠️ |

### 关键点

- `sandbox-windows` profile 强制关闭 `auto-synth`，原因：
  - Windows JVM + 远端 Docker 拓扑下，LLM 网络延迟 + 临时文件路径差异
  - 导致蒸馏出泛化 skill（如 `query_quality_data/python_exec`），而非真实工具链
- 如果用 `dev,sandbox-windows` 组合 profile，**sandbox-windows 会覆盖主配置的 true**
- 离线消化路径（`MemoryDigestionService`）已修复并验证正确，应走此路

### 验证方法

- 用 `dev` 单独 profile：三遍同样问题应自动生成 skill ✅
- 用 `dev,sandbox-windows` 组合：不会触发自动生成（被覆盖为 false）

---

## 3. Sandbox 开关 — 决定是否走 Docker 隔离

| 配置项 | application.properties | application-dev.properties |
|--------|------------------------|---------------------------|
| `harness.a2a.sandbox.enabled` | **false** | **true** |

### 关键点

- 主配置默认关闭 sandbox（适合纯本地测试 / data_primitives 场景）
- dev profile 开启 sandbox，配合远端 Docker：
  ```properties
  harness.a2a.sandbox.remote-docker-enabled=true
  harness.a2a.sandbox.remote-docker-ssh-target=docker-host
  harness.a2a.sandbox.shared-container-name=agentscope-shared-demo
  ```
- 测 `python_exec` 必须开启 sandbox，否则 `PythonExecTool` 报错"沙箱未启用"
- 隔离范围（`isolation-scope`）：
  - `GLOBAL` = 全部用户 × 会话 × subagent 共享同一个容器
  - 配合 `shared-container-name` 使用

---

## 4. Response Cache 开关 — 影响 fingerprint 计数路径

| 配置项 | application.properties |
|--------|------------------------|
| `harness.a2a.response-cache.enabled` | **true** |

### 关键点

- Cache 开启后，第二遍问同样问题走 **HIT 路径**（`ResponseCacheHook`）
- Cache 关闭，所有请求都走 **MISS 路径**（`SkillSynthesisHook`）
- 两条路径都会 bump fingerprint，但 suffix 不同：
  - MISS 路径无维度：`userId|intent|<no-dim>`
  - HIT 路径无维度：`tenant|intent|<cache-hits>`
- **注意**：无维度问题第一遍不会写缓存（`generateCacheKey` 返回 empty），所以三遍都能走 MISS 路径计数

### 相关阈值

```properties
harness.skills.auto-synth.threshold=3   # 累计 3 次触发蒸馏
```

---

## 5. Embedding 配置 — PR3 向量检索依赖

| 配置项 | 值 |
|--------|-----|
| `harness.embedding.enabled` | **true** |
| `harness.embedding.endpoint` | `http://116.148.122.44:11434/v1/embeddings` |
| `harness.embedding.model` | `quentinz/bge-large-zh-v1.5:latest` |
| `harness.embedding.dim` | **1024** ⚠️ 必须与模型实际维度一致 |
| `harness.skills.retrieval.min-cosine` | **0.55** (bge-zh 默认；切 OpenAI 时调到 0.78~0.82) |

### 关键点

- Embedding 必开，否则 `SkillRetrievalHook` 退化为 L1-only（只靠 fingerprint 精确匹配）
- `dim` 必须严格等于模型维度，否则写入拒绝（防止两套向量空间混入同一列）
- bge-large-zh-v1.5 中文短句 cosine 区间偏低（0.55~0.78），原 0.72 阈值会 silent-miss
- 切回 OpenAI `text-embedding-3-*` 时建议调回 0.78~0.82

---

## 6. Memory MySQL Mirror — 跨副本持久化

| 配置项 | application.properties | application-dev.properties |
|--------|------------------------|---------------------------|
| `harness.a2a.memory.mysql-mirror.enabled` | **true** | **true** |
| `harness.a2a.memory.digestion.enabled` | **true** | **true** |
| `harness.a2a.memory.digestion.cron` | `0 9 21 * * *` (21:09) | 继承 |
| `harness.a2a.memory.remote.enabled` | — | **false** (sandbox-windows) |

### 关键点

- GLOBAL 共享容器模式下必须开 MySQL mirror，否则容器重启后 MEMORY.md 丢失
- `sandbox-windows` 里 `memory.remote.enabled=false`，原因：
  - `MemoryFlushHook` 把 base64 后的 MEMORY.md 内联到 `docker exec sh -c '...'`
  - Windows `CreateProcess` 命令行 8KB 上限会触发 `error=206 文件名或扩展名太长`
- MySQL mirror 兜底，关掉 remote 只是少了容器内文件副本，数据不丢

### 夜间消化 Phases

```
Phase 1: 清理 agent_memory_ledger 超过 ledger-retention-days 的行
Phase 2: 挖掘 episodic_memory 当日的工具调用轨迹 → user_trace_summary
Phase 3: 对高频失败的工具链进行技能演化
Phase 4: 将成功流程归并到该用户的 MEMORY.md
```

---

## 7. 数据源配置

### MySQL (主库 — Bean 名: `mysqlDataSource`)

| Profile | jdbc-url | 用户名 |
|---------|----------|--------|
| dev | `jdbc:mysql://116.148.122.44:3306/agentscope` | root |
| prod | `jdbc:mysql://XXXXX:3306/default_db` | root |

### 连接池调优

```properties
spring.datasource.hikari.mysql.minimum-idle=5
spring.datasource.hikari.mysql.maximum-pool-size=20
spring.datasource.hikari.mysql.idle-timeout=600000        # 10 min
spring.datasource.hikari.mysql.max-lifetime=1800000       # 30 min
spring.datasource.hikari.mysql.connection-timeout=30000   # 30 s
spring.datasource.hikari.mysql.keepalive-time=300000      # 5 min 主动 ping
spring.datasource.hikari.mysql.validation-timeout=5000
```

**关键点**：`keepalive-time=300000` 必设，避免运营商 NAT/防火墙静默断开后第一次请求触发 TCP RST + 30s 超时。

### ClickHouse / openGauss

- dev/prod 默认 `enabled=false`，需要时手动开启
- 配置项：`spring.datasource.hikari.clickhouse.enabled` / `spring.datasource.hikari.gauss.enabled`

---

## 8. 多模型实例配置

### 主对话模型

```properties
harness.a2a.model.default-model=glm-main
harness.a2a.model.instances.glm-main.provider=glm
harness.a2a.model.instances.glm-main.api-key=sk-sp-xxx
harness.a2a.model.instances.glm-main.base-url=http://103.236.76.197:11223/openai/v1
harness.a2a.model.instances.glm-main.name=glm-5.2
```

### Subagent → 模型映射

```properties
# 不在此处声明的 subagent 一律使用 default
# harness.a2a.model.subagents.code_interpreter=claude-coder
# harness.a2a.model.subagents.code_interpreter=coder   # 启用 deepseek-chat
```

**关键点**：
- `code_interpreter` 默认用 `glm-main`，可切换到 `claude-coder` 或 `coder` (DeepSeek)
- DeepSeek 代码专精，推理快 ~3x、代码正确率高，但每月需另算 token
- 详见 `docs/code-interpreter-optimization.md`

---

## 9. 启动前必查清单

### 9.1 Profile 组合

```bash
# 确认 spring.profiles.active 的值
grep spring.profiles.active application.properties
```

- `dev` → auto-synth=true, sandbox=true (dev 默认), 端口 8081
- `dev,sandbox-windows` → auto-synth=**false** (被覆盖), sandbox=true
- `prod` → 需手动填齐 MySQL/LLM 占位符

### 9.2 Sandbox 状态

测 `python_exec` 前确认：
```properties
harness.a2a.sandbox.enabled=true
harness.a2a.sandbox.shared-container-name=agentscope-shared-demo  # 远端需 docker run 好
```

### 9.3 MySQL 连通性

```bash
mysql -h <host> -P <port> -u <user> -p <db> -e "SELECT 1"
```

### 9.4 Embedding 服务

```bash
curl -s -X POST http://116.148.122.44:11434/v1/embeddings \
  -H "Content-Type: application/json" \
  -d '{"input":"test","model":"quentinz/bge-large-zh-v1.5:latest"}'
```

### 9.5 阈值复核

| 阈值 | 默认值 | 触发条件 |
|------|--------|----------|
| `auto-synth.threshold` | 3 | 同 fingerprint 累计 3 次触发蒸馏 |
| `evolution.fail-rate-evolve` | 0.3 | 失败率 >30% 且 ≥5 次触发演进 |
| `evolution.fail-rate-blacklist` | 0.6 | 失败率 >60% 且 ≥10 次拉黑 |
| `retrieval.min-cosine` | 0.55 | 向量 cosine ≥0.55 才召回 |
| `evolution.min-uses-evolve` | 5 | 触发演进的最小总次数 |
| `evolution.min-uses-blacklist` | 10 | 触发拉黑的最小总次数 |

---

## 10. 常见问题排查

### 10.1 "三遍同样问题没自动生成 skill"

1. 检查 profile：`dev,sandbox-windows` 会覆盖 `auto-synth` 为 false
2. 检查 dimKey：无维度问题需要方案 2 的 fallback（见 `no-dimension-skill-synthesis-fix.md`）
3. 检查 MySQL：`SELECT * FROM skill_candidate WHERE fingerprint LIKE '%<no-dim>'`

### 10.2 "python_exec 报沙箱未启用"

确认 `harness.a2a.sandbox.enabled=true`，且 `shared-container-name` 对应的容器在远端已 `docker run`。

### 10.3 "Skill 召回不准"

- bge-zh 短句 cosine 偏低，调 `retrieval.min-cosine` 到 0.55
- 切 OpenAI embedding 时调回 0.78~0.82
- 检查 `skill_index.embedding` 字段是否为空（嵌入失败）

### 10.4 "容器重启后 MEMORY.md 丢失"

- GLOBAL 共享容器模式下必须开 `memory.mysql-mirror.enabled=true`
- sandbox-windows 下 `memory.remote.enabled=false` 是正常的，MySQL mirror 兜底

---

## 11. 相关文档

- [skill-self-evolution-detail.md](./skill-self-evolution-detail.md) — PR2/PR3/PR4 完整设计
- [no-dimension-skill-synthesis-fix.md](./no-dimension-skill-synthesis-fix.md) — 无维度问题 fallback 方案
- [code-interpreter-optimization.md](./code-interpreter-optimization.md) — code_interpreter 模型选型
- [memory-skill-optimization-plan.md](./memory-skill-optimization-plan.md) — 记忆系统与 skill 联动
