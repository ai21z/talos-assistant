# [T832-done-high] In-turn compaction evidence and conditional gist

Status: done
Priority: high
Opened: 2026-06-17
Closed: 2026-06-17
Branch: v0.9.0-beta-dev
Base commit: 4edb69cfcc7474f409b786f3d40ee4ddc8e965f2
Phase 1 commit: 4de4bba6ecdd512ec487543501ac4e1c69d6fbdf
Talos version: 0.10.5

## Scope

T832 Phase 1 is evidence and characterization only. It investigates the
in-turn tool-loop compaction behavior without changing production `src/main`
code.

This ticket follows T831 result-formatting extraction. It does not reopen
result formatting, native-call conversion, path/call repair, retry/request
extraction, Qodana wiring, candidate cutting, `SetupCmd.java`, `.claude/`, or
release metadata.

## Current Behavior Anchors

- In-turn compaction is `ToolCallSupport.compactOlderToolResultsInPlace(...)`.
- `ToolCallRepromptStage` calls it only when `state.iterations >= 3`.
- It keeps the last `KEEP_RECENT_TOOL_RESULTS = 2` tool-role results verbatim.
- Older nonblank, non-compacted tool-role results become
  `[compacted: ...]` stubs with tool name, success/error kind, and character
  count.
- Already compacted messages are skipped.
- `latestUserRequestIn(...)` skips synthetic `[compacted:]` user messages.
- `LoopState.readFileBodiesThisTurn` retains read-file bodies for verification
  state, but compacted prompt messages do not rehydrate elided content back into
  the model prompt.
- This in-turn path is separate from session-level `core.context`
  `ConversationManager` compaction.

## Phase 1 Deliverables

- Add `ToolCallLoopCompactionBehaviorCharacterizationTest` with behavioral
  assertions for current compaction mechanics.
- Add `work-cycle-docs/reports/t832-in-turn-compaction-evidence-and-conditional-gist.md`.
- Measure existing local prompt-debug/provider-body artifacts when available.
- Give a clear Phase 1 answer on whether current compaction measurably harms
  answer quality.
- Leave production compaction behavior unchanged.

## Phase 1 Evidence Summary

The initial local artifact scan found 738 provider-body artifacts containing
`[compacted:]` out of 12,571 provider-body JSON files under `local/`. Of those,
700 were parseable for the Phase 1 measurement.

Report hygiene added the exact `local/**/*provider-body*.json` scan script. A
current rescan found 740 files containing `[compacted:]` and 702 parseable
compacted provider bodies. The difference is expected because `local/` is a
live artifact corpus that audit and test runs can extend between scans. The
12,571 provider-body file count is the exact count for the recorded glob at the
time of the hygiene rescan.

The parsed artifacts show same-turn re-read proxy evidence in older audit
artifacts, especially under `gpt-oss-20b` runs. The proxy means a provider body
contained a compacted `talos.read_file` result and multiple same-path
`talos.read_file` calls in the same message history. It is not causal proof that
compaction harmed the answer.

Phase 1 does not prove a measurable answer-quality regression. It does prove
that compaction fires in real local artifacts, that the stub is char-count-only,
and that a low-risk Phase 2 gist-in-stub investigation remains reasonable after
review.

## Deferred

T832 Phase 1 does not authorize:

- gist-in-stub production changes,
- token-pressure-triggered compaction,
- prompt rehydration of elided tool results,
- changes to session-level `core.context` compaction,
- changes to protected-content handling.

If a later Phase 2 adds a gist to the compaction stub, the gist must derive from
the already-sanitized stored prompt message content. It must never bypass
protected-content redaction by reading raw protected file bodies.

## Acceptance

- New characterization test is behavioral and does not source-grep Java files.
- `clean check --no-daemon` passes.
- `wikiEvidenceCloseGate --rerun-tasks --no-daemon` passes.
- `site/` remains untouched and unstaged.
- T832 closes after Phase 1 review and report-hygiene reconciliation.

## Completion Evidence

- T832 Phase 1 commit `4de4bba6ecdd512ec487543501ac4e1c69d6fbdf` added
  `ToolCallLoopCompactionBehaviorCharacterizationTest`, the Phase 1 report,
  this ticket, and wiki updates with zero production `src/main` changes.
- The characterization test has 4 tests, 0 failures, and 0 errors.
- Full `clean check --no-daemon` passed with unit and E2E test result XML
  showing 0 failures and 0 errors.
- `wikiEvidenceCloseGate --rerun-tasks --no-daemon` passed.
- The report now records the exact artifact scan script, original Phase 1
  counts of 738 and 700, and current hygiene rescan counts of 740 and 702.
- Phase 2 gist-in-stub, token-pressure triggering, and prompt rehydration
  remain deferred and unimplemented.
