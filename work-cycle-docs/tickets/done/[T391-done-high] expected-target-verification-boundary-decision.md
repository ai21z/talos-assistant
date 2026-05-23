# [T391-done-high] Expected Target Verification Boundary Decision

Status: done
Priority: high
Date: 2026-05-23
Branch: `T391`
Candidate version: `talosVersion=0.9.9`
Base branch: `origin/v0.9.0-beta-dev`
Parent head inspected: `fba3ce6e`
Predecessor: `T390`

## Scope

T391 is a no-code inspection and decision ticket.

The task is to inspect whether expected-target verification is now a coherent
implementation boundary after T390 extracted exact edit replacement
verification. T391 intentionally does not extract code. The goal is to avoid
moving a mixed policy block mechanically without naming the real owner first.

## Source Evidence

The source inventory was taken from fresh `origin/v0.9.0-beta-dev` on branch
`T391`.

| Area | Evidence | Boundary implication |
|---|---|---|
| Current verifier size | `src/main/java/dev/talos/runtime/verification/StaticTaskVerifier.java` is 835 lines. | The facade is much smaller after T387-T390, but target-scope verification remains embedded in the orchestrator. |
| Facade call site | `StaticTaskVerifier.verifyInternal(...)` calls `verifyExpectedTargets(...)` at `StaticTaskVerifier.java:147`. | The facade already has a single delegation seam for this behavior. |
| Target-scope block | `verifyExpectedTargets(...)` starts at `StaticTaskVerifier.java:268`. | The block is coherent as target-scope verification, not as a narrow expected-target-only helper. |
| Workspace-operation exemptions | `expectedTargetExemptions` is initialized in `StaticTaskVerifier.java:131`, populated from `WorkspaceOperationStaticVerifier` at `:142`, and consumed in `verifyExpectedTargets(...)` at `:273` and `:288`. | Expected targets must account for move, copy, delete, and source-path semantics from workspace operation plans. |
| Workspace-operation aliases | `expectedTargetAliases` comes from `WorkspaceOperationStaticVerifier.Result` and is passed into `verifyExpectedTargets(...)` at `StaticTaskVerifier.java:148`; aliases are normalized at `:293` and used at `:318` and `:342`. | Target verification depends on workspace operation ownership and basename aliases. This must move as part of the whole target-scope owner or remain in the facade. |
| Forbidden targets | `contract.forbiddenTargets()` is verified at `StaticTaskVerifier.java:299-306`. | Forbidden target checks are part of the same target-scope truthfulness rule as expected targets. Splitting them would duplicate matching semantics. |
| Expected targets | `contract.expectedTargets()` is verified at `StaticTaskVerifier.java:310-338`. | This is the central expected-target behavior, but it shares normalization, matching, aliases, exemptions, and diagnostics with forbidden and only-target checks. |
| Static-web context exception | `staticWebRepairContextTargetSatisfied(...)` is called at `StaticTaskVerifier.java:320` and defined at `:389`. | Static-web repair can satisfy an expected context target without direct mutation. This is mixed policy, but it is still a target-scope exception and must be preserved exactly if extracted. |
| Only-target requests | `singleTargetOnlyMutationTarget(...)` starts at `StaticTaskVerifier.java:361`; `requestHasOnlyTargetLimiter(...)` starts at `:368`. | This is request-language policy tied to expected target scope. It should not be extracted separately because it is meaningless without mutated-target matching. |
| OS-specific matching | `expectedTargetMatchingIsCaseInsensitive()` starts at `StaticTaskVerifier.java:893`; `expectedTargetMatches(...)` starts at `:842`. | Windows case-insensitive matching is core target-scope behavior and has direct test coverage. |
| Similar target diagnostics | `similarWrongMutationTargets(...)` starts at `StaticTaskVerifier.java:852`; `looksLikeSingularPluralSibling(...)` starts at `:868`. | Similar-file safety such as `script.js` versus `scripts.js` belongs with target-scope verification. |
| Success facts | Expected target success facts are emitted at `StaticTaskVerifier.java:348-357`. | The future owner must preserve fact wording and the distinction between direct target updates and static-web context target satisfaction. |

## Test Evidence

The existing tests show why this boundary is mixed but still coherent.

| Test area | Evidence | Boundary implication |
|---|---|---|
| Static-web context targets | `staticWebRepairContextFilesDoNotAllNeedMutationWhenFinalSurfacePasses(...)` asserts that static-web repair can pass without directly mutating every named context file. | A future extraction must keep static-web context satisfaction inside the target-scope verifier input contract. |
| Windows path matching | `expectedTargetMatchingCanUseWindowsCaseInsensitiveSemantics(...)` and `expectedTargetFromContractMatchesCaseDifferenceOnWindows(...)` cover case-insensitive matching. | The matching helper should move with target-scope verification and direct tests should move with it. |
| Expected target miss | `expectedTargetFromContractMustBeMutated(...)` asserts failure when `style.css` changes but `index.html` was expected. | Basic expected-target behavior is already deterministic and suitable for direct verifier tests. |
| Similar wrong target | `expectedScriptsJsTargetFailsWhenOnlySingularScriptJsWasMutated(...)` asserts `scripts.js` is not satisfied by `script.js` and reports the similar target. | Similar-target diagnostics are not polish; they are safety behavior and must remain coupled to expected-target matching. |
| Forbidden target | `forbiddenSimilarTargetMutationFailsEvenWhenExpectedTargetMutated(...)` asserts `scripts.js` mutation fails when explicitly forbidden. | Forbidden targets should not be separated from expected-target matching. |
| Only-target guard | `onlyTargetRequestFailsWhenAdditionalSiblingTargetMutated(...)` asserts an additional mutation fails under an only-target request. | Only-target language belongs in target-scope verification, not in final summary selection. |
| Workspace operation target aliases | `WorkspaceOperationStaticVerifierTest` asserts workspace operation outcomes and expected target satisfaction. | T392 must keep the alias/exemption pipe from `WorkspaceOperationStaticVerifier.Result` intact. |

