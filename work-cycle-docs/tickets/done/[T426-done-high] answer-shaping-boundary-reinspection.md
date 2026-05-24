# [T426-done-high] Answer Shaping Boundary Reinspection

## Status

Done.

## Scope

T426 reinspects the post-T425 answer-shaping responsibilities in
`AssistantTurnExecutor` and `ExecutionOutcome`.

This is intentionally a no-code decision ticket. T425 moved denied
protected-read summary rendering into `ProtectedReadAnswerGuard`; this ticket
decides the next coherent owner before another extraction.

## Source Evidence

`ExecutionOutcome` still calls `AssistantTurnExecutor` for these
answer-shaping lanes:

- unsupported document content-claim correction;
- static-web import, diagnostic, selector-search, and selector-mismatch
  answer overrides;
- inspect-under-completion annotation;
- malformed tool-protocol replacement;
- negative local workspace access correction;
- streaming no-tool truthfulness;
- no-tool grounding retry.

`AssistantTurnExecutor` still contains the implementation details for those
lanes. The relevant source clusters are:

- `overrideUnsupportedDocumentClaimsIfNeeded(...)` and unsupported-document
  helpers;
- static-web override helpers around selector mismatch, selector search, import
  answers, and read-only web diagnostics;
- inspect-first and missing-inspection helpers;
- no-tool and streaming answer-shaping helpers;
- direct deterministic answers, session evidence follow-up, and read-evidence
  recovery.

## Decision

Do not extract a broad `AnswerShaper`, `TruthfulnessManager`, or static-web
diagnostic mover.

The next implementation slice should be:

```text
[T427] Extract unsupported document answer guard
```

T427 should move only unsupported-document answer correction into runtime
outcome ownership, likely:

```text
dev.talos.runtime.outcome.UnsupportedDocumentAnswerGuard
```

The extraction should preserve exact wording and behavior for:

- unsupported document capability notes;
- removal of unsupported binary-document content claims;
- unsupported search notes;
- successful supported text-file read exemptions;
- advisory unsupported-document outcome state.

## Why T427 Is The Correct Next Slice

Unsupported-document answer correction is a coherent outcome-truthfulness
responsibility. It is not a streaming concern, not a static-web verifier concern,
not an LLM retry concern, and not a CLI orchestration concern.

It already sits next to `UnsupportedDocumentCapabilityOutcome` in the outcome
classification flow, and `ExecutionOutcome` already invokes it as one answer
post-processing step before other dominance decisions.

Moving it should reduce `AssistantTurnExecutor` responsibility without changing
runtime behavior or broadening policy.

## Rejected Next Slices

Static-web answer overrides are rejected for the next ticket. They remain
coupled to static-web diagnostic semantics, selector mismatch reasoning, linked
script evidence, and previously rejected static-web diagnostic movement.

No-tool grounding and streaming truthfulness are rejected for the next ticket.
They mix retry behavior, stream visibility, local-access capability correction,
and task-contract evidence requirements.

Inspect-under-completion annotation is rejected for the next ticket. It is
coupled to inspect-first intent, missing-read detection, retry eligibility, and
static-web exceptions.

Direct deterministic answers and session evidence follow-up are rejected for the
next ticket. They are broader turn-orchestration concerns, not a small
answer-rendering slice.

## T427 Guardrails

T427 should:

- start from fresh `origin/v0.9.0-beta-dev`;
- add a focused RED ownership test for the new unsupported-document answer
  guard;
- preserve existing final-answer text exactly;
- keep preflight and document mutation policy untouched;
- keep static-web, no-tool, streaming, and inspect-under-completion helpers
  untouched;
- keep any `AssistantTurnExecutor` wrapper only if needed for compatibility
  tests;
- run focused unsupported-document and execution-outcome tests;
- run `validateArchitectureBoundaries`;
- run full `check`.

## Verification

Passed:

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```

## Next Correct Move

After T426 integrates cleanly, start T427 from fresh beta and extract only the
unsupported-document answer guard.
