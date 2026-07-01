# [T714-done-medium] Session Memory Eviction Accounting

Status: done
Priority: medium

## Evidence Summary

- Source: static code review of T709/T711 compaction and `work-cycle-docs/research/t708-t712-independent review-review.md`
- Date: 2026-06-07
- Talos version / commit: `talosVersion=0.9.9`, branch `codex/t708-project-memory-analysis`, HEAD `18b9c5b5cf5075f70850696d07438053766849ef`
- Model/backend: not applicable; deterministic memory/truthfulness follow-up
- Workspace fixture: not applicable
- Raw transcript path: not applicable
- Trace path or `/last trace` summary: not applicable
- File diff summary: no runtime failure transcript; code review found bounded but under-accounted session-memory loss channels
- Approval choices: not applicable
- Checkpoint id: not applicable
- Verification status: focused and full checks passed on 2026-06-07

Closeout evidence, 2026-06-07:

- Added `SessionMemory.RetentionEvictionStats` for non-compaction raw-turn hard-cap evictions and tool-evidence FIFO evictions.
- Added tests proving hard-cap raw turn eviction is accounted, compaction prune does not count as unsummarized hard-cap loss, tool-evidence FIFO eviction is accounted, and clear resets the counters.
- Surfaced retention status through prompt audit, prompt-debug diagnostics, prompt-audit trace event data, and `/last trace` prompt-audit rendering.
- Corrected T709/T711 done-ticket wording so it no longer overclaims absolute tool-evidence durability.
- Commands passed:
  - `.\gradlew.bat test --tests "dev.talos.core.index.*" --tests "dev.talos.core.rag.RagServiceSymbolRetrievalTest" --tests "dev.talos.runtime.SessionMemoryTest" --tests "dev.talos.runtime.trace.PromptAuditSnapshotTest" --no-daemon`
  - `.\gradlew.bat test --tests "dev.talos.core.index.*" --tests "dev.talos.core.rag.*" --tests "dev.talos.runtime.*" --tests "dev.talos.runtime.trace.*" --tests "dev.talos.cli.prompt.*" --tests "dev.talos.cli.repl.slash.*" --no-daemon`
  - `git diff --check`
  - `.\gradlew.bat check --no-daemon`

Redacted prompt sequence:

```text
Review T709/T711 compaction and operational-evidence claims against current code.
```

Expected behavior:

```text
Compaction and session-memory docs/debug fields should describe exactly which memory
channels are protected by compaction and which are independently bounded and evicted.
```

Observed behavior:

```text
T709/T711 correctly gate compaction pruning on successful compaction and separate
integrity rejections from LLM breaker failures. However, SessionMemory.update(...)
still hard-caps prose turns at MAX_TURNS and removes old pairs without producing a
sketch. SessionMemory.recordToolEvidence(...) also FIFO-caps tool evidence at
MAX_TURNS * 4. These channels are bounded, but "no data loss" or "toolEvidence is
durable / never pruned" wording overstates current behavior.
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
The issue is bounded memory accounting, not a known protected-content leak or
mutation failure. It matters because Talos should not overclaim long-session memory
durability, and users/auditors need visible evidence when old prose or tool evidence
has aged out.
```

## Architectural Hypothesis

Bad ticket framing to avoid:

```text
Increase MAX_TURNS.
```

Architectural hypothesis:

```text
Compaction and raw session retention are separate memory boundaries. T709a protects
the compaction prune path, but SessionMemory still has independent hard-cap eviction.
The product needs explicit accounting and truthful trace/debug surface for those
bounded evictions before claiming durable long-session memory.
```

Likely code/document areas:

- `src/main/java/dev/talos/runtime/SessionMemory.java`
- `src/main/java/dev/talos/core/context/ConversationManager.java`
- `src/main/java/dev/talos/core/context/ConversationCompactionStatus.java`
- `src/main/java/dev/talos/runtime/trace/*`
- `src/test/java/dev/talos/core/context/ConversationCompactionTest.java`
- `src/test/java/dev/talos/runtime/*Session*`
- `work-cycle-docs/tickets/done/[T709-done-high] conversation-compaction-hardening.md`
- `work-cycle-docs/tickets/done/[T711-done-high] compaction-operational-evidence-and-truthfulness.md`

Why a one-off patch is insufficient:

