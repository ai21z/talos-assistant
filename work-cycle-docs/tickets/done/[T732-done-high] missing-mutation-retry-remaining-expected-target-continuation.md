# T732 - Missing Mutation Retry Remaining Expected Target Continuation

Status: done
Priority: high
Created: 2026-06-08

## Summary

The post-T731 synchronized approval rerun found a GPT-OSS failure in `t325-python-command-boundary`.

This is not the original T730 source-evidence-before-read failure. GPT-OSS correctly inspected `problem.md`, then initially produced no write/edit call. Talos fired the missing-mutation retry. In that retry, GPT-OSS wrote `dijkstra.py` but did not write the second required target, `test_dijkstra.py`. Runtime stopped after the retry mutation and verification failed because the expected target was missing.

## Evidence

Audit root:

```text
local/manual-testing/current-0.10.0-post-t731-sync-20260608-170218/artifacts/gptoss/sync-approval/t325-python-command-boundary
```

Key facts:

- `audit-transcript.json`
  - `traceStatus: FAILED`
  - `verificationStatus: FAILED`
  - `verificationSummary: test_dijkstra.py: expected target was not successfully mutated.`
  - `approvalCount: 1`
- `traces/last-trace.txt`
  - `talos.list_dir` succeeded.
  - `talos.read_file problem.md` succeeded.
  - `ACTION_OBLIGATION_EVALUATED ... model response had no write/edit tool calls`.
  - retry wrote `dijkstra.py`.
  - `VERIFICATION_COMPLETED status=FAILED`.
- final workspace contains `problem.md` and `dijkstra.py`, but no `test_dijkstra.py`.

Relevant code path:

```text
src/main/java/dev/talos/cli/modes/MissingMutationRetry.java
src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java
src/main/java/dev/talos/runtime/toolcall/ToolRepromptSuccessfulMutationDecision.java
src/main/java/dev/talos/runtime/toolcall/ExpectedTargetProgressAccounting.java
```

## Why It Matters

This is a release-gate convergence bug. A required exact target can remain unwritten after a successful missing-mutation retry, and the runtime only catches it at final verification. That is honest failure, but it leaves a model-sensitive lane failing instead of continuing the bounded expected-target repair path.

## Acceptance Criteria

- If a missing-mutation retry performs some successful mutations but leaves required expected targets unsatisfied, Talos continues once with a bounded expected-target continuation for the remaining target(s).
- The continuation prompt names the remaining exact target(s), preserves the current request, and uses only the appropriate write/edit tools.
- The continuation does not re-read unrelated files or expand scope.
- If the remaining target continuation succeeds, final verification/readback reflects all required targets.
- If it fails, the final answer remains an honest verification failure.
- Deterministic tests reproduce the GPT-OSS shape: initial read-only/no-write response, missing-mutation retry writes only `dijkstra.py`, continuation writes `test_dijkstra.py`.

## Suggested Tests

- Add a focused `AssistantTurnExecutor` or `MissingMutationRetry` test with scripted responses:
  1. read/list source;
  2. no mutation response triggers missing-mutation retry;
  3. retry writes only one of two expected targets;
  4. runtime continuation prompts for the remaining target;
  5. final workspace contains both files.
- Add a negative test where continuation still omits the target and verification remains failed.

## Current Evidence Status

Done on 2026-06-08.

Implementation:

- `MissingMutationRetry` now detects remaining expected targets after a successful but partial missing-mutation retry.
- It sends one compact continuation prompt that names only the remaining exact target(s) and preserves the original current request.
- The continuation executes through the normal `ToolCallLoop` with a mutable message list, so native tool-call result messages can be appended safely.
- Merged loop evidence now preserves read paths, tool outcomes, and read-file bodies from both retry phases.

Regression evidence:

```powershell
.\gradlew.bat test --tests 'dev.talos.cli.modes.AssistantTurnExecutorTest$SynthesisRetryTests' --no-daemon
```

The focused regression reproduces the GPT-OSS shape: initial source read, no mutation, missing-mutation retry writing only `dijkstra.py`, then a remaining-target continuation that writes `test_dijkstra.py`.

Post-fix synchronized approval evidence:

```text
local/manual-testing/current-0.10.0-post-t733-sync-20260608-174534
```

In the GPT-OSS `t325-python-command-boundary` lane, the final workspace contains nonblank `dijkstra.py` and `test_dijkstra.py`; the summary scores the row as `PASS_WITH_READBACK_ONLY_LIMITATION` because command execution remains intentionally unavailable.
