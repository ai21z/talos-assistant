# [T386-done-high] StaticTaskVerifier Expectation And Evidence Boundary Decision

Status: done
Priority: high
Date: 2026-05-23
Branch: `T386`
Candidate version: `talosVersion=0.9.9`
Base branch: `origin/v0.9.0-beta-dev`
Parent head inspected: `e8c9f354`
Predecessor: `T385`

## Scope

T386 is a no-code inspection and decision ticket.

The task is to inspect the non-static-web responsibilities still inside
`StaticTaskVerifier` after the static-web verifier lane closed in T385, then
choose the next coherent implementation owner.

T386 intentionally does not extract code. The goal is to avoid continuing with
mechanical line-count cleanup after the easy static-web verifier pieces have
already moved out.

## Source Evidence

The source inventory was taken from fresh `origin/v0.9.0-beta-dev` on branch
`T386`.

| Area | Evidence | Ownership pressure |
|---|---|---|
| Current verifier size | `src/main/java/dev/talos/runtime/verification/StaticTaskVerifier.java` is 1852 lines. | Static-web extraction reduced the file, but the class is still a verifier framework hidden behind one facade. |
| Public facade | `StaticTaskVerifier.verify(...)` and `verifyWithoutTraceEvents(...)` remain at lines 96, 109, and 118. | The public facade should remain stable until each inner verifier has a typed result boundary. |
| Expectation dispatch | `verifyTaskExpectations(...)` starts at line 278 and dispatches `LiteralContentExpectation`, `ReplacementExpectation`, `AppendLineExpectation`, and `BulletListExpectation`. | This is a type-driven expectation verifier sitting outside the expectation package that owns the resolved expectation types. |
| Expectation result flags | `hasBulletCountExpectation(...)`, `hasAppendLineExpectation(...)`, and `hasReplacementExpectation(...)` start at lines 319, 324, and 329 and repeatedly call `TaskExpectationResolver.resolve(...)`. | Summary selection depends on expectation type facts, but those facts are not returned by a dedicated expectation verifier. |
| Literal expectation verification | `verifyLiteralContentExpectation(...)` starts at line 658 and records redacted trace evidence through `recordLiteralExpectation(...)` at line 705. | Exact content postcondition and trace redaction should be owned by the expectation verifier, not by the whole static verifier facade. |
| Replacement expectation verification | `verifyReplacementExpectation(...)` starts at line 725 and includes preserve-rest evidence checks using mutation evidence. | This is expectation-specific truthfulness logic, not static-web or general target verification. |
| Append-line expectation verification | `verifyAppendLineExpectation(...)` starts at line 915 and proves append-only behavior through exact edit or full-write mutation evidence. | This is expectation-specific evidence validation and should live with the other expectation postcondition checks. |
| Bullet-list expectation verification | `verifyBulletListExpectation(...)` starts at line 1096 and uses generic bullet-line counting helpers. | It belongs with expectation verification, not source-derived artifacts or target validation. |
| Trace evidence | `recordLiteralExpectation(...)`, `recordReplacementExpectation(...)`, `recordAppendLineExpectation(...)`, and `recordBulletListExpectation(...)` call `LocalTurnTraceCapture.recordExpectationVerified(...)` at lines 705, 893, 1066, and 1146. | Expectation verification owns redaction-safe expectation evidence; the facade should not emit type-specific expectation trace events directly. |
| Existing expectation model | `TaskExpectationResolver.resolve(...)` starts at `src/main/java/dev/talos/runtime/expectation/TaskExpectationResolver.java:47`, while structural expectation parsing starts at lines 91 and 117. | The codebase already has a first-class expectation model; verification is the missing half of that ownership. |
| Unused expectation result type | `src/main/java/dev/talos/runtime/expectation/ExpectationVerificationResult.java` exists and is not referenced outside itself. | This is a strong signal that expectation verification was intended to become structured but was left inside `StaticTaskVerifier`. |
| Source-derived artifacts | `verifySourceDerivedArtifact(...)` starts at line 334 and reads text sources plus extractable PDF/DOCX/XLSX evidence through `DocumentExtractionService`. | This is coherent, but it crosses document extraction, file capability policy, source evidence, hallucination detection, and summary scoring. It deserves its own ticket after the expectation boundary is cleaner. |
| Exact edit evidence | `verifyExactEditEvidence(...)` starts at line 592 and checks exact `edit_file` mutation evidence through `ToolAliasPolicy`. | This is coherent and smaller, but it is a generic mutation-evidence fallback. Extracting it before expectations would leave the larger expectation/evidence lie intact. |
| Expected/forbidden targets | `verifyExpectedTargets(...)` starts at line 1167 and includes only-target, forbidden-target, similar-target, aliases, and static-web context-target exceptions. | This boundary is mixed with target scope, static-web context satisfaction, and Windows-style case-insensitive matching. It should not be first. |
| Mutation target checks | `verifyMutationTarget(...)` starts at line 1311 and handles generic path/readability/template-placeholder checks. | This is generic readback infrastructure and should stay in the facade until target-scope verification is separated cleanly. |

