# [T375-done-high] Verification And Outcome Truthfulness Ownership Decision

Status: done
Priority: high
Date: 2026-05-23
Branch: `T375`
Candidate version: `talosVersion=0.9.9`
Base branch: `origin/v0.9.0-beta-dev`
Parent head inspected: `1d2679c52c428e8c161e2b0ea25f665ad4cd3b15`
Predecessor: `T374`

## Scope

This is a decision and inventory ticket, not an implementation burn-down.

T375 starts the verification and outcome truthfulness hygiene lane selected by
T374. It inspects the current source shape, records the ownership model for the
lane, rejects broad first moves, and chooses the first implementation slice from
source evidence.

T375 does not change production runtime behavior, verifier semantics, final
answer wording, package rules, or architecture-boundary scanner rules.

## Source Evidence

The source inventory was taken from fresh `origin/v0.9.0-beta-dev` on branch
`T375`.

| Area | Current evidence | Ownership pressure |
|---|---|---|
| Architecture gate | `config/architecture-boundary-baseline.txt` is empty except for header comments. | Package-direction debt is no longer the active hygiene lane. The next work must attack internal ownership, not import counters. |
| Prior decision | `work-cycle-docs/tickets/done/[T374-done-high] architecture-boundary-zero-baseline-closeout.md` selected verification and outcome truthfulness ownership as the next lane. | T375 should not invent a new lane or start a speculative refactor. |
| Original architecture report | `work-cycle-docs/reports/t335-architecture-hygiene-baseline-20260521.md` records VRT-001 through VRT-004: `StaticTaskVerifier`, string-coupled repair state, primitive outcome dominance, and verifier/repair structure. | The lane is not cosmetic. It is tied to false-success prevention, repair routing, and final answer truthfulness. |
| `StaticTaskVerifier` | `src/main/java/dev/talos/runtime/verification/StaticTaskVerifier.java` is 2855 lines. Its public entrypoint funnels into `verifyInternal(...)`, which handles mutation target evidence, task expectations, exact edit evidence, source-derived artifacts, static web checks, workspace operation verification, facts, problems, and final `TaskVerificationResult` selection. | This class is a verifier framework hidden in one class. It should become an orchestrator over focused verifier components. |
| Workspace operation verification | Workspace operation accumulation and path postcondition checking live as private logic in `StaticTaskVerifier` around `accumulateWorkspaceOperation(...)`, `verifyWorkspaceOperations(...)`, `verifyWorkspacePathExpectation(...)`, and private records for accumulator/result state. | This logic has a clear boundary: convert `WorkspaceOperationPlan` path effects into workspace postcondition facts/problems. It is an implementation-ready extraction. |
| Workspace operation tests | `src/test/java/dev/talos/runtime/verification/WorkspaceOperationStaticVerifierTest.java` exists, but every test still calls `StaticTaskVerifier.verify(...)`. | The test name already identifies the missing production ownership. T376 can move those tests onto the extracted production API while keeping integration coverage through `StaticTaskVerifier`. |
| Broad verifier tests | `src/test/java/dev/talos/runtime/verification/StaticTaskVerifierTest.java` is 2764 lines and covers exact content, bullet counts, append-line checks, replacement checks, source-derived artifacts, static web, exact edit evidence, and readback-only behavior. | A whole-verifier split would be too broad for the first implementation ticket. The test blast radius says extract one verifier unit first. |
| `ExecutionOutcome` | `src/main/java/dev/talos/cli/modes/ExecutionOutcome.java` is 1639 lines. It shapes answers, verifies evidence obligations, runs `StaticTaskVerifier`, maps verification status, asks `OutcomeDominancePolicy` twice, builds `TaskOutcome`, emits truth warnings, and records trace outcomes. | This is important, but changing it first would combine answer wording, verifier invocation, trace, and dominance behavior in one packet. That is too much for the first implementation slice. |
| `OutcomeDominancePolicy` | `src/main/java/dev/talos/cli/modes/OutcomeDominancePolicy.java` has a `Facts` record carrying many primitive boolean signals plus verification status, then a precedence chain chooses completion status. | The model should eventually move toward ranked outcome signals. It should not be first because it affects final-answer dominance and is easier to verify after verifier ownership is cleaner. |
| Runtime outcome types | `src/main/java/dev/talos/runtime/outcome/TaskOutcome.java`, `MutationOutcome.java`, `TaskCompletionStatus.java`, and `TruthWarningType.java` already hold structured runtime outcome data. | The codebase already has a neutral outcome model. The future outcome work should consolidate signals into that model rather than adding more CLI-local booleans. |
| `RepairPolicy` | `src/main/java/dev/talos/runtime/repair/RepairPolicy.java` has typed `RepairPlan` data, but it renders `[Static verification repair context]` prose and exposes `fullRewriteTargetsFromRepairContext(...)`, which reparses rendered prompt text. | This is real design debt, but it is coupled to reprompt control and should follow the first verifier extraction unless a concrete failure forces it earlier. |
| `ToolCallRepromptStage` | `src/main/java/dev/talos/runtime/toolcall/ToolCallRepromptStage.java` consumes static repair context through `RepairPolicy.fullRewriteTargetsFromRepairContext(...)` and string prefix detection around static repair messages. | Reprompt state should eventually consume structured repair state, but changing that first risks loop-control regressions before verifier ownership has been reduced. |
| `TaskVerificationResult` | `src/main/java/dev/talos/runtime/verification/TaskVerificationResult.java` is already a small structured result with status, summary, facts, and problems. | New verifier components can return or contribute to this structure without inventing a new result type immediately. |

## Decision

