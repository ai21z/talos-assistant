# [T396-done-high] TaskExpectationStaticVerifier Boundary Decision

Status: done
Priority: high
Date: 2026-05-24
Branch: `T396`
Candidate version: `talosVersion=0.9.9`
Base branch: `origin/v0.9.0-beta-dev`
Parent head inspected: `87d5a1eb`
Predecessor: `T395`

## Scope

T396 is a no-code inspection and decision ticket.

The task is to inspect `TaskExpectationStaticVerifier` after the T394/T395
static-verifier lane closeout, then decide whether the next move should be an
implementation extraction, another no-code planning ticket, or no action.

T396 intentionally does not move verifier code. This verifier sits on a
truthfulness boundary: it decides whether Talos can honestly claim that a user
requested exact content, text replacement, append-line, or bullet-count task was
actually satisfied. Moving code here without a named ownership target risks
changing outcome wording, trace redaction, or failure dominance.

## Current Measurements

Measured from fresh `origin/v0.9.0-beta-dev` at `87d5a1eb`:

| File | Lines | Current role |
|---|---:|---|
| `TaskExpectationStaticVerifier.java` | 644 | Resolves task expectations, verifies literal/replacement/append-line/bullet-list postconditions, records expectation traces, and returns summary-selection flags. |
| `TaskExpectationResolver.java` | 398 | Converts `TaskContract` wording into narrow deterministic expectation records. |
| `TaskExpectationStaticVerifierTest.java` | 76 | Focused expectation-verifier redaction test added in the verifier lane. |
| `TaskExpectationResolverTest.java` | 240 | Resolver coverage for exact literal, replacement, append-line, bullet-list, similar-target, and preserve-rest wording. |
| `StaticTaskVerifierTest.java` | 2764 | Integration-level static verifier coverage, including most expectation behavior and user-facing summary assertions. |
| `TaskVerificationOutcomeSelector.java` | 120 | Final status/summary selection extracted in T394. |

## Source Evidence

`TaskExpectationStaticVerifier` currently owns these distinct mechanisms:

| Evidence | Current ownership |
|---|---|
| `TaskExpectationStaticVerifier.java:32` calls `TaskExpectationResolver.resolve(contract)`. | The verifier currently resolves expectations itself instead of receiving resolved expectations. |
| `TaskExpectationStaticVerifier.java:43-70` dispatches by `LiteralContentExpectation`, `ReplacementExpectation`, `AppendLineExpectation`, and `BulletListExpectation`. | One class owns four expectation families. |
| `TaskExpectationStaticVerifier.java:82-127` verifies exact literal file content and emits exact-content facts/problems. | Literal postcondition ownership. |
| `TaskExpectationStaticVerifier.java:129-147` records literal expectation trace events. | Trace recording is embedded in the verifier. |
| `TaskExpectationStaticVerifier.java:149-221` verifies replacement old/new text and delegates preserve-rest proof. | Replacement postcondition ownership. |
| `TaskExpectationStaticVerifier.java:223-287` verifies preserve-rest mutation evidence for `edit_file` and `write_file`. | Mutation-evidence proof is mixed into expectation verification. |
| `TaskExpectationStaticVerifier.java:289-310` proves one old/new replacement changes only requested text. | Text diff primitive ownership is local and shared by replacement preservation. |
| `TaskExpectationStaticVerifier.java:317-337` records replacement expectation trace events. | Redacted replacement trace recording is embedded in the verifier. |
| `TaskExpectationStaticVerifier.java:339-395` verifies append-line post-state and delegates append-only evidence proof. | Append-line postcondition ownership. |
| `TaskExpectationStaticVerifier.java:397-450` verifies append-line mutation evidence for exact edits and full writes. | Append-only mutation-evidence proof is mixed into expectation verification. |
| `TaskExpectationStaticVerifier.java:452-469` checks whether an edit appends only the requested line. | Text mutation primitive ownership is local and shared by append-line evidence. |
| `TaskExpectationStaticVerifier.java:490-509` records append-line expectation trace events. | Redacted append-line trace recording is embedded in the verifier. |
| `TaskExpectationStaticVerifier.java:520-568` verifies exact bullet/list count and rejects non-bullet prose. | Bullet-list postcondition ownership. |
| `TaskExpectationStaticVerifier.java:570-589` records bullet-list expectation trace events. | Redacted bullet-count trace recording is embedded in the verifier. |
| `TaskExpectationStaticVerifier.java:627-641` returns `verifiedAny`, expectation-kind flags, facts, and problems. | Result shape is still coupled to `TaskVerificationOutcomeSelector`. |

