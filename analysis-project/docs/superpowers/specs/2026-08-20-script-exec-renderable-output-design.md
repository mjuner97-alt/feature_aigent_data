# Script Exec Renderable Output Design

## Goal

Unify `/ai/chat` and `/v2/ai/chat` handling of `script_exec` results. The frontend receives only fenced ECharts and HTML blocks produced by the script, while the model response remains untouched.

## Approaches Considered

1. Filter in both frontends. Rejected because it duplicates parsing behavior and requires every client to implement the same rule.
2. Filter independently in both chat services. Rejected because the services could drift and both ultimately observe the same tool hook.
3. Filter once in `ToolCallTrackingHook`. Selected because it is the shared point where the completed `script_exec` output and request SSE emitter are both available.

## Behavior

- For `script_exec`, extract fenced blocks whose language is `echarts`, `echart`, `html`, or `htm` from stdout.
- If at least one supported block exists, send one `script_output` SSE event containing only those blocks in their original order.
- Do not send a `tool_output` event for `script_exec`.
- If no supported block exists, send neither `script_output` nor `tool_output` for `script_exec`.
- Keep existing `tool_output` behavior for every other tool.
- Do not inspect, remove, deduplicate, replace, or suppress any LLM token or final answer.

## Scope

The implementation changes only shared backend hook behavior and backend tests. No frontend or Skill files are changed.

## Tests

- A mixed script result exposes only its ECharts and HTML blocks.
- A script result without supported blocks produces no renderable output.
- Shared hook routing classifies `script_exec` as renderable-only for both chat endpoints.
- Non-script tools retain their existing output-event behavior.
