# Multiple recipient lookup

Approved behavior: split names and IDs on commas, semicolons, whitespace and Chinese separators. Search merges candidates by userId. Batch adds exact IDs or unique full names only; ambiguous names remain selectable. Report missing and failed terms independently. Keep persisted recipient IDs unchanged.

1. Add regression tests for mixed input, deduplication, ambiguous names and partial failures.
2. Implement a shared lookup helper with at most eight concurrent requests.
3. Wire long-task recipient picker, notification settings and independent-task search to the helper; add feedback and department labels.
4. Run lookup and existing recipient tests, build frontend, inspect diff.
