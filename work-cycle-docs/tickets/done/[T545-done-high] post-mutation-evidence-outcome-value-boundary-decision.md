# [T545-done-high] Post Mutation Evidence Outcome Value Boundary Decision

Status: done
Priority: high
Date: 2026-05-27
Branch: `T545`
Candidate version: `talosVersion=0.9.9`
Base branch: `origin/v0.9.0-beta-dev`
Parent head inspected: `36674880`
Predecessor: `T544`

## Scope

T545 inspects the post-T544 outcome value surface before starting another
implementation ticket.

This ticket intentionally makes no code changes.

## Source Evidence

Measured from fresh `origin/v0.9.0-beta-dev` at `36674880`.

Primary inspection commands:

```powershell
rg -l "ToolCallLoop\.LoopResult" src/main/java src/test/java src/e2eTest/java
rg -l "ToolCallLoop\.ToolOutcome" src/main/java src/test/java src/e2eTest/java
rg -l "ToolMutationEvidence" src/main/java src/test/java src/e2eTest/java
rg -n "invalidEmptyEditArguments|fullRewriteRepairRedirect|oldStringNotFoundEditFailure|appendLinePreservationFailure|expectedTargetScopeFailure" src/main/java src/test/java src/e2eTest/java
rg -n "new ToolCallLoop\.ToolOutcome|ToolOutcomeFactory\.|ToolCallLoop\.ToolOutcome\(" src/main/java src/test/java src/e2eTest/java
```

Current reference spread:

| Reference | Files |
|---|---:|
| `ToolCallLoop.LoopResult` | 44 |
| `ToolCallLoop.ToolOutcome` | 77 |
| `ToolMutationEvidence` | 14 |
| `ToolOutcomeFactory` | 3 |
| `ToolMutationEvidenceFactory` | 3 |

Post-T544 status:

| Area | Current owner assessment |
|---|---|
| `ToolMutationEvidence` | Acceptable. It is no longer nested in `ToolCallLoop`; production construction is narrow through `ToolMutationEvidenceFactory`. |
| `ToolOutcomeFactory` | Acceptable. Production `ToolOutcome` construction is already centralized in the tool-call execution lane. |
| `ToolMutationEvidenceFactory` | Acceptable. Mutation-evidence construction is already centralized and tested. |
| `ToolCallLoop.LoopResult` | Still broad public facade. Do not move without a compatibility plan. |
| `ToolCallLoop.ToolOutcome` | Still broad public facade. Do not move as a mechanical follow-up. |
| `ToolOutcome` failure-shape methods | Coherent remaining smell: five error-shape predicates still live inside the nested value. |

The remaining `ToolOutcome` predicate methods in `ToolCallLoop.java`:

| Method | Current meaning |
|---|---|
| `invalidEmptyEditArguments()` | Classifies recoverable invalid edit args involving empty/missing `old_string` or `new_string`. |
| `fullRewriteRepairRedirect()` | Classifies static-verification repair redirects that require full `write_file` replacement. |
| `oldStringNotFoundEditFailure()` | Classifies `talos.edit_file` old-string-not-found failures. |
| `appendLinePreservationFailure()` | Classifies append-line `write_file` preservation failures. |
| `expectedTargetScopeFailure()` | Classifies expected-target scope failures before approval. |

Production consumers of those failure-shape methods:

| Consumer | Methods used | Meaning |
|---|---|---|
| `ToolCallLoop.LoopResult.summary()` | invalid-empty, full-rewrite, old-string-not-found | Suppresses recovered edit failures from summary failed-call count. |
| `MissingMutationRetry.java` | full-rewrite | Prevents misleading missing-mutation retry when full-rewrite repair already redirected. |
| `MutationFailureAnswerRenderer.java` | invalid-empty, full-rewrite, old-string-not-found | Renders truthful partial/failed mutation summaries. |
| `MutationOutcome.java` | invalid-empty, full-rewrite, old-string-not-found | Classifies recovered invalid edit failures. |
| `ExpectedTargetScopeRepairPlanner.java` | expected-target-scope | Plans target-scope repair. |
| `TargetReadbackCompactRepairPlanner.java` | append-line, old-string-not-found | Plans compact readback repair for mutation verification. |

This is a coherent owner because all five methods classify tool-outcome failure
shapes from the same facts:

- tool name;
- mutating/success/denied state;
- `ToolError.INVALID_PARAMS`;
- error-message text.

## Decision

Do not move `ToolOutcome` yet.

Do not move `LoopResult` yet.

The next implementation ticket should be:

```text
[T546] Extract tool outcome failure shape classifier
```

T546 should move only the failure-shape predicate bodies out of
`ToolCallLoop.ToolOutcome` into a dedicated tool-call helper while preserving
the public `ToolOutcome` predicate methods as compatibility wrappers.

Recommended target:

```text
dev.talos.runtime.toolcall.ToolOutcomeFailureShape
```

Recommended implementation shape:

1. Add RED ownership test proving `ToolCallLoop.java` no longer owns the
   string-matching bodies for the five failure-shape predicates.
2. Add `ToolOutcomeFailureShape` with static methods:
   - `invalidEmptyEditArguments(ToolCallLoop.ToolOutcome)`;
   - `fullRewriteRepairRedirect(ToolCallLoop.ToolOutcome)`;
   - `oldStringNotFoundEditFailure(ToolCallLoop.ToolOutcome)`;
   - `appendLinePreservationFailure(ToolCallLoop.ToolOutcome)`;
   - `expectedTargetScopeFailure(ToolCallLoop.ToolOutcome)`.
3. Keep the existing `ToolOutcome` instance methods and delegate to the helper.
4. Preserve exact behavior and wording.
5. Run focused tests around:
   - `MutationOutcomeTest`;
   - `MutationFailureAnswerRendererTest`;
   - `ExpectedTargetScopeRepairPlannerTest`;
   - `TargetReadbackCompactRepairPlannerTest`;
   - `ToolCallLoopTest` cases covering recovered edit failures.
6. Run `git diff --check`, `validateArchitectureBoundaries`, and full
   `check`.

This is the correct next slice because it improves ownership without breaking
the public `ToolOutcome` facade or forcing broad API churn.

## Rejected Next Moves

### Move `ToolOutcome`

Rejected.

Reason: `ToolOutcome` is still referenced from 77 files across CLI, runtime
outcome rendering, evidence policy, static verification, reprompt planning,
trace/accounting, and tests. A direct move would be compatibility churn, not a
clean architecture improvement.

### Move `LoopResult`

Rejected.

Reason: `LoopResult` is still referenced from 44 files and remains the public
`ToolCallLoop.run(...)` facade. It needs a separate compatibility decision.

### Extract final-answer or outcome-rendering code

Rejected.

Reason: T542 closed the response/final-output lane. The current smell is not
final answer text; it is failure-shape classification embedded in a nested
value.

### Extract another random block from `ToolCallLoop.run(...)`

Rejected.

Reason: the remaining improvement must clarify ownership. Random run-loop
extraction would reduce locality without resolving a known boundary.

## Acceptance Criteria

- Inspect post-T544 value ownership from fresh beta.
- Confirm `ToolMutationEvidence` extraction is steady-state.
- Re-evaluate whether `ToolOutcome` or `LoopResult` should move next.
- Select the next implementation ticket from source evidence.
- Make no code changes.

## Verification

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```
