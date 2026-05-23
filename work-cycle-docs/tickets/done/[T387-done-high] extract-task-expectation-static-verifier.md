# [T387-done-high] Extract Task Expectation Static Verifier

Status: done
Priority: high
Date: 2026-05-23
Branch: `T387`
Candidate version: `talosVersion=0.9.9`
Base branch: `origin/v0.9.0-beta-dev`
Parent head inspected: `f9df3726`
Predecessor: `T386`

## Scope

T387 implements the boundary selected by T386:

```text
[T387] Extract task expectation static verifier
```

This is a behavior-preserving ownership extraction. It moves expectation
post-apply verification out of `StaticTaskVerifier` and into:

```text
src/main/java/dev/talos/runtime/verification/TaskExpectationStaticVerifier.java
```

The existing `StaticTaskVerifier.verify(...)` public facade remains the
orchestrator. It delegates task expectation verification and keeps final
`TaskVerificationResult` summary selection unchanged.

Per the current work instruction, T387 also carries the latest local site
changes from the main checkout on top of fresh `origin/v0.9.0-beta-dev`.
Remote beta had no changes to those four site files after the main checkout
base, and the copied site diff patch-id matched the main checkout patch-id:

```text
5cfbdd06c9a8c41c32b062e773f28b5f7313097d
```

## Implementation

`TaskExpectationStaticVerifier` now owns:

- resolving task expectations through `TaskExpectationResolver`;
- dispatching `LiteralContentExpectation`;
- dispatching `ReplacementExpectation`;
- dispatching `AppendLineExpectation`;
- dispatching `BulletListExpectation`;
- exact literal content postcondition checks;
- replacement old/new text postcondition checks;
- preserve-rest replacement evidence checks;
- append-only evidence checks for exact edit and full-write evidence;
- bullet-list count checks;
- redaction-safe `EXPECTATION_VERIFIED` trace event emission;
- a typed `Result` carrying:
  - `verifiedAny`;
  - `replacementRequired`;
  - `appendLineRequired`;
  - `bulletCountRequired`;
  - expectation facts;
  - expectation problems.

`StaticTaskVerifier` still owns:

- public verifier facade methods;
- mutation target readback checks;
- workspace operation verifier delegation;
- expected/forbidden target scope checks;
- exact edit fallback verification;
- source-derived artifact verification;
- static-web orchestration and diagnostic facades;
- final `TaskVerificationResult` status/summary selection.

## Behavior Preservation

T387 intentionally preserves existing user-facing behavior:

- exact content summaries stay the same;
- replacement summaries stay the same;
- append-line summaries stay the same;
- bullet-list summaries stay the same;
- facts and problems are copied from the prior implementation;
- `EXPECTATION_VERIFIED` trace payload keys and redaction behavior are preserved;
- `StaticTaskVerifier.verifyWithoutTraceEvents(...)` still suppresses
  expectation trace events.

No source-derived artifact verification, exact-edit fallback verification,
target verification, static-web verification, outcome dominance, or final-answer
rendering behavior is moved in this ticket.

## Measurements

Measured after extraction:

| File | Lines | Role |
|---|---:|---|
| `StaticTaskVerifier.java` | 1270 | Public facade/orchestrator plus remaining non-expectation verifier domains. |
| `TaskExpectationStaticVerifier.java` | 644 | Deterministic expectation postcondition verifier and expectation trace emitter. |

Before T387, `StaticTaskVerifier.java` was 1852 lines on `f9df3726`.

## TDD Evidence

RED:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.verification.TaskExpectationStaticVerifierTest" --no-daemon
```

Result: failed at `:compileTestJava` because `TaskExpectationStaticVerifier`
did not exist.

GREEN:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.verification.TaskExpectationStaticVerifierTest" --tests "dev.talos.runtime.verification.StaticTaskVerifierTest" --tests "dev.talos.runtime.expectation.TaskExpectationResolverTest" --no-daemon
```

Result: passed.

## Verification

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.verification.TaskExpectationStaticVerifierTest" --tests "dev.talos.runtime.verification.StaticTaskVerifierTest" --tests "dev.talos.runtime.expectation.TaskExpectationResolverTest" --no-daemon
npm test --prefix site
npm run build --prefix site
npm run test:e2e --prefix site
.\gradlew.bat validateArchitectureBoundaries --no-daemon
git diff --check
.\gradlew.bat check --no-daemon
```

- `.\gradlew.bat test --tests "dev.talos.runtime.verification.TaskExpectationStaticVerifierTest" --tests "dev.talos.runtime.verification.StaticTaskVerifierTest" --tests "dev.talos.runtime.expectation.TaskExpectationResolverTest" --no-daemon`:
  passed (`BUILD SUCCESSFUL`).
- `npm test --prefix site`: passed (27 tests, 0 failures).
- `npm run build --prefix site`: passed after `npm ci --prefix site`
  installed the isolated worktree's missing site dependencies.
- `npm run test:e2e --prefix site`: passed (22 tests, 0 failures).
- `.\gradlew.bat validateArchitectureBoundaries --no-daemon`: passed
  (`BUILD SUCCESSFUL`, 1 actionable task up-to-date).
- `git diff --check`: passed, line-ending warnings only.
- `.\gradlew.bat check --no-daemon`: passed (`BUILD SUCCESSFUL`, 14
  actionable tasks: 2 executed, 12 up-to-date).

## Next Decision

After T387 lands, do not automatically extract another verifier.

The next likely implementation ticket is the source-derived artifact verifier
selected as provisional follow-up in T386, but it must be re-inspected from the
post-T387 source first because it crosses document extraction, file capability
policy, source evidence, and hallucination detection.