External use of expectation ownership is broader than final static verification:

| Consumer | Expectation dependency |
|---|---|
| `CurrentTurnPlan.java:130` | Adds resolved expectations to the current-turn plan. |
| `ExactLiteralWriteCallCorrector.java:44-78` | Uses literal expectations to override model-provided exact write payloads with runtime-parsed literal content. |
| `ActionObligationPolicy.java:34` | Uses absence of task expectations to distinguish workspace-operation obligation from mutating-tool obligation. |
| `ToolSurfacePlanner.java:156` | Uses resolved expectations to require a write-capable tool surface. |
| `ToolCallExecutionStage.java:600-625` | Uses append-line expectations for pre-approval diagnostics on risky `write_file` append attempts. |
| `ToolCallRepromptStage.java:1304-1315` | Uses replacement expectations to build exact target repair calls. |
| `ToolCallRepromptStage.java:1416-1437` | Uses append-line expectations for compact append repair. |
| `LocalTurnTraceCapture.java:492-521` | Owns the low-level redacted `EXPECTATION_VERIFIED` trace event sink. |

One additional observation matters:

`ExpectationVerificationResult.java` exists under `dev.talos.runtime.expectation`,
but source search shows it is currently unused. That is not automatically bad,
but it means there is no active result pipeline to adopt casually. Retrofitting
the whole expectation verifier to that record would be a semantic refactor, not
a low-risk extraction.

## Boundary Analysis

### Split by expectation kind

This is plausible but not the first safe step.

A direct split into `LiteralContentExpectationVerifier`,
`ReplacementExpectationVerifier`, `AppendLineExpectationVerifier`, and
`BulletListExpectationVerifier` would create coherent classes on paper. The
problem is that the current class does more than per-kind postcondition checks:

- every verifier must resolve and normalize target paths safely;
- every verifier must read workspace files with identical fail-closed wording;
- replacement and append-line verification both depend on mutation evidence;
- replacement and append-line evidence share line-ending and exact-change
  primitives;
- every verifier must preserve redacted trace semantics;
- the aggregate `Result` flags drive current summary precedence in
  `TaskVerificationOutcomeSelector`.

Splitting by kind first would either duplicate those concerns or force multiple
new abstractions in one ticket. That is too much behavior surface for the next
implementation slice.

### Split trace recording first

This is the cleanest first implementation boundary.

Trace recording is a cross-cutting concern. The verifier currently contains
four `record*Expectation(...)` methods that all format redaction-safe metadata
for `LocalTurnTraceCapture.recordExpectationVerified(...)`.

Extracting a package-private `TaskExpectationTraceRecorder` would:

- remove direct trace-formatting ownership from the verifier;
- keep the low-level trace sink in `LocalTurnTraceCapture`;
- preserve redaction behavior by moving existing payload construction without
  changing event names or fields;
- provide one stable dependency for future per-kind verifier extraction;
- avoid touching resolver behavior, mutation evidence, summary precedence, or
  user-facing wording.

This is a real ownership fix, not a line-count move.

### Split target file reading/path resolution first

This is also plausible, but second-best.

The verifier repeats target resolution, workspace containment, readability, and
`Files.readString(...)` handling for literal, replacement, append-line, and
bullet-list checks. A `TaskExpectationTargetReader` could reduce duplication.

However, the current failure messages are expectation-kind-specific:

- `exact content verification could not resolve target path`
- `replacement verification target is not a readable file`
- `appended line verification could not read target`
- `bullet count verification target is not a readable file`

Extracting target reading safely would either preserve message customization
through a parameterized helper or change user-facing wording. It is useful, but
less isolated than trace recording.

### Split mutation-evidence primitives first

This is a real future target, but not the first implementation ticket.

`replacementOnlyChangesRequestedText(...)` and
`exactEditAppendsOnlyRequestedLine(...)` are related text-mutation proof
primitives. They would fit in a small helper such as
`TaskExpectationMutationEvidence`.

