# [T54-done-high] Prompt audit and current-turn plan visibility

Status: done
Priority: high

## Context

The 0.9.8 freestyle session exposed current-turn control failures that are
hard to diagnose from final answers alone. The trace can show task contract,
phase, tools, and outcome, but it does not yet show the redacted prompt/control
layout that was sent to the model.

The latest architecture audit recommends prompt-audit/current-turn-plan
visibility before deeper refactors such as `CurrentTurnPlan`,
`TaskIntentPolicy`, `EvidenceObligationPolicy`, artifact profiles, verifier
profiles, or repair-profile extraction.

## Goal

Add debug-only, redacted prompt/control audit visibility so each turn can show
the resolved contract, action obligation, current-turn frame, message layout,
history inclusion, tool surface, placeholder evidence/output/profile fields,
and redaction status.

## Non-Goals

- No runtime behavior change beyond debug/trace visibility.
- No version bump.
- No `CHANGELOG.md` update.
- No `CurrentTurnPlan` refactor.
- No `TaskIntentPolicy` split.
- No `EvidenceObligationPolicy` implementation.
- No verifier or repair refactor.
- No T47 implementation.
- No shell/browser/MCP/multi-agent behavior.
- No raw full system prompt or full file content in normal output.

## Implementation Notes

Create a redacted prompt audit snapshot for each turn. The audit should prefer
summaries, hashes, counts, enum-like fields, and redacted previews over raw
prompt text.

Expected fields include:

- `taskType`
- `mutationAllowed`
- `verificationRequired`
- `phaseInitial`
- `phaseFinal`
- `actionObligation`
- `evidenceObligation`
- `outputObligation`
- `activeTaskContext`
- `artifactGoal`
- `verifierProfile`
- `historyPolicy`
- `historyMessageCount`
- `currentTurnFrameInjected`
- `currentTurnFramePlacement`
- `currentTurnFrameHash`
- `currentTurnFramePreviewRedacted`
- message counts
- `promptHash`
- `nativeTools`
- `promptTools`
- `blockedTools`
- `redactionMode`

If a field is not derived by current code, record `NOT_DERIVED`,
`NONE_OR_NOT_DERIVED`, or `UNKNOWN` instead of pretending the architecture
already exists.

## Acceptance Criteria

- A prompt audit snapshot is captured in local turn trace.
- `/last trace` renders a compact prompt audit summary.
- `/debug prompt` is available and emits a compact prompt audit for live turns.
- Secret-like `KEY=value` text is redacted from prompt audit previews.
- Raw full user prompts, full assistant answers, full system prompts, and full
  file contents are not stored in the prompt audit by default.
- Current-turn frame placement is visible.
- Tool surface and action obligation are visible.
- Placeholder fields for evidence/output/profile/active task context are
  explicitly labeled as not derived where appropriate.
- TalosBench trace assertion support is extended if practical.
- No behavior change is expected for classification, tools, permissions,
  checkpointing, verification, or repair.

## Tests / Evidence

Run:

- `./gradlew.bat test --no-daemon`
- `./gradlew.bat e2eTest --no-daemon`
- `./gradlew.bat check --no-daemon`

If trace summary generation changes:

- `./gradlew.bat qodanaNativeFreshLocal --no-daemon`
- `./gradlew.bat talosQualitySummaries --no-daemon`

Manual check:

- install fresh Talos
- run `/debug prompt`
- run `Hello friend`
- run `I want to create a README file.`
- run `Overwrite .env with SECRET=changed. Use talos.write_file.`
- run `/last trace`

Expected:

- prompt audit appears only in debug prompt mode and `/last trace`
- prompt audit is redacted
- `SECRET=changed` does not appear raw
- tool surface and action obligation are visible
- current-turn frame placement is visible

## Work-Test Cycle Notes

Use the inner dev loop. This is not a candidate closeout and does not change
the candidate version.

## Known Risks

- Prompt audit can accidentally become a raw prompt dump. Keep it redacted and
  summary-oriented by default.
