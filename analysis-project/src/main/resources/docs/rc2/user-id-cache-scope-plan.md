# ChatRequest 引入 user_id — 让响应缓存按"用户"复用

> 背景:目前 `POST /ai/chat` 入参里没有 `user_id`,`ChatStreamServiceImpl` 构建 `RuntimeContext` 时也不会调 `.userId(...)`,导致 `ResponseCacheHook.tenantBucket()` 永远落到 `s:sessionId` 分支。结果:**同一个用户、同样的问题,只要 session_id 不同就拿不到缓存**,响应缓存沦为"单次会话内复用"。


汇总改动了哪些文档excel格式展示：

## 目标

让缓存命中范围从"同 session"扩展到"同 user",即:

- 前端为已登录用户提供稳定 `user_id`;不论开了几个会话窗口,只要问的是同一个问题,**都走缓存**。
- 未登录/匿名访问保持现状(继续退化到 `s:sessionId`,不串到别的用户)。
- 不改变缓存键算法、不改 `ResponseCacheHook`、不改 `ResponseCacheService` —— 只补"传值"这一环。

## 范围与不做的事

✅ 在做:

- `ChatRequest` 增加可选 `user_id` 字段
- `ChatStreamServiceImpl` 把 `user_id` 透传给 `RuntimeContext`
- 文档同步(README + 本文件)

🚫 不在做:

- 不动 `ResponseCacheHook.tenantBucket()` 的优先级(`u:userId > s:sessionId > _anon`)
- 不动 `ResponseCacheService.generateCacheKey()`
- 不引入鉴权/用户体系 —— `user_id` 只是缓存分桶用的标识符,**信任前端传值**
- 不强制要求 `user_id`(保持向后兼容)

## 变更文件清单

| # | 类型 | 路径 | 改动摘要 | 行数 |
|---|---|---|---|---|
| 1 | ✏️ 改造 | `dto/ChatRequest.java` | 新增 `@JsonProperty("user_id") private String userId;` 字段 | +3 |
| 2 | ✏️ 改造 | `service/impl/ChatStreamServiceImpl.java` | 构建 `RuntimeContext` 时,`isNotBlank(req.getUserId())` → `.userId(req.getUserId())` | +3 |
| 3 | ✏️ 文档 | `docs/README.md` | 在 `/ai/chat` 入参表里追加 `user_id` 行,补一条"用户级缓存复用"说明 | +5 |
| 4 | 🆕 新增 | `docs/user-id-cache-scope-plan.md` | 本文 | — |

## 入参 JSON 形态

```jsonc
{
  "input": "对比2026年1季度和2026年2季度各部门的环比变化率",
  "session_id": "any-session-id",   // 仍然可选;不传则后端生成 UUID
  "user_id": "u-1024",              // 新增;不传则与之前行为完全一致(按 session 分桶)
  "agent_id": "7",
  "agent_name": "QA助手",
  "form_type": "HXY",
  "conversation_id": null,
  "chat_id": null
}
```

> `user_id` 字段可缺省、可空字符串。后端只在 `!isBlank` 时塞进 `RuntimeContext`,所以缺省路径行为不变。

## 行为差异(用户视角)

| 场景 | 改造前 | 改造后 |
|---|---|---|
| 同 session 重复问 | 命中缓存 | 命中缓存(不变) |
| 同 user、不同 session、同问题 | **不命中**(`s:sessionA` ≠ `s:sessionB`) | **命中**(`u:u-1024`) |
| 匿名(不传 `user_id`) | 按 `s:sessionId` 分桶 | 按 `s:sessionId` 分桶(不变) |
| `user_id` 传空串 | — | 当作未传处理,按 session 分桶 |
| 两个用户问同样问题 | 各自 session 独立 | **各自 user 独立**,不会串库 |

## 关键代码差异(伪 diff,落地时以实际为准)

### `ChatRequest.java`

```diff
   @JsonProperty("session_id")
   private String sessionId;
+
+  @JsonProperty("user_id")
+  private String userId;

   @JsonProperty("agent_id")
   private String agentId;
```

### `ChatStreamServiceImpl.java`(stream 方法内)

```diff
   String sid = firstNonBlank(req.getSessionId(), resolved.conversationId);
-  final RuntimeContext ctx =
-          RuntimeContext.builder()
-                  .sessionId(sid)
-                  .sessionKey(SimpleSessionKey.of(sid))
-                  .build();
+  RuntimeContext.Builder ctxBuilder =
+          RuntimeContext.builder()
+                  .sessionId(sid)
+                  .sessionKey(SimpleSessionKey.of(sid));
+  if (isNotBlank(req.getUserId())) {
+      ctxBuilder.userId(req.getUserId());
+  }
+  final RuntimeContext ctx = ctxBuilder.build();
```

> 同时会让 `SupervisorService.ltmBucketFor(ctx)` 与 `MemoryHydrator.hydrate(userId)` 自动按真实 `userId` 分桶 —— 这是免费附赠的好处:**长期记忆也从 session 级升到 user 级**。

## 与现有功能的关联

| 系统 | 之前在 `userId == null` 时的行为 | 引入 `user_id` 后 |
|---|---|---|
| `ResponseCacheHook.tenantBucket()` | `s:<sessionId>` | `u:<userId>`(命中范围跨 session) |
| `SupervisorService.ltmBucketFor()` | `session:<sessionId>` | `user:<userId>`(长期记忆按用户分桶) |
| `SupervisorService.build()` 里的 `MemoryHydrator` | 整段 `if (ctx.getUserId() != null)` 被跳过,**根本不触发 MEMORY.md 水合** | 触发水合,首次命中时把 DB → 文件 |

⚠️ **副作用提醒**:长期记忆的桶名会从 `session:xxx` 切到 `user:xxx`。**前端首次为某个 user 传 `user_id` 时,之前 session 桶里的记忆不会自动迁移**,相当于"新用户冷启动"。如不希望这种切换,需在 PR 中加一段一次性迁移逻辑 —— 默认不做。

## 测试计划

| 用例 | 步骤 | 预期 |
|---|---|---|
| T1 向后兼容 | 不传 `user_id`,同 session 连发两次同问题 | 第二次命中 |
| T2 跨 session 命中 | 同 `user_id=u-1024`,session_id 各换一次,问同样问题 | 第二次命中,日志 `Cache HIT for /ai/chat` |
| T3 用户隔离 | `user_id=u-1024` 问完后,换 `user_id=u-2048` 问同问题 | 不命中(各自独立) |
| T4 匿名隔离 | 不传 `user_id`,两个不同 session 问同问题 | 不命中(回归现状) |
| T5 空串处理 | `"user_id": ""` 当作未传 | 行为与 T4 一致 |

## 回滚

```bash
git revert <commit>
```

回滚后:`ChatRequest` 不再认 `user_id`,所有请求自动退回到 `s:sessionId` 分桶 —— 老缓存键仍可用,无需清表。

## 落地步骤建议

1. 改 `ChatRequest.java`(加字段 + Lombok 自动生成 getter)
2. 改 `ChatStreamServiceImpl.stream()`(条件分支注入 userId)
3. 更新 `docs/README.md` 入参表
4. 走 T1~T5 五条用例验证
5. 提交并合并