The risk is that these helpers sit directly on false-success prevention. A
mistake here changes when Talos says a preserve-rest replacement or append-line
task passed. That deserves a focused implementation ticket after trace
recording is out of the way and with red/green tests around both preserve-rest
and append-line evidence.

### Retain the verifier as-is

Keeping the current verifier unchanged is defensible short term, but not the
best next engineering move.

The file is now the largest static-verification owner left in this lane. Its
current shape is coherent enough to avoid emergency refactoring, but the trace
recording concern is obviously not the same ownership as postcondition
verification. Extracting that concern prepares the file for later kind-specific
or evidence-specific splits without changing runtime behavior.

## Decision

Do not split `TaskExpectationStaticVerifier` by expectation kind yet.

Do not retrofit `ExpectationVerificationResult` yet.

Do not move resolver behavior.

Do not move mutation-evidence proof first.

The next implementation ticket should be:

```text
[T397] Extract task expectation trace recorder
```

T397 should extract only redacted expectation trace event formatting from
`TaskExpectationStaticVerifier` into a package-private verification helper,
tentatively:

```text
dev.talos.runtime.verification.TaskExpectationTraceRecorder
```

Expected T397 scope:

- Move `recordLiteralExpectation(...)`.
- Move `recordReplacementExpectation(...)`.
- Move `recordAppendLineExpectation(...)`.
- Move `recordBulletListExpectation(...)`.
- Keep event type `EXPECTATION_VERIFIED` unchanged.
- Keep all trace field names unchanged:
  - `kind`
  - `status`
  - `pathHint`
  - `sourcePattern`
  - `expectedHash`
  - `expectedBytes`
  - `expectedChars`
  - `expectedLines`
  - `observedHash`
  - `observedBytes`
  - `observedChars`
  - `observedLines`
- Keep `LocalTurnTraceCapture` as the actual trace sink.
- Keep all facts, problems, summaries, and pass/fail behavior unchanged.
- Do not touch `TaskExpectationResolver`.
- Do not touch mutation-evidence proof.
- Do not touch `TaskVerificationOutcomeSelector`.

Expected T397 tests:

- Focused red/green around the new recorder or an ownership test proving
  `TaskExpectationStaticVerifier` no longer imports `LocalTurnTraceCapture`.
- Existing trace-redaction tests must remain passing:
  - `TaskExpectationStaticVerifierTest.literalExpectationResultAndTraceStayRedacted`
  - `StaticTaskVerifierTest.literalExpectationTraceEventIsRedacted`
  - `StaticTaskVerifierTest.appendLineExpectationTraceEventIsRedacted`
  - `StaticTaskVerifierTest.replacementExpectationTraceEventIsRedacted`
- Existing summary/behavior tests for literal, replacement, append-line, and
  bullet-list verification must remain passing.

## Rejected T396 Implementations

### Extract literal/replacement/append-line/bullet-list verifiers immediately

Rejected for T396.

Reason: the expectation kinds are mixed with shared file-read handling,
mutation-evidence proof, trace recording, and final summary flags. A
kind-by-kind split is probably the future direction, but doing it before
extracting trace recording would make the first implementation too broad.

### Extract mutation-evidence proof first

Rejected for T396.

Reason: preserve-rest replacement and append-line evidence checks are
false-success prevention logic. They should move only with focused red/green
tests that prove no pass/fail behavior changed. Trace recording is the lower
risk ownership correction.

### Retrofit `ExpectationVerificationResult`

Rejected for T396.

Reason: the record is currently unused. Adopting it would change the internal
result pipeline and may affect `TaskVerificationOutcomeSelector` summary
selection. That should not be mixed with the first extraction.

### Move resolver behavior

Rejected for T396.

Reason: `TaskExpectationResolver` feeds the turn plan, tool-surface planning,
action obligation, exact write correction, execution diagnostics, repair
prompts, and static verification. Resolver changes are a separate semantic
lane.

## Acceptance Criteria

- T396 records the current `TaskExpectationStaticVerifier` boundary from fresh
  beta source.
- T396 explicitly rejects another random extraction from `StaticTaskVerifier`.
- T396 decides whether the expectation verifier should split now, stay intact,
  or move a preparatory concern first.
- T396 names the next implementation ticket and its exact scope.
- T396 changes no production runtime behavior.
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
