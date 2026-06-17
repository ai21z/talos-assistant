# [T831-done-high] ToolCallSupport Result Formatting Extraction

Status: done
Priority: high
Date: 2026-06-17
Branch: `v0.9.0-beta-dev`
Candidate version: `0.10.5`
Predecessor: `[T830-done-high] tool-call-support-native-call-conversion-extraction`

## Why This Ticket Exists

T829 scoped the broad `ToolCallSupport` helper surface. T830 extracted the
native-call conversion seam. The next narrow high-value seam is prompt-visible
tool-result formatting: result envelopes, protected-content sanitization,
truncation, verification-status rendering, and first-sentence summaries.

This is trust-surface work because formatted tool results are model-context
payloads. The extraction must preserve exact visible behavior while reducing
`ToolCallSupport` ownership.

## Scope

In scope:

- Add package-private `ToolResultFormatter` in `dev.talos.runtime.toolcall`.
- Move only `formatToolResult(...)`, `extractVerificationSummary(...)`,
  `firstSentenceSummary(...)`, and private helper logic required by those
  methods.
- Keep `ToolCallSupport` public/static delegates stable.
- Keep `ToolCallLoop` static delegates stable.
- Add focused formatter tests for exact prompt-visible behavior.

Out of scope:

- No `summarizeToolResult(...)` or `compactOlderToolResultsInPlace(...)` move.
- No native-call conversion changes.
- No retry/request extraction.
- No path/call repair extraction.
- No `LoopState`, stage, or `ExecutionOutcome` move.
- No Qodana changes.
- No candidate recut.
- No `SetupCmd.java`, `.claude/`, release metadata, or `site/` edits.

## Acceptance Criteria

- Tool-result success and error envelopes are unchanged.
- Protected-content sanitization remains the default.
- `preserveSuccessOutput=true` still preserves raw successful output.
- Blank successful output still renders `(empty result)`.
- Long successful output still truncates at 32K characters with the existing
  suffix.
- Verification status still renders as `[verification_status: NAME]`.
- Verification warning extraction remains unchanged.
- First-sentence summary stripping and 160-character cap remain unchanged.
- `ToolCallSupport` and `ToolCallLoop` delegates remain callable.
- `ToolResultFormatterTest`, `ToolCallSupportBoundaryCharacterizationTest`,
  `ToolCallSupportTest`, `ToolCallLoopTest`, `ToolCallLoopP0Test`,
  `ToolProgressUXTest`, protected-read/model-context handoff tests,
  `runtime.toolcall.*`, `ToolCallLoop*`, full `check`, and
  `wikiEvidenceCloseGate --rerun-tasks` pass.

## Completion Evidence

Implementation commit: `93313de56b22a93a02af9515828e00a3a77947f8`.

T831 extracted prompt-visible tool-result formatting into package-private
`ToolResultFormatter` while keeping `ToolCallSupport` and `ToolCallLoop`
delegates stable. It preserved success and error envelopes, default
protected-content sanitization, raw output preservation for successful results
when explicitly requested, blank-output fallback, 32K truncation, verification
status rendering, verification warning extraction, and first-sentence summary
behavior.

Verified gates:

- `ToolResultFormatterTest`: `5` tests, `0` failures, `0` errors.
- `ToolCallSupportBoundaryCharacterizationTest`: green.
- `ToolCallSupportTest`: green.
- `ToolCallLoopTest`, `ToolCallLoopP0Test`, `ToolProgressUXTest`: green.
- `ProtectedReadScopeIntegrationTest`: `18` tests, `0` failures, `0` errors.
- `ToolResultModelContextHandoffTest`: `6` tests, `0` failures, `0` errors.
- `runtime.toolcall.*`: green.
- `ToolCallLoop*`: green.
- `check --no-daemon`: `15/15` tasks executed, build successful.
- `wikiEvidenceCloseGate --rerun-tasks --no-daemon`: build successful,
  including architecture intelligence contract `8/8`.

No `site/`, Qodana, candidate recut, `SetupCmd.java`, `.claude/`, release
metadata, compaction, retry/request extraction, path/call repair, stage, or
`ExecutionOutcome` changes were made by T831.
