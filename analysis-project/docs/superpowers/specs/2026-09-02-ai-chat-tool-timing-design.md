# `/ai/chat` Tool Timing Design

## Goal

Record accurate tool-call start time, end time, and duration for new `/ai/chat`
trace events stored in ClickHouse, and display those values in conversation history.
Existing trace rows are not backfilled or inferred.

## Current Behavior

`AiChatRestToolCallTrackingToDbHook` emits separate `PRE_ACTING` and
`POST_ACTING` events. Each event contains only its own `createdAt` value.
`TraceBatchWriter` copies `createdAt` to `trace_event.timestamp`, but always
writes `0` to `trace_event.duration_ms`. The history page consequently shows
event timestamps but cannot show a tool-call interval or duration.

## Design

### Request-scoped timing state

`TraceSession` owns a thread-safe map of active tool calls. The key is the
framework `tool_use.id`, never the tool name, because the same tool may be
called repeatedly or concurrently. Each value stores the start `Instant`.

The session exposes narrowly scoped operations to start and finish a tool
timing. Finishing removes the entry and returns an immutable timing value with
`startedAt`, `endedAt`, and non-negative `durationMs`. A missing or blank tool
call ID is not tracked.

### Hook event payloads

When handling `PRE_ACTING`, the trace hook obtains the tool call ID, records the
start time in `TraceSession`, and adds `startedAt` to the event JSON. The
existing `createdAt` field remains unchanged and is equal to the start event's
capture time.

When handling `POST_ACTING`, the hook finishes the matching timing by tool call
ID. When a matching start exists, the event JSON includes:

- `startedAt`: ISO-8601 UTC timestamp
- `endedAt`: ISO-8601 UTC timestamp
- `durationMs`: elapsed milliseconds

`createdAt` remains the end event's capture time. If no matching start exists,
the event is still recorded with its existing payload, but the timing fields
are omitted. This avoids inventing data.

Timing uses a monotonic elapsed clock for `durationMs` and a wall clock for the
display timestamps. This prevents wall-clock adjustments from producing a
negative or inaccurate duration.

### ClickHouse persistence

`TraceBatchWriter` reads an event JSON's numeric `durationMs` and writes it to
`trace_event.duration_ms`. Events without that field continue to write `0`,
preserving compatibility with non-tool events and old payloads. The existing
ClickHouse schema already provides the required `UInt32 duration_ms` column,
so no migration is required. Values larger than `UInt32` are capped rather
than overflowing.

The two event rows retain their existing timestamps. The completed
`POST_ACTING` row is the authoritative row for tool duration and SQL-based
latency analysis.

### History API and UI

The history API continues returning raw `event_json` strings. No response
contract change is required because the new timing fields are part of those
JSON objects.

`SessionDetailPage` pairs `PRE_ACTING` and `POST_ACTING` by `tool_use.id` for
new records and renders one tool-call step instead of separate call and output
steps. The step contains the input and output and displays start time, end
time, and formatted duration. Pairing preserves event order.

Old events without the explicit timing fields are not used to infer duration.
They retain the current separate call/output presentation, with no fabricated
timing value. An unmatched new `PRE_ACTING` event remains visible with an
unknown end time and duration.

## Error Handling

- A blank tool call ID disables pairing for that call but does not drop trace
  events.
- A `POST_ACTING` event without a recorded start is persisted without timing
  fields.
- An unfinished call remains in request-scoped memory only until the request
  session is released.
- Trace timing failures remain observational and must not fail `/ai/chat`.

## Tests

Backend tests cover:

- a normal `PRE_ACTING`/`POST_ACTING` pair;
- repeated or concurrent calls with the same tool name and different IDs;
- an unmatched completion event;
- persistence of `durationMs` and the zero fallback;
- `UInt32` capping.

Frontend tests cover:

- merging a new-format pair by tool call ID;
- rendering start, end, and duration;
- leaving old-format events in their current separate representation;
- rendering an unmatched start without an invented duration.

## Non-goals

- Backfilling or inferring timing for existing ClickHouse rows.
- Changing `/v2/ai/chat` tracing.
- Adding new ClickHouse columns or tables.
- Changing SSE events emitted to the chat page.
