# [T443-done-high] Extract Missing-Mutation Retry

## Status

Done.

## Scope

T443 extracts the missing-mutation retry gate and compact retry envelope from
`AssistantTurnExecutor` into `MissingMutationRetry`.

This is an ownership refactor. It preserves runtime behavior and does not
change answer shaping, outcome dominance, static-web diagnostics,
`ToolCallRepromptStage`, read-only retries, no-tool grounding retry, or
post-tool inspect-completeness retry.

## Change

Added:

```text
dev.talos.cli.modes.MissingMutationRetry
```

`MissingMutationRetry` now owns:

- missing-mutation retry gate checks;
- action-obligation retry trace recording;
- compact retry tool-surface narrowing;
- compact retry prompt/frame/message construction;
- static verification repair-context compaction for retry;
- prior mutation request reissue selection;
- retry model call seam;
- retry tool-loop re-entry;
- denied, invalid, wrong-tool, inspection-only, and context-budget failure handling;
- mutation retry evidence merge.

`AssistantTurnExecutor` keeps compatibility wrappers and call ordering. The
executor still decides where missing-mutation retry sits relative to synthesis
retry, inspect-completeness retry, read-evidence handoff, verification phase
movement, and final answer shaping.

## Guardrails

Preserved:

- original message mutation before the retry backend call;
- separate compact backend retry message list;
- write/edit versus workspace-operation retry tool narrowing;
- static full-rewrite repair retry using only `talos.write_file`;
- retry loop re-entry for native and text-format tool calls;
- deterministic failed-action answers;
- mutation retry evidence merge ordering and counters;
- compatibility wrappers used by existing tests.

Not changed:

- `ToolCallRepromptStage` compact mutation continuation;
- exact-write context-budget fallback scope;
- read-only inspection retry;
- post-tool inspect-completeness retry;
- no-tool grounding retry;
- static-web diagnostic rendering;
- protected-read and unsupported-document answer guards.

## Tests

RED was observed before implementation:

```powershell
.\gradlew.bat test --tests "dev.talos.cli.modes.MissingMutationRetryTest" --no-daemon
```

Expected compile failure:

```text
cannot find symbol
  symbol:   variable MissingMutationRetry
```

GREEN focused verification passed after implementation:

```powershell
.\gradlew.bat test --tests "dev.talos.cli.modes.MissingMutationRetryTest" --no-daemon
```

Wider focused verification passed:

```powershell
.\gradlew.bat test --tests "dev.talos.cli.modes.MissingMutationRetryTest" --tests "dev.talos.core.llm.AssistantTurnExecutorMutationRetryToolSurfaceTest" --tests "dev.talos.cli.modes.AssistantTurnExecutorTest" --no-daemon
```

## Full Verification

Run before merge:

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```

## Next Move

After T443 is integrated, inspect the post-extraction retry/orchestration shape
before choosing T444.

Do not merge `MissingMutationRetry` with `ToolCallRepromptStage` compact
mutation continuation without a separate design decision. They share prompt
compression vocabulary, but they run in different lifecycle positions and have
different evidence and tool-surface constraints.
