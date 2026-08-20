# Script Exec Renderable Output Design

## Goal

Restrict `/v2/ai/chat` handling of `script_exec` results. Its frontend receives only fenced ECharts and HTML blocks produced by the script, while the model response remains untouched. `/ai/chat` remains unchanged.

## Approaches Considered

1. Filter in both frontends. Rejected because it duplicates parsing behavior and requires every client to implement the same rule.
2. Filter independently in both chat services. Rejected because the services could drift and both ultimately observe the same tool hook.
3. Filter once in `ToolCallTrackingHook`. Selected because it is the shared point where the completed `script_exec` output and request SSE emitter are both available.

## Behavior

- For `script_exec`, extract fenced blocks whose language is `echarts`, `echart`, `html`, or `htm` from stdout.
- If at least one supported block exists, send one `script_output` SSE event containing only those blocks in their original order.
- Do not send a `tool_output` event for `script_exec`.
- If no supported block exists, send neither `script_output` nor `tool_output` for `script_exec`.
- Keep existing `tool_output` behavior for every other `/v2/ai/chat` tool.
- Do not inspect, remove, deduplicate, replace, or suppress any LLM token or final answer.
- Do not add tool-output events or answer processing to `/ai/chat`.

## Scope

The implementation changes only backend hook behavior used by `/v2/ai/chat` and backend tests. No frontend, Skill, or `/ai/chat` service files are changed.

## Tests

- A mixed script result exposes only its ECharts and HTML blocks.
- A script result without supported blocks produces no renderable output.
- The hook routes `script_exec` through renderable-block extraction on `/v2/ai/chat`.
- Non-script `/v2/ai/chat` tools retain their existing output-event behavior.