```text
This is not a single bad phrase. It is a boundary between three memory carriers:
compacted prose, raw turn history, and structured tool evidence. Their retention
semantics should be explicit and test-backed.
```

## Goal

```text
Make long-session memory retention claims truthful by documenting and testing the
hard-cap eviction channels, and by surfacing bounded eviction counts/status where
that information affects auditability.
```

## Non-Goals

- No shell/browser unless the milestone explicitly includes it.
- No MCP or multi-agent behavior unless explicitly approved.
- No LLM classifier for safety-critical permission, privacy, mutation, or verification policy.
- No giant untyped phrase dump without an owner policy.
- No bypassing approval, permission, checkpoint, trace, or verification.
- No committing raw private transcripts.
- No vector memory.
- No threshold tuning unless a test proves the current thresholds are unsafe.
- No LLM-based memory-integrity probe.
- No emergency summarizer unless separately designed and accepted.

## Implementation Notes

```text
Start with tests that capture current behavior:
- hard-cap prose eviction can occur through SessionMemory.update(...) independently
  of compaction;
- toolEvidence is FIFO-capped.

Then choose the smallest truthful product change. Likely options:
- add counters/status to SessionMemory and prompt-debug/trace;
- update ticket/docs wording to say "not pruned by compaction" instead of "never
  pruned";
- optionally surface "raw turns evicted without sketch" as a warning state.

Do not conflate this with the T709 compaction result gate; that gate is still correct.
```

## Architecture Metadata

Capability:

- Session context and memory truthfulness

Operation(s):

- remember
- compact
- trace

Owning package/class:

- `dev.talos.runtime.SessionMemory`
- `dev.talos.core.context.ConversationManager`
- `dev.talos.core.context.ConversationCompactionStatus`

New or changed tools:

- None expected

Risk, approval, and protected paths:

- Risk level: memory/truthfulness risk
- Approval behavior: unchanged
- Protected path behavior: no raw content should be exposed through new counters/status

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: not applicable
- Evidence obligation: deterministic tests and trace/debug evidence
- Verification profile: context/session tests
- Repair profile: not applicable

Outcome and trace:

- Outcome/truth warnings: long-session memory and tool evidence must be described as bounded
- Trace/debug fields: include eviction counts/status if implementation chooses visibility

Refactor scope:

- Allow small retention-accounting record/class if it keeps SessionMemory explicit.
- Do not rewrite conversation compaction architecture.

## Acceptance Criteria

- Tests prove the `SessionMemory.update(...)` hard cap can evict old prose turns independently of compaction.
- Tests prove `toolEvidence` is FIFO-capped at `MAX_TURNS * 4` or whatever constant remains after implementation.
- T709/T711 docs or ticket notes stop claiming absolute no-loss/durable evidence where the code only guarantees "not pruned by compaction."
- If counters/status are added, `/last trace` or prompt-debug exposes them without raw user/model text.
- `ConversationManager.clear()` or equivalent session reset clears any new eviction counters.
- No regressions to privacy, permissions, checkpointing, trace redaction, or outcome truth.

## Tests / Evidence

Required deterministic regression:

- Unit test: session memory hard-cap eviction accounting.
- Unit test: toolEvidence FIFO cap accounting.
- Integration/executor test: prompt-debug or trace field assertion if visibility is added.
- JSON e2e scenario: not required.
- Trace assertion: required only if new trace/debug fields are added.

Manual/TalosBench rerun:

- Prompt family: not required.
- Workspace fixture: not required.
- Expected trace: if implemented, trace should say bounded session data was evicted.
- Expected outcome: no overclaim of full durable memory.

Commands:

```powershell
.\gradlew.bat test --tests "dev.talos.core.context.*" --tests "dev.talos.runtime.*Session*" --no-daemon
.\gradlew.bat check --no-daemon
```

## Work-Test Cycle Notes

- Use the inner dev loop unless the ticket explicitly declares a candidate.
- Do not bump version unless this is candidate closeout.
- Do not update `CHANGELOG.md` unless this is candidate closeout.
- Prefer truthful accounting over threshold changes.

## Known Risks

- Adding trace fields can create noise. Keep fields compact and redaction-safe.
- An "emergency sketch" before hard-cap eviction sounds attractive but is a larger design change and may reintroduce compaction failure modes.

## Known Follow-Ups

- If long-session audits prove hard-cap loss matters in practice, design an explicit emergency-summary or retention-tier policy.