## Test Evidence

The existing tests identify the next boundary by behavior, not by naming alone.

| Test area | Evidence | Boundary implication |
|---|---|---|
| Expectation trace redaction | `literalExpectationTraceEventIsRedacted(...)`, `appendLineExpectationTraceEventIsRedacted(...)`, and `replacementExpectationTraceEventIsRedacted(...)` are at `StaticTaskVerifierTest.java:469`, `:507`, and `:552`. | A future expectation verifier must preserve redacted `EXPECTATION_VERIFIED` events exactly. |
| Append and bullet expectations | Append and bullet assertions appear around `StaticTaskVerifierTest.java:253`, `:321`, `:363`, `:386`, `:409`, `:425`, `:445`, and `:465`. | Expectation verification has enough focused behavior to test an extracted component directly. |
| Source-derived artifacts | Multi-source and document-source summary tests are at `StaticTaskVerifierTest.java:1215`, `:1243`, and `:1300`. | Source-derived verification is important but document-extraction-coupled; it should not be mixed into the same ticket as expectation extraction. |
| Exact edit evidence | Exact edit evidence tests are at `StaticTaskVerifierTest.java:2070` and nearby exact-edit assertions. | Exact edit can become a later narrow verifier, but it is not the primary ownership gap. |
| Target scope | Expected, forbidden, and only-target tests are at `StaticTaskVerifierTest.java:2486`, `:2502`, `:2550`, and `:2572`. | Target-scope verification is still mixed with static-web target exceptions and should get a separate decision or extraction later. |

## Decision

The next implementation ticket should be:

```text
[T387] Extract task expectation static verifier
```

The owner should be a package-private verifier under the existing runtime
verification package:

```text
src/main/java/dev/talos/runtime/verification/TaskExpectationStaticVerifier.java
```

This is the correct next owner because the codebase already separates
expectation parsing and expectation value types under `dev.talos.runtime.expectation`,
but the post-apply verifier for those expectations still lives inside
`StaticTaskVerifier`.

The implementation should make `StaticTaskVerifier` delegate expectation
verification and receive a typed result that contains at least:

- whether any task expectation was verified;
- whether replacement verification was required;
- whether append-line verification was required;
- whether bullet-list verification was required;
- expectation facts;
- expectation problems.

`StaticTaskVerifier` should keep final `TaskVerificationResult` selection in
T387 unless moving it is proven necessary. The first extraction should preserve
all existing summaries, facts, problems, and trace event payloads.

## Why T387 Should Not Be Source-Derived First

`SourceDerivedArtifactVerifier` is a real future owner, but it is not the next
implementation ticket.

Source-derived verification currently:

- resolves target and source paths;
- reads final target content;
- extracts evidence from text-bearing PDFs, Word documents, and workbooks;
- uses `Config`, `FileCapabilityPolicy`, `DocumentExtractionService`,
  `DocumentExtractionRequest`, `DocumentExtractionResult`, and
  `DocumentExtractionStatus`;
