# 中等方案：跳过 LLM 驱动的 Memory consolidation/flush,保留文件管理

> 路径：`src/main/resources/docs/Plan-Machie/memory-middleware-middle-ground.md`
> 关联：`plan-notebook-frontend-design.md`（前端测速场景）；`docs/rc2-to-rc5/interactive-pause-resume-plan.md`（interrupt 端点）
> 日期：2026-07-21

## 背景与动机

### 观察到的延迟
2026-07-20 日志时间轴（DeepSeek 主模型，单轮 "你好"）：

```
09:07:36.717  POST_REASONING        ← DeepSeek 输出完毕
09:07:39.455  MemoryMaintenanceMiddleware: Running memory maintenance
09:07:45.293  OpenAI API call (consolidation LLM #1)
09:07:56.097  SkillVectorIndex cache refreshed (+10.8s)
09:07:57.056  MEMORY.md consolidated (2550 chars)
09:07:58.625  Memory maintenance completed  ← 19s
09:08:00.807  OpenAI API call (flush LLM)
09:08:03.995  Memory flush completed
09:08:06.672  Offloaded 2 messages
09:08:08.809  POST_CALL              ← 8s (MemoryFlushMiddleware)
                                    ──────
                                    32s 总延迟
```

### 三种方案回顾

| 方案 | 动作 | 代价 |
|---|---|---|
| **轻** | `consolidationMinGap(Duration.ofDays(365))` 节流跳过 consolidation | 仅省 19s；flush 仍每次跑 LLM 省 0 |
| **中（本方案）** | 轻方案 + `flushTrigger(FlushTrigger.never())` | 省 27s；MEMORY.md 停更 + flush 摘要停跑,offload 仍保留 |
| **重** | `HarnessA2aRunnerV2` 不调 `.memory(MemoryConfig)` | 两个 middleware 整体不挂；context 无 offload,长对话易爆 |

本方案是中:既让单轮响应快(~5s),又不丢框架的文件管理能力(归档/prune/offload)。

## 框架源码核实(agentscope-harness-2.0.0-RC5-sources.jar)

### `MemoryConfig.java` 可调旋钮
```java
// MemoryConfig.java (extracted)
public static final Duration DEFAULT_CONSOLIDATION_MIN_GAP = Duration.ofMinutes(30);

public enum FlushMode {
    ALWAYS,   // 每次调用都 flush (默认)
    NEVER,    // 不调 flush LLM (offload 仍跑)
    THROTTLED // 按 minGap 节流
}

public static final class FlushTrigger {
    public static FlushTrigger never() { return NEVER_INSTANCE; }
    public static FlushTrigger throttled(Duration minGap) { ... }
}

private final Duration consolidationMinGap;       // 控制 MemoryMaintenanceMiddleware 节流
private final FlushTrigger flushTrigger;         // 控制 MemoryFlushMiddleware 是否调 flush LLM
```

### `MemoryMaintenanceMiddleware.java` 关键路径
```java
// line 155-170
private void maybeRunMaintenance(RuntimeContext rc) {
    Instant now = Instant.now();
    AtomicReference<Instant> ref = lastRunAtFor(rc);
    Instant last = ref.get();
    if (Duration.between(last, now).compareTo(minGap) < 0) {
        return;   // ← 节流命中,直接返回,不跑任何 maintenance
    }
    ...
    runMaintenance(rc);
}

private void runMaintenance(RuntimeContext rc) {
    expireDailyFiles(rc);       // ← 纯文件操作 (move 旧 daily file 到 archive/)
    consolidateMemory(rc);     // ← 调 LLM consolidate(rc).block()
    pruneOldSessions(rc);      // ← 纯文件操作 (delete 旧 session jsonl)
}

private void consolidateMemory(RuntimeContext rc) {
    if (consolidator == null) return;   // ← 但 consolidator 由 MemoryConfig.model 构造,null model 会回退到主模型,不是 null
    consolidator.consolidate(rc).block();
}
```

**关键**: `minGap=365 days` 后,`maybeRunMaintenance` 在 line 159 直接 `return`,**连 `expireDailyFiles` 和 `pruneOldSessions` 都不跑**——这两个无 LLM 的文件清理也被跳过。

