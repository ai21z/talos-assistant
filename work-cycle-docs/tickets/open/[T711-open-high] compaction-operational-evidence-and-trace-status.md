# T711 - Compaction Operational Evidence And Trace Status

Status: open
Priority: high
Created: 2026-06-06

## Evidence Summary

- Source: static code review of T709b commit `717d6aab`
- Branch: `v0.9.0-beta-dev`
- Talos version: `0.9.9`
- Reviewed files:
  - `src/main/java/dev/talos/core/context/CompactionIntegrityPolicy.java`
  - `src/main/java/dev/talos/core/context/ConversationCompactor.java`
  - `src/main/java/dev/talos/core/context/ConversationManager.java`
  - `src/main/java/dev/talos/runtime/MemoryUpdateListener.java`
  - `src/main/java/dev/talos/runtime/SessionMemory.java`
  - `src/main/java/dev/talos/runtime/trace/PromptMessageLayout.java`
  - `work-cycle-docs/tickets/done/[T709-done-high] conversation-compaction-hardening.md`

Expected behavior:

```text
Compaction hardening should preserve and expose operational evidence that matters
for long-session truthfulness: tool calls, approvals/denials, checkpoint ids,
verification failures, compacted-history status, and failure reasons.
```

Observed behavior:

```text
T709/T709b is fail-safe for prose history and sketch redaction, but the critical
anchor gate inspects only ChatMessage prose. In normal runtime, tool evidence is
stored separately in SessionMemory.toolEvidence and is not passed to
CompactionIntegrityPolicy. Prompt-debug only exposes INCLUDED_COMPACTED, while
compaction attempt reason, failure count, and integrity status remain logs.
```

## Classification

Primary taxonomy bucket:

- `CURRENT_TURN_FRAME`

Secondary buckets:

- `TRACE_REDACTION`
- `OUTCOME_TRUTH`
- `TOOL_EXECUTION`

Blocker level:

- future milestone / high-priority reliability follow-up

Why this level:

```text
No immediate data-loss defect was found. T709a prevents destructive pruning on
failed compaction, and toolEvidence is not pruned by compaction. The remaining
risk is truthfulness and reliability: the ticket wording overstates operational
evidence protection, and integrity rejections can trip the same breaker used for
LLM/transport failures.
```

## Confirmed Findings

### F1 - Operational anchor preservation is prose-only

Evidence:

- `MemoryUpdateListener.onTurnComplete(...)` records tool calls separately via
  `memory.recordToolEvidence(...)` before storing assistant prose.
- `SessionMemory.toolEvidence` stores `ToolEvidence(turnNumber, toolName,
  pathHint, success)` separately from `turns`.
- `SessionMemory.pruneOldest(...)` prunes only `turns` and rebuilds the flat
  prose buffer.
- `CompactionIntegrityPolicy.validate(...)` calls `join(oldTurns)` and therefore
  sees only `ChatMessage` prose, not `SessionMemory.toolEvidence`.

Impact:

```text
The current `talos.*` anchor gate is correct for synthetic prose inputs, but it
is weak in production because real tool-call names are usually not part of the
stored prose. The real protection for tool evidence is separate storage, not the
integrity policy.
```

### F2 - Integrity rejections share the LLM failure breaker

Evidence:

- `ConversationManager.maybeCompactWith(...)` increments
  `consecutiveCompactionFailures` for every `!result.succeeded()`.
- `CompactionIntegrityPolicy` returns failure for deterministic integrity
  rejections such as `trivial-summary` and `critical-evidence-missing:*`.
- After three failures, the session breaker skips compaction attempts.

Impact:

```text
This is safe short-term because old turns are preserved, but it can disable
compaction for the session. Long sessions remain bounded by SessionMemory caps,
so the risk is not literal unbounded growth; the risk is bounded,
unsummarized history that can eventually age out by hard cap without a compact
sketch.
```

### F3 - Compaction trace/debug status is only partial

Evidence:

- `PromptMessageLayout` reports `INCLUDED_COMPACTED` when `[Conversation
  context]` is present.
- Compaction trigger, failure reason, token counts, failure count, and integrity
  status are logged through SLF4J, but are not represented as prompt-debug or
  local trace fields.
- The T709 done ticket still names richer trace/debug fields: compaction reason,
  token counts, preserved tail, failure count, and summary verification status.

Impact:

```text
The current implementation provides useful visibility that compacted history was
included, but it does not fully satisfy rich compaction status visibility.
```

## Goal

```text
Make compaction operational-evidence preservation and trace/debug status
truthful and explicit without weakening T709a's data-loss gate.
```

## Non-Goals

- No vector memory.
- No LLM-based summary verification probe.
- No automatic rollback.
- No broad session-store rewrite.
- No threshold tuning without tests.

## Implementation Direction

Progress note, 2026-06-06:

- Primary path selected: honest scoping rather than feeding durable
  `toolEvidence` into the prose sketch gate.
- Reason: `SessionMemory.toolEvidence` is already durable and is not pruned by
  `SessionMemory.pruneOldest(...)`; forcing sketches to re-echo tool names would
  add brittleness without improving the authoritative evidence store.
- Turn-number plumbing is the real cost of a future evidence-fed gate:
  `CompactionIntegrityPolicy.validate(...)` receives a bare `List<ChatMessage>`,
  while `SessionMemory.toolEvidence` is keyed by turn number. Do not add that
  plumbing until a concrete sketch-as-sole-carrier need appears.
