# ADR-7.2: BotLoopGuard and IdempotencyStore Are Channel-Level, Not Applicable to A2A Runner

> Date: 2026-07-14
> Status: Accepted
> Context: Stage 7 upgrade from RC2 to RC5

## Decision

**BotLoopGuard and IdempotencyStore are NOT migrated to v2 and are deferred to Stage 8+.**

## Rationale

### BotLoopGuard

`BotLoopGuard` (`io.agentscope.extensions.channel.common.BotLoopGuard`) is a per-peer sliding-window throttle for **inbound channel events**. It:

- Tracks events per peer key (e.g., WeChat user ID, Feishu user ID)
- Trips into a 60-second cooldown when a peer exceeds 20 events per 60-second window
- Is used exclusively in **Channel constructors**: `WeComChannel`, `FeishuChannel`, `DingTalkChannel`, `GitHubChannel`, `GitLabChannel`

The A2A runner uses `HarnessAgent.streamEvents()` directly — it does not receive inbound webhook callbacks from chat platforms. There is no "peer" to throttle and no bot-to-bot loop risk from inbound events.

### IdempotencyStore

`IdempotencyStore` (`io.agentscope.extensions.channel.common.IdempotencyStore`) is a bounded per-channel dedup store for **inbound webhook event IDs**. It:

- Returns `true` on first sight of an event ID, `false` on duplicates
- Uses TTL-based eviction (5 min default, 10k entries)
- Is used exclusively in the same **Channel classes** for deduplication of platform webhook retries

The A2A runner makes direct gRPC/HTTP calls and does not receive retried webhook payloads.

### HarnessAgent.Builder API

Neither `BotLoopGuard` nor `IdempotencyStore` appears in the `HarnessAgent.Builder` API. There are no `.botLoopGuard()` or `.idempotencyStore()` builder methods. They are purely **Channel ingress plumbing**.

## Consequences

- If webhook integration is needed in future, create a Channel adapter class (e.g., `A2aJsonRpcChannel`) that wraps `BotLoopGuard` and `IdempotencyStore`
- The v2 runner remains focused on direct `streamEvents()` invocation without Channel-level concerns
- No changes needed to `HarnessA2aRunnerV2` or any v2 config class

## References

- `io.agentscope.extensions.channel.common.BotLoopGuard`
- `io.agentscope.extensions.channel.common.IdempotencyStore`
- `WeComChannel`, `FeishuChannel`, `DingTalkChannel`, `GitHubChannel`, `GitLabChannel` — all Channel constructors