权衡:测速场景下 daily 文件归档延迟一年无所谓(session jsonl 短期不会爆 90 天阈值),所以可接受。

### `MemoryFlushMiddleware.java` 关键路径
```java
// line 140-156
public Flux<AgentEvent> onAgent(...) {
    return next.apply(input)
            .concatWith(Mono.defer(() -> doFlush(agent, rc))
                    .subscribeOn(Schedulers.boundedElastic())
                    ...);
}

private Mono<Void> doFlush(Agent agent, RuntimeContext rc) {
    ...
    boolean shouldFlush = shouldFlushNow(rc);
    Mono<Void> flushMono;
    if (shouldFlush) {
        flushMono = flushManager.flushMemories(rc, messages)...;  // ← LLM 调用
    } else {
        flushMono = Mono.empty();   // ← FlushTrigger.never() 走这里
    }
    Mono<Void> offloadMono = Mono.fromRunnable(() -> flushManager.offloadMessages(...));
    return flushMono.then(offloadMono);   // ← offload 无论如何都跑
}

boolean shouldFlushNow(RuntimeContext rc) {
    switch (flushTrigger.mode()) {
        case NEVER:  return false;   // ← FlushTrigger.never() 走这里
        case ALWAYS: return true;
        ...
    }
}
```

**关键**: `FlushTrigger.never()` 让 `flushMemories` 跳过(LLM 调用省掉),但 `offloadMessages` 仍执行——session jsonl 继续追加,`SessionSearchTool` 和 resumption 不受影响。

## 设计

### 改动点:唯一一个

`HarnessA2aRunnerV2.java` 第 138-140 行:

```java
// === BEFORE ===
.memory(MemoryConfig.builder()
        .model(smallModel)
        .build())

// === AFTER ===
.memory(MemoryConfig.builder()
        .model(smallModel)
        // 中等方案:测速期跳过 LLM 驱动的 consolidation + flush
        //   - consolidationMinGap=365d: maybeRunMaintenance 节流命中直接 return,
        //     19s 的 MEMORY.md 合并 LLM 调用被跳过 (连带 expireDailyFiles/pruneOldSessions 也跳)
        //   - flushTrigger=never: flushMemories 跳过 LLM 调用,
        //     但 offloadMessages 仍跑 (session jsonl 保持完整)
        // 代价:MEMORY.md 停更;daily file 不归档 (短期可接受,夜间 cron Phase 4 仍会跑)
        // 关闭:把这两行删掉即恢复默认
        .consolidationMinGap(Duration.ofDays(365))
        .flushTrigger(MemoryConfig.FlushTrigger.never())
        .build())
```

需要新增 import:
```java
import java.time.Duration;
// MemoryConfig 已 import (line 34)
```

### 不改动项

| 不改 | 原因 |
|---|---|
| 不传 `consolidator=null` | 框架不暴露此构造路径,`MemoryConfig.model=null` 会回退到主模型而非 null consolidator |
| 不删 `.memory(...)` 整段 | 会同时移除 `MemoryFlushMiddleware.offloadMessages`,长对话 context 无 offload 会爆 |
| 不改 `application.properties` | 此开关是代码级,不是 properties 级;框架未暴露 `MemoryConfig` 的 properties 绑定 |
| 不改夜间 cron digestion | `harness.a2a.memory.digestion.enabled=true` 的 21:09 job 独立,Phase 4 仍会把 episodic 挖到 MEMORY.md,白天 LLM 合并停了但夜间补上 |
| 不改 SkillEvolution | 已验证 `SkillEvolutionRunner.dispatchEvolve()` 用 `Schedulers.boundedElastic()`,纯异步,不阻塞主链路 (日志里 PostCall 只 0.4s) |

### 为什么不通过 application.properties 控制

查 `HarnessA2aRunnerV2` 第 138 行:`.memory(MemoryConfig.builder().model(smallModel).build())` 是硬编码构造,框架没有把 `MemoryConfig` 暴露为 Spring `@ConfigurationProperties`。要做 properties 化需要:

1. 新建 `MemoryMiddlewareProperties` `@ConfigurationProperties(prefix="harness.a2a.memory.middleware")`
2. `HarnessA2aRunnerV2` 注入它,按 fields 构造 `MemoryConfig`
3. 加 4 个属性:`consolidation-min-gap` / `flush-mode` / `flush-min-gap` / `enabled`

**本方案不做**:测速是临时需求,加 properties 增加配置面面积不划算。需要的话见本文档"后续升级"章节。

## 预期效果

### 时序对比

| 阶段 | 改前 | 改后 (中等方案) |
|---|---|---|
| POST_REASONING → MemoryMaintenanceMiddleware | ~3s 启动 + 19s 跑 | ~0.01s (节流命中直接 return) |
| MemoryFlushMiddleware flush LLM | ~3s | 0 (FlushMode.NEVER) |
| MemoryFlushMiddleware offload | ~5s | ~5s (仍跑) |
| **POST_CALL 总延迟** | **~32s** | **~5s** |
| 单轮 "你好" 端到端 | 55.7s | ~28s (省 27s) |

### 功能性损失清单

| 功能 | 状态 | 影响 |
|---|---|---|
| `MEMORY.md` 白天 LLM 合并 | 停 | 21:09 cron Phase 4 补上(夜间批处理) |
| `memory/YYYY-MM-DD.md` flush 摘要 | 停 | episodic_memory 表(MySQL 镜像)仍由 `MemoryLedgerMirrorMiddleware` 写,不依赖此 flush |
| `memory/archive/` 归档 (>90 天) | 停 | 测速期不会产生超过 90 天的文件,无影响 |
| `agents/*.log.jsonl` prune (>180 天) | 停 | 同上 |
| `offloadMessages` (session jsonl 追加) | 跑 | `SessionSearchTool` 和 resumption 不受影响 |
| 跨 session MEMORY.md 召回 | 读仍可用 | 白天只读不写,夜间批处理后次日才更新 |

### 不受影响的项

- `SkillEvolutionHook` (异步,独立)
- `SkillSynthesisHook` / `SkillRetrievalHook` (走 cache/vector,不走 memory)
- `MemoryLedgerMirrorMiddleware` (项目自己的 bean,独立于框架 memory)
- `MysqlAgentStateStore` (session state 持久化,独立)
- `CompactionMiddleware` (context 压缩,由 `CompactionConfig` 控制,不由 `MemoryConfig` 控制)
- 夜间 digestion 4 个 phase (Phase 1-4 由 `MemoryDigestionJob` 调度,不读 `MemoryConfig`)

## 验证步骤

### 1. 编译
```bash
cd D:/AILLMS/javacode/analysis-project/analysis-project
mvn clean package -Dmaven.test.skip=true 2>&1 | tail -5
# 预期: BUILD SUCCESS
```

### 2. 启动 + 单轮 "你好" 测速
```bash
cmd.exe //c "taskkill /F /IM java.exe" 2>/dev/null

DOCKER_API_VERSION=1.46 DOCKER_HOST=ssh://root@116.148.120.160 \
  nohup G:/jdk21/bin/java.exe -jar target/analysis-project-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=sandbox-windows,dev > /tmp/app-mem-middle.log 2>&1 &
sleep 25

curl -sN -X POST http://localhost:8081/v2/ai/chat \
  -H 'Content-Type: application/json' \
  --data-binary '{"input":"你好","conversationId":"mem-middle-001","user_id":"mem_tester"}' \
  > /tmp/mem-middle-chat.txt 2>&1

# 计时:从 POST 到 done
grep -E "POST_REASONING|POST_CALL|Memory maintenance|Memory flush|Offloaded" /tmp/app-mem-middle.log \
  | tail -20
```

### 3. 预期日志(关键校验)

**通过条件** (3 项必须全部满足):

```bash
# A. MemoryMaintenanceMiddleware 不跑 maintenance (节流命中)
grep -c "Running memory maintenance" /tmp/app-mem-middle.log
# 预期: 0  (改前是 1)

# B. MemoryFlushMiddleware 不调 flush LLM
grep -c "Memory flush completed" /tmp/app-mem-middle.log
# 预期: 0  (改前是 1)

# C. offload 仍跑
grep -c "Offloaded" /tmp/app-mem-middle.log
# 预期: ≥1 (改前是 1)
```

