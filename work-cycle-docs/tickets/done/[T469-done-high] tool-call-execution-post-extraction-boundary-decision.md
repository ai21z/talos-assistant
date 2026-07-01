# [T469-done-high] Tool-Call Execution Post-Extraction Boundary Decision

## Status

Done.

## Scope

T469 inspects the post-T468 `ToolCallExecutionStage` shape after the current
execution-stage extraction lane moved:

- append-line pre-approval diagnostics to `AppendLinePreApprovalGuard`;
- source-derived write-before-read and exact evidence repair to
  `SourceDerivedEvidenceGuard`;
- edit retry pre-approval decisions to `EditFilePreApprovalGuard`;
- duplicate read-only suppression to `RedundantReadSuppressionGuard`;
- mutation-evidence construction to `ToolMutationEvidenceFactory`.

This is a no-code decision ticket. It does not change runtime behavior,
approval behavior, protected/private read handling, context ledger capture,
mutation evidence, static-web repair behavior, tool-result wording, trace
wording, or final outcome rendering.

## Snapshot

Measured from fresh `origin/v0.9.0-beta-dev` at `dd968ac5`.

| Item | Measurement |
|---|---:|
| `ToolCallExecutionStage.java` | 926 lines |
| Architecture baseline | 0 |

## Current Source Shape

`ToolCallExecutionStage` is smaller, but it is not simply a facade. It still
owns execution ordering and several safety-sensitive post-result decisions:

1. protected alias normalization before execution;
2. workspace operation planning and path hinting;
3. pre-approval guard dispatch;
4. actual `TurnProcessor.executeTool(...)`;
5. protected/private model-context handoff;
6. context ledger decision capture;
7. read/mutation state accounting;
8. denied/path-policy/unsupported-read classification;
9. post-result edit failure accounting;
10. static-web full-rewrite recovery state.

The important change is qualitative: the obvious low-risk extraction cluster is
mostly gone. The remaining large cluster is not another simple guard.

## Remaining Responsibility Inventory

| Responsibility | Current source | Classification |
|---|---|---|
| Protected alias normalization | `ProtectedPathAliasNormalizer.canonicalizeExpectedProtectedAliases(...)` in `execute(...)` | Pre-execution path normalization tied to task contract and trace. Keep local until path-policy pipeline is designed. |
| Workspace operation planning | `workspaceOperationPlan(...)`, `pathHint(...)` | Execution framing for path and checkpoint metadata. Low risk, but not currently the biggest ownership problem. |
| Read-before-write nudge | local `readBeforeWriteNudge` block | Small UX nudge tied to `edit_file` result formatting. Too small to justify the next ticket by itself. |
| Protected/private handoff | `isSuccessfulProtectedRead(...)`, private-document handoff approval, withheld result construction, result preservation/sanitization | Safety-critical post-result model-context policy. Needs a decision ticket before implementation. |
| Context ledger capture | `recordContextLedgerDecision(...)` | Accounting for the same protected/private handoff decision. Should probably move with, or immediately after, the handoff owner. |
| Read/mutation state accounting | `recordSuccessfulRead(...)`, `recordMutationSuccess(...)`, read-call body cache clearing | Loop-state bookkeeping. Keep local until post-result execution event shape is clearer. |
| Failure classification | denial, unsupported-read, pre-approval path-policy classification | Outcome classification. Could become a small owner later, but currently intertwined with loop counters and failure decisions. |
| Edit-failure state | `recordStaleEditFailure(...)`, empty-edit failure counts, multi-failure write-file suggestion | Post-result edit failure accounting. Related to previous edit-guard work but not pre-approval; inspect after content handoff. |
| Static-web full rewrite recovery | `shouldRecoverStaticWebEditFailureWithFullRewrite(...)`, `recordStaticWebFullRewriteRequired(...)` | Post-result repair state tied to task contract, static-web profile, trace, and repair context. Do not move casually. |
| Tool outcome summary | `toolOutcomeSummary(...)` | Small formatting helper. Not enough architecture value for the next ticket unless bundled into a broader outcome-accounting owner. |

## Decision

Do not continue the execution-stage lane with another mechanical extraction.

The next correct ticket should be a focused decision ticket for post-result
content handoff:

```text
[T470] Protected And Private Tool Result Handoff Boundary Decision
```

The decision should inspect the protected/private handoff block and answer:

- What owner should decide whether raw tool output can enter model context?
- Should protected read local-display-only handling and private document
  per-turn send-to-model approval share one owner?
- Does context-ledger capture belong inside that owner, beside it, or after it?
- What exact data object should represent the handoff decision?
- Which side effects must stay in `ToolCallExecutionStage`?
- What is the smallest implementation ticket after the decision?

## Current Recommendation For T470

Start with no code.

The likely implementation shape after T470 is an owner such as:

```text
ToolResultModelContextHandoff
```

or:

```text
ToolResultHandoffPolicy
```

But that should not be implemented until T470 proves the API shape from source
and tests.

The owner probably needs to return a decision object containing:

- raw result;
- model-visible result;
- protected-read classification;
- private-document handoff approval state;
- model-context preservation flag;
- context-ledger decision reason;
- whether `state.contentWithheldFromModelContext` must be set.

`ToolCallExecutionStage` should likely keep:

- calling `TurnProcessor.executeTool(...)`;
- invoking approval through `turnProcessor.approvalGate()` until an approval
  adapter boundary is explicitly designed;
- incrementing execution counters;
- appending tool-result messages;
- loop control.

## Rejected Immediate Work

### Extract `toolOutcomeSummary(...)`

Rejected for T470.

It is small and safe, but it does not address the main remaining ownership
confusion. It would reduce line count while avoiding the safety-critical
handoff design.

### Extract static-web full-rewrite recovery

Rejected for T470.

It is post-result repair state, not a continuation of the pre-approval guard
lane. It depends on task contracts, static-web capability classification,
repair context, and trace recording.

### Extract protected/private handoff directly

Rejected for T470 as an immediate implementation.

This block mixes policy, approval, result sanitization, metadata, trace/audit
side effects, context-ledger accounting, and state mutation. It is the right
problem, but it needs an explicit boundary decision before code moves.

## Verification

Required no-code closeout gate:

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```

Run before PR.