- detects instruction echoing;
- compares distinctive source terms against target terms;
- detects unsupported target terms;
- enforces narrow bullet limits.

That is a high-value truthfulness verifier, but it crosses document extraction
and source-evidence policy. Extracting it before the expectation verifier would
leave the cleaner, already-modeled expectation boundary buried in the facade.

The likely follow-up after T387 is:

```text
[T388] Extract source-derived artifact verifier
```

That ticket should be selected only after T387 lands cleanly and the remaining
source-derived imports and tests are re-inspected.

## Why T387 Should Not Be Exact Edit Evidence First

`ExactEditEvidenceVerifier` is coherent, but too narrow to be the next correct
ownership move.

The exact-edit verifier only covers successful `edit_file` mutation outcomes
with exact replacement evidence. It improves one fallback result path, but it
does not resolve the larger contradiction where expectation types and
expectation trace events are owned by `StaticTaskVerifier`.

Exact edit evidence should follow once expectation verification and
source-derived verification have their own boundaries, or earlier only if a
specific failure shows that exact-edit behavior is the active risk.

## Why T387 Should Not Be Target Verification First

`MutationTargetVerifier` or `ExpectedTargetVerifier` would be premature as the
next ticket.

`verifyExpectedTargets(...)` is not just "did the target change." It includes:

- expected targets;
- forbidden targets;
- only-target requests;
- similar-target detection such as `script.js` versus `scripts.js`;
- aliases from workspace operation plans;
- exemptions for source/deleted/moved paths;
- static-web context target satisfaction;
- case-insensitive target matching.

That is an important owner, but it is a mixed scope. It should be planned after
expectation and source-derived evidence ownership are no longer inside the
facade.

## T387 Implementation Boundary

T387 should:

- create `TaskExpectationStaticVerifier`;
- move expectation dispatch for literal, replacement, append-line, and bullet
  expectations out of `StaticTaskVerifier`;
- move expectation-specific helpers needed by those checks;
- move expectation trace event emission while preserving redaction behavior;
- return a typed result with facts, problems, and expectation-kind booleans;
- keep `StaticTaskVerifier.verify(...)` as the public orchestrator;
- preserve exact user-facing summaries, facts, problems, and trace payload
  keys/values.

T387 should not:

- move source-derived artifact verification;
- move exact-edit fallback verification;
- move expected/forbidden target verification;
- move mutation-target readback verification;
- move static-web verification;
- change outcome dominance or final-answer wording;
- relax or add architecture boundary rules;
- rewrite the `TaskExpectationResolver`.

## Focused Test Plan For T387

Recommended focused tests:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.verification.StaticTaskVerifierTest" --tests "dev.talos.runtime.expectation.TaskExpectationResolverTest" --no-daemon
```

If T387 introduces direct tests for `TaskExpectationStaticVerifier`, run them
with the same command or as a narrower focused target first.

Required closeout gates:

```powershell
.\gradlew.bat validateArchitectureBoundaries --no-daemon
git diff --check
.\gradlew.bat check --no-daemon
```

## Acceptance Criteria

- T386 records the source evidence for the remaining non-static-web verifier
  responsibilities in `StaticTaskVerifier`.
- T386 chooses a next implementation owner from inspected source, not from
  line-count chasing.
- T386 rejects source-derived, exact-edit, and target-verification extractions
  as the immediate next ticket with concrete reasons.
- T386 changes no production runtime behavior.
- No generated artifacts or prompt-debug evidence directories are committed.

## Verification

```powershell
.\gradlew.bat validateArchitectureBoundaries --no-daemon
git diff --check
.\gradlew.bat check --no-daemon
```

- `.\gradlew.bat validateArchitectureBoundaries --no-daemon`: passed
  (`BUILD SUCCESSFUL`, 1 actionable task up-to-date).
- `git diff --check`: passed.
- `.\gradlew.bat check --no-daemon`: passed (`BUILD SUCCESSFUL`, 14
  actionable tasks: 2 executed, 12 up-to-date).
