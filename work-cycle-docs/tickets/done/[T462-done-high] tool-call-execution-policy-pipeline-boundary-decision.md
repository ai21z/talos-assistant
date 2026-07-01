# [T462-done-high] ToolCallExecutionStage Policy Pipeline Boundary Decision

## Status

Done.

## Scope

T462 inspects `ToolCallExecutionStage` as the next hygiene lane after the
`ToolCallRepromptStage` lane was closed.

This is a no-code decision ticket. It does not change runtime behavior,
approval behavior, protected/private read handling, source-evidence behavior,
append-line behavior, mutation evidence, context ledger capture, trace
wording, tool-result wording, or final outcome rendering.

## Snapshot

Measured from fresh `origin/v0.9.0-beta-dev` at `cc23729b`.

| Item | Measurement |
|---|---:|
| Candidate version | `talosVersion=0.9.9` |
| Java version | `javaVersion=21` |
| `ToolCallExecutionStage.java` | 1107 lines |
| Architecture baseline | 0 |

## Source Shape

`ToolCallExecutionStage.execute(...)` owns a dense execution pipeline:

1. path alias normalization;
2. workspace operation planning and path hinting;
3. deterministic pre-approval guard rails;
4. actual tool execution through `TurnProcessor.executeTool(...)`;
5. protected/private content model-context containment;
6. context ledger capture;
7. read and mutation state updates;
8. denied/path-policy/unsupported-read classification;
9. mutation evidence capture;
10. post-result edit-failure recovery state.

This is real policy density, but it is not one implementation ticket.

## Responsibility Inventory

| Responsibility | Current source | Classification |
|---|---|---|
| Protected alias normalization | `ProtectedPathAliasNormalizer.canonicalizeExpectedProtectedAliases(...)` in `execute(...)` | Pre-execution path normalization; keep local until path-policy pipeline is designed. |
| Full-rewrite repair edit blocking | `fullRewriteRepairRequiredDiagnostic(...)` and early `talos.edit_file` block | Pre-execution deterministic guard tied to static-web repair state. |
| Stale edit reread block | `staleRereadRequiredPaths(...)`, `staleEditRereadRequiredDiagnostic(...)` | Pre-execution deterministic guard tied to same-turn read/mutation state. |
| Duplicate failing edit suppression | `failedCallSignatures`, empty-edit diagnostics | Pre-execution duplicate-failure guard tied to retry counters. |
| Redundant read suppression | `successfulReadCalls` read signature block | Read-only loop hygiene, not mutation guard behavior. |
| Source-derived write-before-read block | `missingSourceEvidenceTargets(...)`, `sourceEvidenceRequiredDiagnostic(...)` | Pre-execution source-evidence guard, but it spans source-read capture and source-derived task contracts. |
| Source-evidence exact coverage | `SourceDerivedEvidenceGuard.exactEvidenceCoverageDiagnostic(...)` and repair/block branch | Pre-execution source-evidence guard with call replacement semantics. |
| Append-line preservation block | `appendLinePreApprovalDiagnostic(...)` and helper methods | Pre-execution append-line guard; smallest clean implementation owner. |
| Protected/private read handoff | `isSuccessfulProtectedRead(...)`, private document approval, withheld results | Post-result content-safety pipeline. Do not mix with pre-execution guards. |
| Context ledger capture | `recordContextLedgerDecision(...)` | Post-result evidence/accounting. Keep separate from guard extraction. |
| Read/mutation tracking | `recordSuccessfulRead(...)`, `recordMutationSuccess(...)` | Loop state accounting. Keep in stage for now. |
| Mutation evidence | `mutationEvidence(...)` | Outcome/verifier evidence; do not move in first execution-lane ticket. |
| Static-web full rewrite recovery | `shouldRecoverStaticWebEditFailureWithFullRewrite(...)` and `recordStaticWebFullRewriteRequired(...)` | Post-result repair state; coupled to verifier/repair context. |

## Decision

Do not extract a broad `ToolCallExecutionPolicy` or
`PreExecutionToolGuardPipeline` yet.

The first implementation ticket should extract only append-line pre-approval
preservation into a dedicated owner:

```text
[T463] Extract append-line pre-approval guard
```

Target owner:

```text
dev.talos.runtime.toolcall.AppendLinePreApprovalGuard
```

Preferred shape:

```text
AppendLinePreApprovalGuard.diagnostic(
    ToolCall call,
    LoopState state,
    TaskContract contract,
    String pathHint
)
```

The owner should return the exact diagnostic string or `null`, matching the
current behavior.

`ToolCallExecutionStage` should keep lifecycle and side effects:

- incrementing `failedCalls`;
- incrementing `failuresThisIter`;
- calling `recordFailure(...)`;
- creating `ToolResult.fail(...)`;
- emitting the tool result;
- recording `APPEND_LINE_WRITE_PRESERVATION`;
- adding the failed `ToolOutcome`;
- appending the formatted tool-result message;
- deciding `continue`.

## Why Append-Line First

Append-line preservation is the cleanest first execution-lane implementation
because:

- it runs before approval;
- it does not call `TurnProcessor.executeTool(...)`;
- it does not require protected/private content handoff;
- it does not mutate the tool call;
- it does not write context ledger entries;
- it already has focused behavior coverage proving no approval is requested
  for invalid writes;
- it directly pairs with the existing
  `TargetReadbackCompactRepairPlanner` append-line compact repair owner;
- it extracts a real policy owner without hiding execution-stage ordering.

## Rejected Immediate Implementations

### Broad pre-execution guard pipeline

Rejected for T463.

Too many policies would move at once: full-rewrite repair, stale edit,
duplicate edit, redundant read, source evidence, append-line preservation, and
path normalization. That would make ordering regressions hard to diagnose.

### Source-derived write guard first

Rejected for the first implementation ticket, not rejected as a future lane.

The source-evidence branch is coherent but heavier:

- it has both before-read blocking and exact-evidence coverage repair/blocking;
- one branch can replace the effective `ToolCall`;
- it records source-evidence action obligations;
- it uses `TurnSourceEvidenceCapture`, task contract source targets, and
  `SourceDerivedEvidenceGuard`;
- it should follow after the first smaller pre-approval guard extraction proves
  the execution-stage extraction style.

### Protected/private read handoff

Rejected for this lane start.

That is post-result content-safety behavior. It depends on actual
`ToolResult`, private-document approval prompts, model-context preservation,
withheld local result text, and context ledger decisions. It should be its own
decision ticket, not mixed with pre-approval guards.

### Mutation evidence extraction

Rejected for T463.

Mutation evidence is verifier/outcome evidence, not pre-execution policy. It
should be inspected after the execution guard pipeline is stable.

## T463 Guardrails

T463 must preserve:

- exact diagnostic wording:
  `append-line write_file for ... requires complete same-turn read evidence before approval.`;
- exact diagnostic wording:
  `append-line write_file for ... does not preserve the complete same-turn readback and append exactly ...`;
- alias behavior through `ToolAliasPolicy.localCanonicalName(...)`;
- target matching via `TaskExpectationResolver.resolve(...)`;
- same line-ending normalization;
- optional terminal newline acceptance;
- no approval request for invalid append-line full writes;
- no mutation on invalid append-line full writes;
- `APPEND_LINE_WRITE_PRESERVATION` trace/action-obligation recording;
- failed `ToolOutcome` content and error code;
- existing compact repair behavior after the pre-approval failure.

T463 must not touch:

- source-evidence guards;
- full-rewrite repair edit blocking;
- stale edit reread blocking;
- duplicate edit suppression;
- redundant read suppression;
- protected/private read handoff;
- context ledger capture;
- mutation evidence;
- static-web full rewrite recovery;
- final answer wording.

## Proposed T463 Tests

Start with a RED ownership test:

```text
AppendLinePreApprovalGuardTest
```

It should prove:

- invalid append-line `talos.write_file` returns the exact diagnostic;
- valid append-line full write returns `null`;
- same content without a prior read returns the exact missing-read diagnostic;
- `ToolCallExecutionStage` delegates append-line diagnostic selection to the
  guard and no longer owns `appendLinePreApprovalDiagnostic(...)`.

Focused behavior regressions:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.AppendLinePreApprovalGuardTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.ToolCallLoopTest.appendLineFullWriteThatDoesNotPreserveReadbackIsRejectedBeforeApproval" --tests "dev.talos.runtime.ToolCallLoopTest.appendLinePreapprovalFailureUsesCompactRepairWithReadbackBeforeApproval" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.TargetReadbackCompactRepairPlannerTest" --no-daemon
```

Required closeout gate:

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```

## Verification

Required no-code closeout gate:

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```

Passed before PR.