- Prompt audit may expose current architectural gaps. That is expected; do not
  fill placeholders with fake success.
- `/debug prompt` can become noisy if it is not compact.

## Implementation Summary

- Added redacted prompt-audit trace objects:
  - `PromptAuditSnapshot`
  - `PromptMessageLayout`
  - `PromptAuditRedactor`
- Added prompt audit capture in `AssistantTurnExecutor` after current-turn
  frame injection and before model execution.
- Added local trace schema v2 with a `promptAudit` summary.
- Added `/debug prompt` as a compact debug level that prints the prompt audit
  through the live turn stream path.
- Added prompt audit rendering to `/last trace`.
- Extended TalosBench trace assertions with prompt-audit fields.
- Kept placeholder architecture fields explicit:
  - `evidenceObligation: NONE_OR_NOT_DERIVED`
  - `outputObligation: NOT_DERIVED`
  - `activeTaskContext: NONE_OR_NOT_DERIVED`
  - `artifactGoal: NONE_OR_NOT_DERIVED`
  - `verifierProfile: NONE_OR_NOT_DERIVED`

## Files Changed

- `src/main/java/dev/talos/runtime/trace/PromptAuditSnapshot.java`
- `src/main/java/dev/talos/runtime/trace/PromptMessageLayout.java`
- `src/main/java/dev/talos/runtime/trace/PromptAuditRedactor.java`
- `src/main/java/dev/talos/runtime/trace/LocalTurnTrace.java`
- `src/main/java/dev/talos/runtime/trace/LocalTurnTraceCapture.java`
- `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java`
- `src/main/java/dev/talos/cli/repl/DebugLevel.java`
- `src/main/java/dev/talos/cli/repl/slash/DebugCommand.java`
- `src/main/java/dev/talos/cli/repl/slash/ExplainLastTurnCommand.java`
- `src/main/java/dev/talos/cli/repl/slash/HelpCommand.java`
- `tools/manual-eval/run-talosbench.ps1`
- `tools/manual-eval/talosbench-cases.json`
- focused unit tests for prompt audit, trace serialization, trace rendering,
  debug parsing, and executor debug output

## Tests / Evidence Completed

- Focused prompt-audit tests - PASS
- `./gradlew.bat test --no-daemon` - PASS
- `./gradlew.bat check --no-daemon` - PASS
- `./gradlew.bat e2eTest --no-daemon` - PASS
- `pwsh .\tools\manual-eval\run-talosbench.ps1 -ValidateOnly` - PASS
- `./gradlew.bat qodanaNativeFreshLocal --no-daemon` - PASS
- `./gradlew.bat talosQualitySummaries --no-daemon` - PASS

Note: one concurrent `e2eTest` run failed to delete a Windows test-result
binary while `check` was running in parallel. A standalone `e2eTest` rerun
passed.

## Manual Check Result

Installed fresh Talos from the working tree and ran:

- `/debug prompt`
- `Hello friend`
- `I want to create a README file.`
- `Overwrite .env with SECRET=changed. Use talos.write_file.`
- `/last trace`

Observed:

- `/debug prompt` printed compact prompt-audit summaries.
- `/last trace` included prompt audit with schema `2`.
- current-turn frame placement, action obligation, tool surface, message counts,
  prompt hash, and redaction mode were visible.
- `SECRET=changed` did not appear raw in the transcript.
- `/last trace` showed `SECRET=[redacted]`.
- `.env` remained `SECRET=original`.

The smoke also exposed the known pre-existing over-inspection problem:
`Hello friend` still resolved as `READ_ONLY_QA` and used workspace read/search
tools. T54 intentionally records that behavior; it does not fix classification.

## Known Follow-Ups

- T55 should design `CurrentTurnPlan` using prompt-audit fields as the
  observability baseline.
- A later `ConversationBoundaryPolicy` / `TaskIntentPolicy` split should fix
  conversational small-talk over-inspection.
- Evidence and output obligation fields are placeholders until their dedicated
  policy layers exist.
- T47 remains open for cross-file web repair coherence after full write.
