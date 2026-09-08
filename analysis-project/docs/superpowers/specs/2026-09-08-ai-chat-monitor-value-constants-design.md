# AiChatMonitor `@Value` Constants Design

## Goal

Replace the three inline Spring configuration expressions in `AiChatMonitor` with named constants so the constructor annotations are easier to read and future configuration-key changes have a single edit point.

## Design

Add three `private static final String` constants directly to `AiChatMonitor`, one for each complete `@Value` expression:

- alert enabled, including its `false` default;
- alert types, including the existing timeout-type defaults;
- alert cooldown seconds, including its `300` default.

The constructor continues to use `@Value`, but each annotation references the corresponding constant. The constants remain private because no other class currently consumes these expressions. No shared configuration class or `@ConfigurationProperties` type is introduced.

## Behavior and Compatibility

This is a source-level refactor only. Property names, default values, constructor parameters, parsing, alert behavior, and Spring injection semantics remain unchanged.

## Verification

Add a focused source-structure test that fails while the expressions are inline and passes when the three constructor annotations use named constants. Then run the relevant test and the project compilation/test verification available for this module.