## Decision

Expected-target verification should not be extracted as a narrow
`ExpectedTargetVerifier`.

The correct next implementation owner is broader:

```text
[T392] Extract target scope static verifier
```

The owner should be a package-private verifier under the existing runtime
verification package:

```text
src/main/java/dev/talos/runtime/verification/TargetScopeStaticVerifier.java
```

This is the right boundary because the current block verifies the target scope
of a completed mutation, not only whether one expected file changed. The owner
must include:

- expected targets;
- forbidden targets;
- only-target request limits;
- workspace-operation target exemptions;
- workspace-operation target aliases;
- static-web repair context target satisfaction;
- Windows-aware target matching;
- similar-target diagnostics;
- target-scope facts and problems.

`StaticTaskVerifier` should remain the public orchestrator. It should call the
new verifier, append returned facts and problems, and keep final
`TaskVerificationResult` summary selection unchanged.

## Why T392 Should Not Split Smaller

T392 should not extract only `expectedTargetMatches(...)`.

The matcher has no useful ownership by itself. Its behavior matters because
forbidden targets, expected targets, aliases, only-target requests, and
similar-target diagnostics all use the same normalized comparison semantics.
Extracting the matcher alone would create a utility but leave the real
policy owner buried in `StaticTaskVerifier`.

T392 should not extract only the only-target language detector.

`requestHasOnlyTargetLimiter(...)` is not a standalone task classifier. It is a
target-scope guard that becomes actionable only when there is exactly one
expected target and multiple mutation outcomes to compare. Moving it alone
would make the architecture look cleaner while making ownership less obvious.

T392 should not split out static-web context satisfaction yet.

`staticWebRepairContextTargetSatisfied(...)` is the mixed part of the boundary,
but it is also the exception that prevents false failures for coherent
static-web repairs. It should move with the target-scope verifier as an
explicit dependency on `CapabilityProfile` and `StaticWebCapabilityProfile`.
Only after T392 should a separate ticket decide whether static-web context
target satisfaction deserves its own policy object.

## T392 Implementation Boundary

T392 should:

- create `TargetScopeStaticVerifier`;
- move `verifyExpectedTargets(...)` behavior behind a typed result;
- move helper methods that are target-scope-specific:
  - `singleTargetOnlyMutationTarget(...)`;
  - `requestHasOnlyTargetLimiter(...)`;
  - `staticWebRepairContextTargetSatisfied(...)`;
  - `expectedTargetMatches(...)`;
  - `similarWrongMutationTargets(...)`;
  - `looksLikeSingularPluralSibling(...)`;
  - `expectedTargetMatchingIsCaseInsensitive(...)`;
  - target-scope rendering needed for similar-target diagnostics;
- preserve existing fact and problem wording;
- update direct tests that currently call `StaticTaskVerifier.expectedTargetMatches(...)`
  to call the new package-private owner;
- keep `StaticTaskVerifier.verify(...)` as the public facade;
- keep final summary precedence unchanged.

T392 should not:

- change `TaskContractResolver`;
- change expected target extraction rules;
- change static-web capability selection;
- change workspace operation planning or verification;
- change final task verification summaries;
- add new target policy semantics;
- weaken `script.js` versus `scripts.js` safety;
- relax forbidden-target or only-target failures.

## Focused Test Plan For T392

Recommended RED/GREEN focused tests:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.verification.TargetScopeStaticVerifierTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.verification.StaticTaskVerifierTest" --tests "dev.talos.runtime.verification.WorkspaceOperationStaticVerifierTest" --no-daemon
```

If T392 starts by moving existing behavior, the first RED should be a direct
test for `TargetScopeStaticVerifier` that fails to compile until the new owner
exists.

Required closeout gates:

```powershell
.\gradlew.bat validateArchitectureBoundaries --no-daemon
git diff --check
.\gradlew.bat check --no-daemon
```

## Acceptance Criteria

- T391 records the source evidence for current expected/forbidden/only-target
  verification.
- T391 identifies the owner as target-scope verification, not expected-target
  verification narrowly.
- T391 selects T392 only after inspecting source and tests on fresh beta.
- T391 changes no production runtime behavior.
- No generated artifacts or prompt-debug evidence directories are committed.

## Verification

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```

- `git diff --check`: passed.
- `.\gradlew.bat validateArchitectureBoundaries --no-daemon`: passed
  (`BUILD SUCCESSFUL`; 1 actionable task executed).
- `.\gradlew.bat check --no-daemon`: passed (`BUILD SUCCESSFUL`; first run
  had 14 actionable tasks: 13 executed, 1 up-to-date; final packet rerun had
  14 actionable tasks: 2 executed, 12 up-to-date).