The next hygiene lane is verification and outcome truthfulness ownership.

The first implementation ticket should be:

```text
[T376] Extract workspace operation static verifier
```

T376 should extract the workspace-operation postcondition verifier into a real
production class:

```text
src/main/java/dev/talos/runtime/verification/WorkspaceOperationStaticVerifier.java
```

The extracted component should own only this responsibility:

```text
Given a workspace root and one or more WorkspaceOperationPlan values, derive
postcondition facts/problems for copied, moved, renamed, deleted, created, and
batch-applied paths, plus expected-target exemptions and aliases.
```

`StaticTaskVerifier` should remain the public orchestrator in T376. It should
delegate workspace-operation path-effect verification to the new component and
keep the rest of the verifier behavior unchanged.

## Why T376 Is The Correct First Slice

T376 is the correct first slice because it reduces real ownership confusion
without changing the user-facing truthfulness contract.

Concrete reasons:

- The production logic is already internally isolated inside
  `StaticTaskVerifier`.
- The tests already describe the missing ownership class name:
  `WorkspaceOperationStaticVerifierTest`.
- The behavior is deterministic local filesystem postcondition checking.
- The component boundary is data-in/data-out: workspace root, operation plans,
  facts, problems, mutation targets, expected target exemptions, and aliases.
- The extraction does not need to rewrite `ExecutionOutcome`,
  `OutcomeDominancePolicy`, `RepairPolicy`, or `ToolCallRepromptStage`.
- The public `StaticTaskVerifier.verify(...)` entrypoint can stay stable while
  the internal implementation becomes smaller.

This is not a baseline decrement ticket. The architecture baseline is already
zero. The metric is now verifier ownership clarity plus unchanged truthfulness
behavior.

## Rejected First Moves

### Full `StaticTaskVerifier` split

Rejected for T376.

Reason: `StaticTaskVerifier` currently mixes expected targets, task
expectations, exact edit evidence, source-derived artifacts, static web
coherence, workspace operations, trace events, and result selection. A full
split would combine too many verification semantics in one PR.

### `OutcomeSignal` / dominance rewrite first

Rejected for T376.

Reason: `OutcomeDominancePolicy` should eventually stop relying on primitive
boolean precedence, but that work changes how failure, partial, blocked,
advisory, and verified-complete signals dominate final status. It has a larger
final-answer blast radius than extracting workspace operation verification.

### Structured repair-state rewrite first

Rejected for T376.

Reason: `RepairPolicy` already has typed `RepairPlan` data, but the loop still
uses rendered repair context for some routing. Replacing that coupling is
important, but it touches `RepairPolicy`, `LoopState`,
`ToolCallRepromptStage`, repair prompts, and static web repair continuation.
That is a later lane slice, not the first extraction.

### Another docs-only ticket

Rejected after T375.

Reason: the first implementation slice is now identifiable from current source
evidence. Continuing with planning-only tickets would delay the actual
ownership improvement.

## T376 Implementation Boundary

T376 should:

- Create `WorkspaceOperationStaticVerifier`.
- Move the private workspace operation accumulator/result/path expectation
  logic out of `StaticTaskVerifier`.
- Preserve the existing public `StaticTaskVerifier.verify(...)` API.
- Keep `TaskVerificationResult` wording and status behavior stable unless a
  test proves the current wording is wrong.
- Move `WorkspaceOperationStaticVerifierTest` onto the extracted production API
  where practical.
- Keep at least one integration assertion through `StaticTaskVerifier.verify(...)`
  so the orchestrator delegation remains covered.

T376 should not:

- Rewrite `ExecutionOutcome`.
- Change outcome dominance precedence.
- Change final answer text unless existing tests require exact adjustment.
- Replace static repair prompt parsing.
- Extract static web verification.
- Extract source-derived artifact verification.
- Add or relax architecture-boundary rules.

## T376 Focused Test Plan

Recommended focused tests before the full check:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.verification.WorkspaceOperationStaticVerifierTest" --tests "dev.talos.runtime.verification.StaticTaskVerifierTest" --no-daemon
```

If implementation touches outcome wording or final-answer shaping despite the
scope above, also run:

```powershell
.\gradlew.bat test --tests "dev.talos.cli.modes.ExecutionOutcomeTest" --tests "dev.talos.cli.modes.OutcomeDominancePolicyTest" --no-daemon
```

Required closeout gates for T376:

```powershell
.\gradlew.bat validateArchitectureBoundaries --no-daemon
git diff --check
.\gradlew.bat check --no-daemon
```

## Future Lane Order After T376

Provisional order after T376:

1. Extract a static web verification component only after workspace-operation
   extraction lands cleanly.
2. Extract source-derived artifact verification if the static web extraction
   does not reveal a better intermediate boundary.
3. Replace repair-context string parsing with structured repair state.
4. Replace boolean outcome dominance with ranked outcome signals.

This order is provisional. Each ticket must re-check source evidence before
implementation.

## Acceptance Criteria

- The next hygiene lane is explicitly verification and outcome truthfulness
  ownership.
- T375 records source evidence for the decision.
- T375 chooses a concrete T376 implementation slice.
- T375 rejects broad first moves with reasons.
- T375 changes no production runtime behavior.
- No generated artifacts or prompt-debug evidence directories are committed.

## Verification

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```

Result:

- `git diff --check`: passed.
- `.\gradlew.bat validateArchitectureBoundaries --no-daemon`: passed
  (`:validateArchitectureBoundaries` up to date).
- `.\gradlew.bat check --no-daemon`: passed (`BUILD SUCCESSFUL`, 14
  actionable tasks: 2 executed, 12 up-to-date).
