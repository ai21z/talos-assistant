# [T828-done-high] ToolCallExecutionStage Guard Chain Extraction

Status: done
Priority: high
Date: 2026-06-16
Branch: `v0.9.0-beta-dev`
Candidate version: `0.10.5`
Predecessor: `[T827-done-high] architecture-intelligence-qodana-summary-ordering`

## Why This Ticket Exists

T826 characterized direct `ToolCallExecutionStage.execute(...)` behavior before
production decomposition. T827 made the architecture evidence gate deterministic
before the first production cut.

T828 performs the first behavior-preserving decomposition of
`ToolCallExecutionStage`: extract the pre-execution guard chain into a
package-private collaborator while preserving the public stage API, message
shape, approval/trace/ledger behavior, mutation/failure accounting, and
edit-repair ordering.

## Scope

In scope:

- Add package-private `ToolCallPreExecutionGuardChain` in
  `dev.talos.runtime.toolcall`.
- Move only pre-execution guard orchestration out of
  `ToolCallExecutionStage.execute(...)`.
- Preserve the current guard ordering:
  `EditFilePreApprovalGuard` and `RedundantReadSuppressionGuard` run before
  `state.totalToolsInvoked++`; `PrivateDocumentNamedTargetGuard` runs after the
  call is counted; source-evidence exact-write repair can update the effective
  call and path context without blocking.
- Keep block paths responsible for the same outcome records, failure counts,
  result messages, trace/progress emissions, and loop `continue` behavior.
- Update T826 report/test wording from the deferred T827 extraction boundary to
  the actual T828 extraction boundary.

Out of scope:

- No public `ToolCallExecutionStage` API change.
- No `IterationOutcome` change.
- No `LoopState`, `ToolCallSupport`, `ToolCallParseStage`,
  `ToolCallRepromptStage`, `ToolCallLoop.LoopResult`,
  `ToolCallLoop.ToolOutcome`, or `ExecutionOutcome` move.
- No post-execution accounting move.
- No Qodana changes.
- No candidate recut.
- No `SetupCmd.java` edits.
- No `site/` staging.

## Acceptance Criteria

- T826 characterization remains green.
- Text/native result-message shape remains unchanged.
- Private-document blocking still proves no approval, no read body, trace block
  event, and `totalToolsInvoked == 1`.
- Approval denial, context ledger, mutation/failure/edit-repair accounting
  remain unchanged.
- Full `check` passes.
- `wikiEvidenceCloseGate --rerun-tasks` passes.
- `site/` remains untouched and unstaged.

## Completion Evidence

- Implementation commit: `4d45b3ed54b50bdf75ceb457b298a572a0783d7a`.
- Added package-private `ToolCallPreExecutionGuardChain` in
  `dev.talos.runtime.toolcall`.
- Preserved public `ToolCallExecutionStage.execute(...)` and
  `IterationOutcome`.
- Preserved text/native result-message shape, approval denial behavior,
  private-document blocking evidence, context-ledger decisions, mutation
  accounting, failure accounting, and edit-repair accounting.
- Fresh focused/security suites passed:
  `ToolCallExecutionStageCharacterizationTest`, `runtime.toolcall.*`,
  `ToolCallLoop*`, `ProtectedReadScopeIntegrationTest`,
  `ToolResultModelContextHandoffTest`, and `LocalTurnTraceCommandTest`.
- Full `check --no-daemon` passed.
- `wikiEvidenceCloseGate --rerun-tasks --no-daemon` passed.
- `ArchitectureIntelligenceReportContractTest` passed 8/8 through the evidence
  gate.
- `git diff --check -- . ':!site'` passed.
- `site/` remained unrelated owner work and was not staged.

## T829 Preview

After T828, the next planned move is T829
`tool-call-support-boundary-scoping`. It should characterize and scope the
broad `ToolCallSupport` helper surface before choosing a T830 production
extraction seam.
