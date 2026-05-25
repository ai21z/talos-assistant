# [T452-done-high] Post-T451 ToolCallRepromptStage Boundary Closeout

## Status

Done.

## Scope

T452 inspects the post-T451 `ToolCallRepromptStage` shape after
`TerminalReadOnlyStopAnswer` was extracted.

This is a no-code closeout and next-lane decision ticket. It does not change
runtime behavior, prompt wording, tool selection, verifier behavior, failure
dominance, or final outcome rendering.

## Snapshot

Measured from fresh `origin/v0.9.0-beta-dev` at `2d27c115`.

| Item | Measurement |
|---|---:|
| Candidate version | `talosVersion=0.9.9` |
| `ToolCallRepromptStage.java` | 2436 lines |
| `TerminalReadOnlyStopAnswer.java` | 232 lines |
| Architecture baseline | 0 |

## Post-T451 Source Shape

T451 removed the clearly bounded deterministic terminal read-only answer lane:

- `ToolCallRepromptStage` now delegates terminal read-only answer selection to
  `TerminalReadOnlyStopAnswer`;
- `TerminalReadOnlyStopAnswer` owns read-target, directory-listing,
  unsupported-document, and read-only static-web diagnostic terminal answers;
- `ToolCallRepromptStage` keeps lifecycle placement and ordering.

The remaining large sections are not equally safe:

| Area | Finding |
|---|---|
| Top-level `reprompt(...)` ordering | Still order-sensitive loop orchestration across approval stops, path-policy stops, terminal read-only answers, mutation success stops, context-budget fallback, failure policy, repair prompts, and cleanup. Do not move wholesale. |
| Compact mutation continuation | Still tied to context-budget dominance, backend calls, mutable `LoopState`, target/readback/source-evidence selection, and tool-choice controls. Do not extract as a hygiene move. |
| Generic repair continuations | Expected-target, source-evidence, append-line, and old-string repair selection share helpers and failure semantics. Do not split casually. |
| Static-web continuation | Coherent candidate lane, but it crosses verifier output, linked asset inference, pending action obligations, mutation accounting, tool narrowing, and provider reprompting. It needs guardrails before code movement. |

## Decision

Close the deterministic terminal read-only stop-answer lane.

Do not start another mechanical extraction from `ToolCallRepromptStage`.

The next correct lane is static-web continuation ownership, but it should be
started as a decision/inspection ticket before implementation.

Suggested next ticket:

```text
[T453] Static web continuation boundary decision
```

T453 should decide whether the following cluster forms a single owner:

- `continueStaticWebCreationAfterDirectoryOnlyMutation(...)`;
- `continueStaticWebCreationAfterVerificationFailure(...)`;
- `staticWebCreationContinuationMessages(...)`;
- `staticWebVerificationContinuationMessages(...)`;
- `staticWebVerificationFailureContext(...)`;
- `staticWebCreationContinuationControls(...)`;
- `successfulDirectoryMutationSummary(...)`;
- `staticWebVerificationContinuation(...)`;
- `missingStaticWebTargets(...)`;
- linked missing CSS/JavaScript asset inference;
- small-web mutation satisfaction accounting.

## Guardrails For T453

T453 must answer before implementation:

- should the owner be a runtime/toolcall owner behind `ToolCallRepromptStage`,
  such as `StaticWebContinuation`, rather than a verifier or CLI-mode class;
- should it own only message/target planning, or also the actual
  `chatReprompt(...)` call;
- how to preserve pending action obligation setup for missing targets;
- how to preserve required-tool controls and debug tags;
- how to preserve linked asset inference from mutated HTML;
- how to preserve static verification failure context wording;
- which focused tests should fail before extraction and pass after extraction.

T453 must not touch:

- compact mutation continuation;
- compact read-only evidence continuation;
- terminal read-only stop answers;
- expected-target, source-evidence, append-line, or old-string repair lanes;
- final outcome warning construction;
- `AssistantTurnExecutor` final-answer shaping.

## Candidate T454 Shape

If T453 confirms the boundary, T454 can extract a runtime/toolcall owner such
as:

```text
dev.talos.runtime.toolcall.StaticWebContinuation
```

The safest API is probably not yet settled. The decision ticket should compare:

```text
StaticWebContinuation.tryContinue(ToolCallRepromptStage.RepromptBridge bridge, LoopState state)
```

against a smaller plan-returning shape:

```text
StaticWebContinuation.nextPlan(LoopState state)
```

The plan-returning shape is less invasive if it keeps `chatReprompt(...)`
inside `ToolCallRepromptStage`, but it may leave too much ownership behind.
T453 should decide based on concrete source and test evidence.

## Verification

Required no-code closeout gate:

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```

Passed before merge.