### 4. 延迟校验

```bash
# 从 POST_REASONING 到 POST_CALL 的间隔
POST_REASONING_TS=$(grep "POST_REASONING" /tmp/app-mem-middle.log | tail -1 | awk '{print $1}')
POST_CALL_TS=$(grep "POST_CALL" /tmp/app-mem-middle.log | tail -1 | awk '{print $1}')
echo "POST_REASONING: $POST_REASONING_TS"
echo "POST_CALL: $POST_CALL_TS"
# 预期: 间隔 < 8s (改前 32s)
```

### 5. 回归校验

```bash
# A. SSE 流仍以 done 结尾
grep -c "^event:done$" /tmp/mem-middle-chat.txt
# 预期: 1

# B. MEMORY.md 仍存在(读路径未断)
ls -la .agentscope/workspace/harness-a2a/memory/MEMORY.md
# 预期: 文件存在 (内容可能不更新,但读路径不受影响)

# C. 夜间 digestion 仍可启动 (cron 配置未动)
grep "harness.a2a.memory.digestion.enabled" src/main/resources/application.properties
# 预期: =true (未改)
```

## 回滚

改回只需把 `HarnessA2aRunnerV2.java` 第 138-140 行改回:
```java
.memory(MemoryConfig.builder()
        .model(smallModel)
        .build())
```
两行新增的 `.consolidationMinGap(...)` / `.flushTrigger(...)` 删掉即恢复默认。

## 后续升级(可选,非本方案范围)

如果测速期结束后想把此开关做成 properties 化(生产可调):

### 新增 `MemoryMiddlewareProperties.java`
```java
@Data
@ConfigurationProperties(prefix = "harness.a2a.memory.middleware")
public class MemoryMiddlewareProperties {
    private boolean enabled = true;
    private Duration consolidationMinGap = Duration.ofMinutes(30);
    private FlushMode flushMode = FlushMode.ALWAYS;
    private Duration flushMinGap = Duration.ZERO;

    public enum FlushMode { ALWAYS, NEVER, THROTTLED }
}
```

### `HarnessA2aRunnerV2` 改造
```java
@Autowired
private MemoryMiddlewareProperties memProps;

// in build():
if (memProps.isEnabled()) {
    MemoryConfig.Builder b = MemoryConfig.builder().model(smallModel)
            .consolidationMinGap(memProps.getConsolidationMinGap());
    switch (memProps.getFlushMode()) {
        case NEVER -> b.flushTrigger(MemoryConfig.FlushTrigger.never());
        case THROTTLED -> b.flushTrigger(
            MemoryConfig.FlushTrigger.throttled(memProps.getFlushMinGap()));
        default -> b.flushTrigger(MemoryConfig.FlushTrigger.always());
    }
    builder.memory(b.build());
}
// else: 不调 .memory(),两个 middleware 都不挂 (重方案)
```

### `application.properties` 新增
```properties
# 中等方案默认关 (生产)
harness.a2a.memory.middleware.enabled=true
harness.a2a.memory.middleware.consolidation-min-gap=30m
harness.a2a.memory.middleware.flush-mode=ALWAYS

# 测速期临时打开:
# harness.a2a.memory.middleware.consolidation-min-gap=365d
# harness.a2a.memory.middleware.flush-mode=NEVER
```

此升级单独立项,不在本次改动内。

## 关联记忆

- [[interrupt_resume_endpoint_verified]] - interrupt 端点已验证;本方案不影响 interrupt 流程(interrupt 触发的 recovery msg 不经 memory middleware)
- [[async_tool_middleware_wiring]] - AsyncToolMiddleware 30s offload 与 memory middleware 并行;两者独立
- [[plan_state_cross_restart_verified]] - plan_mode_context 持久化依赖 MysqlAgentStateStore,与 MemoryConfig 无关
- [[daily_file_local_write_fix]] - MemoryLedgerMirrorMiddleware 写 daily jsonl 的路径独立于 MemoryFlushMiddleware,本方案不影响