- Implemented slice: `CompactionResult` now carries a result category, and
  deterministic integrity rejections no longer consume the LLM/output failure
  breaker.
- T711 remains open for richer trace/debug status fields unless a later batch
  deliberately narrows and closes that criterion.

1. Explicitly separate prose-anchor integrity from durable operational evidence:
   - `CompactionIntegrityPolicy` checks represented `ChatMessage` prose only;
   - `SessionMemory.toolEvidence` remains the durable tool-call evidence store;
   - ticket and code wording must not imply that the prose sketch gate protects
     real runtime tool evidence.
2. Do not require all prose anchors verbatim. Prefer evidence-class preservation:
   - at least one meaningful anchor per represented class;
   - path basename or normalized target matching where appropriate;
   - deterministic rules only.
3. Distinguish compaction result categories:
   - LLM/transport failure;
   - blank/malformed output;
   - deterministic integrity rejection;
   - malformed local history.
4. Do not let deterministic integrity rejections blindly trip the same breaker
   intended for repeated LLM/transport failures.
5. Expose compaction status in prompt-debug/local trace in a later focused slice:
   - attempted/skipped;
   - reason;
   - failure count;
   - old-turn count;
   - preserved tail count;
   - integrity status.

## Architecture Metadata

Capability:

- Conversation memory / compaction

Operation(s):

- read, summarize, trace

Owning package/class:

- `dev.talos.core.context.ConversationManager`
- `dev.talos.core.context.ConversationCompactor`
- `dev.talos.core.context.CompactionIntegrityPolicy`
- `dev.talos.runtime.SessionMemory`
- `dev.talos.runtime.trace.*`

New or changed tools:

- none

Risk, approval, and protected paths:

- Risk level: medium
- Approval behavior: none
- Protected path behavior: compaction prompts and sketches must remain sanitized

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: none
- Evidence obligation: compaction trace/debug must expose status and reason
- Verification profile: none
- Repair profile: none

Outcome and trace:

- Outcome/truth warnings: final answers must not treat compacted summaries as
  fresh inspection evidence
- Trace/debug fields: compaction attempt status, result category, reason,
  failure count, summarized turn count, preserved tail count, and integrity
  status

Refactor scope:

- Allowed: small value object for compaction status/result category; carefully
  scoped trace/debug fields
- Deferred: operational-evidence input object for integrity policy until a
  concrete need appears
- Forbidden: replacing session memory wholesale; adding vector memory

## Acceptance Criteria

- The integrity policy is honestly scoped to represented prose
  `ChatMessage` text.
- Tool evidence preservation claims are removed from prose-sketch wording unless
  a later ticket explicitly feeds aligned operational evidence into the gate.
- Tests prove `SessionMemory.pruneOldest(...)` preserves structured
  `toolEvidence`, so the true durable evidence mechanism is covered.
- Integrity rejections are distinguishable from LLM/transport failures and do
  not blindly trip the same breaker.
- Prompt-debug/local trace exposes compacted-history status beyond the
  `INCLUDED_COMPACTED` label, or this remaining criterion is explicitly left
  open with this ticket.
- Existing T709a guarantees remain intact: no prune on failed compaction,
  repeated LLM failures trip a session breaker, and `clear()` resets the breaker.
- Tests cover prose-only compaction, `SessionMemory.toolEvidence` preservation,
  and integrity rejection category handling.

## Tests / Evidence

Required deterministic tests:

- Unit test: `SessionMemory.pruneOldest(...)` preserves represented
  `SessionMemory.toolEvidence` classes.
- Unit test: integrity rejection does not increment the LLM/transport failure
  breaker, or increments a distinct counter with distinct trace status.
- Unit test: repeated actual LLM/transport failures still trip the breaker.
- Trace/prompt-debug test: compaction attempt reason and result category are
  visible. This remains open if not implemented in the current slice.
- Regression test: T709 prompt/sketch redaction and data-loss gate still pass.

Commands:

```powershell
.\gradlew.bat test --tests "dev.talos.core.context.*" --tests "dev.talos.runtime.trace.*" --tests "dev.talos.runtime.*Session*" --no-daemon
.\gradlew.bat check --no-daemon
```

Completed slice evidence, 2026-06-06:

```powershell
.\gradlew.bat test --tests "dev.talos.core.context.ConversationCompactionTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.core.context.ConversationCompactionTest" --tests "dev.talos.runtime.MemoryUpdateListenerTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.core.context.*" --tests "dev.talos.runtime.trace.*" --tests "dev.talos.runtime.*Session*" --no-daemon
.\gradlew.bat check --no-daemon
```

Implemented in this slice:

- `CompactionResult` now carries an explicit result category.
- `INTEGRITY_REJECT` no longer consumes the LLM/output failure breaker.
- `BLANK_OUTPUT` and `LLM_FAILURE` still consume the breaker.
- `SessionMemory.pruneOldest(...)` has regression coverage proving it preserves
  structured `toolEvidence`.
- T709's done ticket now cross-references T711 for richer trace/debug status and
  any future operational-evidence integration.

Still open:

- Prompt-debug/local trace fields for compaction reason, count, summarized-turn
  count, preserved-tail count, and integrity status.

## Known Risks

- Over-strict anchor checks can disable compaction in exactly the long sessions
  where compaction matters.
- Under-strict checks can produce sketches that omit operational constraints.

## Known Follow-Ups

- T708 hierarchical project memory and T710 structure-first code retrieval remain
  separate open tickets.
