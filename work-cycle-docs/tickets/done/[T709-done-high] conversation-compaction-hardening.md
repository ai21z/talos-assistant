# T709 - Conversation Compaction Hardening

Status: done
Priority: high
Created: 2026-06-06

## Evidence Summary

- Source: static architecture/code review plus research synthesis
- Date: 2026-06-06
- Talos version / commit: `0.9.9` / `dd67d6864e3ccb084f1efef532930e0824ef3c15`
- Evidence:
  - `work-cycle-docs/research/context-retrieval-memory-best-techniques-from-reference-systems.md`
  - `src/main/java/dev/talos/core/context/ConversationManager.java`
  - `src/main/java/dev/talos/core/context/ConversationCompactor.java`

Expected behavior:

```text
Conversation compaction should preserve recent context, avoid splitting critical
tool/evidence pairs, verify summary quality where practical, and stop retrying
after repeated compaction failures.
```

Observed behavior:

```text
Talos has token-budget-triggered compaction and a recent-tail strategy, but the
inspected code does not prove summary verification, a consecutive-failure
circuit breaker, or explicit tool-call/tool-result pair preservation guarantees.
```

## Classification

Primary taxonomy bucket:

- `CURRENT_TURN_FRAME`

Secondary buckets:

- `OUTCOME_TRUTH`
- `TRACE_REDACTION`

Blocker level:

- future milestone

Why this level:

```text
This is context reliability infrastructure. It is not the current T707 blocker,
but bad compaction can directly cause stale or false task continuation behavior.
```

## Architectural Hypothesis

Bad ticket framing to avoid:

```text
Make memory smaller.
```

Architectural hypothesis:

```text
Compaction is a safety boundary, not a convenience function. It must preserve
approval, tool, verifier, and recent user intent evidence, or Talos can produce
truthfulness failures after long sessions.
```

Likely code/document areas:

- `src/main/java/dev/talos/core/context/ConversationManager.java`
- `src/main/java/dev/talos/core/context/ConversationCompactor.java`
- `src/main/java/dev/talos/runtime/SessionMemory.java`
- `src/main/java/dev/talos/runtime/trace/`

Why a one-off patch is insufficient:

```text
Changing a threshold does not address the failure mode. The invariant is about
what compaction is allowed to discard, how summaries are checked, and how failure
loops stop.
```

## Goal

```text
Harden Talos conversation compaction with explicit preservation rules,
summary-quality checks, and a deterministic failure circuit breaker.
```

## Non-Goals

- No vector memory.
- No hidden autonomous summarization outside the trace.
- No broad session-store rewrite.
- No release candidate bump.

## Implementation Notes

Progress note, 2026-06-06:

- T709a implemented the compaction data-loss gate and session-local failure
  breaker:
  - `ConversationCompactor.tryCompact(...)` returns explicit success/failure
    state while legacy `compact(...)` remains a string-compatible wrapper.
  - `ConversationManager` prunes old turns only after `succeeded == true`.
  - Blank/thrown compaction failures preserve the prior sketch and all verbatim
    turns.
  - Three consecutive failures skip further compaction attempts for the
    session until a success or `ConversationManager.clear()` resets the breaker.
- T709 remains open for T709b: represented tool/evidence-pair preservation,
  deterministic summary integrity/redaction checks, and visible compaction
  status in trace/debug.

Progress note, 2026-06-06:

- T709b completed the remaining deterministic hardening slice:
  - Compaction prompts now sanitize prior sketches and old turn text before
    sending them to the compaction LLM, so user-supplied secret-like values and
    private-document canaries are not reintroduced through the summarization
    call.
  - `CompactionIntegrityPolicy` now sanitizes returned sketches through the
    shared safety sanitizer before a summary can be accepted.
  - Trivial compaction outputs such as `summary omitted` / `no context` are
    rejected for substantive old turns, preserving the prior sketch and verbatim
    history.
  - Critical operational anchors represented in history, including
    `talos.*` tool names, checkpoint ids, verification/approval/blocking
    phrases, and relevant file targets, must survive the sketch or compaction
    fails closed.
  - `ConversationManager` now refuses malformed stored histories that are not
    complete user/assistant pairs before invoking the compactor or pruning.
  - Prompt audit history policy now reports `INCLUDED_COMPACTED` when compacted
    conversation context is injected, making compaction visible in
    prompt-debug and `/last trace` prompt-audit summaries.
  - T709a's failure gate and session-local circuit breaker remain in place.

Initial direction:

- Preserve a recent tail verbatim.
- Treat tool-call/tool-result, approval, checkpoint, verification, and active
  task context evidence as non-splittable units where represented in history.
- Add a bounded summary verification/probe or deterministic consistency check.
- Add a consecutive compaction failure counter and circuit breaker.
- Record compaction attempts, failures, truncation, and summary replacement in
  trace/debug state.

## Architecture Metadata

Capability:

- Conversation memory / compaction

Operation(s):

- read, summarize

Owning package/class:

- `dev.talos.core.context.ConversationManager`
- `dev.talos.core.context.ConversationCompactor`

New or changed tools:

- none

Risk, approval, and protected paths:

- Risk level: medium
- Approval behavior: none
- Protected path behavior: summaries must not unredact protected content

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: none
- Evidence obligation: compaction trace must reveal that summary replaced older
  history
- Verification profile: none
- Repair profile: none

Outcome and trace:

- Outcome/truth warnings: final answers must not treat compacted summaries as
  fresh inspection evidence
- Trace/debug fields: compaction reason, token counts, preserved tail, failure
  count, summary verification status

Refactor scope:

- Allowed: small compaction policy object if needed
- Forbidden: replacing session memory wholesale

## Acceptance Criteria

- Compaction keeps recent turns verbatim and summarizes only older/middle history.
- Compaction does not split represented tool/evidence pairs.
- Consecutive compaction failures disable further compaction attempts for the
  session until reset.
- Summary verification/probe or deterministic consistency check exists.
- Prompt-debug/trace exposes compaction status.
- Tests cover normal compaction, repeated failure breaker, redaction, malformed
  pair preservation, compaction-prompt redaction, and preservation of critical
  evidence.
- No regressions to privacy, permissions, checkpointing, trace redaction, or
  outcome truth.

## Tests / Evidence

Required deterministic regression:

- Unit test: recent tail remains verbatim.
- Unit test: failure breaker after repeated compaction failures.
- Unit test: summary does not include unredacted protected markers.
- Integration test: compacted history trace/debug state is visible.

Commands:

```powershell
.\gradlew.bat test --tests "dev.talos.core.context.*" --tests "dev.talos.runtime.*Session*" --no-daemon
.\gradlew.bat check --no-daemon
```

Completed evidence, 2026-06-06:

```powershell
.\gradlew.bat test --tests "dev.talos.core.context.ConversationCompactionTest" --tests "dev.talos.runtime.trace.PromptAuditSnapshotTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.core.context.*" --tests "dev.talos.runtime.trace.*" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.*Session*" --no-daemon
.\gradlew.bat test --tests "dev.talos.core.context.*" --tests "dev.talos.runtime.trace.*" --tests "dev.talos.runtime.*Session*" --no-daemon
```

## Work-Test Cycle Notes

- Implement with TDD.
- Do not tune thresholds without tests proving the safety invariant.

## Known Risks

- Bad summaries can erase approvals, denials, verification failures, or target
  constraints.

## Known Follow-Ups

- Candidate live audit for long-session context behavior after deterministic
  tests are green.
