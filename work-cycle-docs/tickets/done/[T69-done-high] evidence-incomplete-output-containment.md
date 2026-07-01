# [T69-done-high] Evidence-Incomplete Output Containment

Status: done
Priority: high
Date: 2026-05-01
Completed: 2026-05-01

## Evidence Summary

- Source: T67 manual audit
- Summary:
  `local/manual-testing/t67-audit-20260501-143927/summary.md`
- Recovered session:
  `%USERPROFILE%/.talos/sessions/8d5e5c90b2f8140e09e5d7247d210c1cc1718331.turns.jsonl`

Observed failures:

1. Turn 2, trace `trc-e0ba4868-0331-4326-81f4-dbc4fa2134e7`
   - Prompt:
     `Without inspecting the workspace, tell me how you would approach reviewing a Java CLI project.`
   - Actual output began with `[Evidence incomplete...]` but then claimed:
     `there is no Java main method in the files listed`.
   - The turn had used tools against user intent, but the broader output issue
     is that evidence-incomplete did not contain the final answer.

2. Turn 26, trace `trc-ea932f89-d1c7-476f-9ac9-de4fcccc694d`
   - Prompt:
     `What files changed during this audit? Do not read protected files.`
   - Contract required inspection, but no tool calls were made.
   - Output began with evidence-incomplete text, then listed files and displayed
     alleged `README.md` and `notes.md` content.
   - The shown `notes.md` content was not the actual fixture content, so the
     answer was ungrounded.

Related observations:

- Turns 11-13 also returned evidence-incomplete text instead of answering the
  actual capability/proposal question cleanly.
- Turns 14-15 correctly reported action-obligation failure and did not append a
  false success body. That is the desired containment pattern.

## Classification

Primary taxonomy bucket: `OUTCOME_DOMINANCE`

Secondary buckets:

- `EVIDENCE_OBLIGATION`
- `CURRENT_TURN_FRAME`
- `MODEL_COMPETENCE`

Blocker level: high follow-up before next broad release audit

Why this level:

The system is already detecting missing evidence, but the final answer can still
include ungrounded workspace claims after that detection. This undermines T57,
T58, and T64 even when the trace correctly records the failure.

## Architectural Hypothesis

Bad ticket framing to avoid:

```text
Tell the model harder not to answer after evidence failure.
```

Architectural hypothesis:

```text
Evidence/action obligation failure needs a final-output containment layer. Once
the runtime knows required evidence was not gathered, it should either replace
or strictly bound the assistant body so ungrounded workspace facts cannot be
rendered after the failure banner.
```

Likely code/document areas:

- `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java`
- `src/main/java/dev/talos/cli/modes/ExecutionOutcome.java`
- `src/main/java/dev/talos/runtime/trace/`
- `src/test/java/dev/talos/cli/modes/AssistantTurnExecutorTest.java`
- `tools/manual-eval/talosbench-cases.json`

## Goal

When evidence is incomplete or action obligation fails, the user-visible final
answer must not append ungrounded workspace facts, file contents, success
claims, or invented summaries.

## Resolution

- Missing-evidence shaping now uses a runtime-owned containment message for all
  evidence obligation types, not only read-target and protected-read turns.
- Workspace-inspection failures now suppress fabricated changed-file lists,
  file-content claims, and invented summaries after the evidence-incomplete
  banner.
- Directory-list-only violations now suppress model-derived content claims when
  the model read file contents instead of only listing directory entries.
- Existing dominant runtime safety outcomes remain intact: read-only denied
  mutations, malformed protocol replacement, no-tool mutation replacement, and
  invalid/denied mutation summaries are not overwritten by generic evidence
  containment.
- Streaming no-tool grounding still exposes the grounding warning, but the
  fabricated model body is replaced with a bounded runtime explanation.
- TalosBench now has the manual T69 guard
  `t69-changed-files-evidence-containment` for the T67 changed-files sanity
  prompt.

## Non-Goals

- No suppression of legitimate grounded answers.
- No new verifier type.
- No model-specific prompt tuning as the only fix.
- No change to the core approval policy.

## Acceptance Criteria

- If a turn is marked evidence-incomplete, the final assistant text is limited
  to the evidence failure explanation and allowed next steps.
- The model's unsupported or ungrounded body is not rendered after an
  evidence-incomplete banner.
- Turns with `INSPECT_REQUIRED` and zero tool calls cannot list files, show file
  contents, or claim changed files.
- Trace and `/last trace` still expose enough detail to debug the failed
  obligation.
- Existing action-obligation failure behavior for no-write file edits remains
  intact.

## Tests / Evidence

Required deterministic regression:

- Executor test: a scripted model returns file facts without calling tools on an
  evidence-required turn; final output must not include those facts.
- Executor test: no-tool `WORKSPACE_EXPLAIN` with inspection required reports a
  bounded evidence failure.
- TalosBench/manual case for final changed-files sanity prompt.

Suggested commands:

```powershell
.\gradlew.bat test --no-daemon
pwsh .\tools\manual-eval\run-talosbench.ps1 -ValidateOnly
```

Executed evidence:

- RED: `.\gradlew.bat test --tests
  "dev.talos.cli.modes.ExecutionOutcomeTest.workspaceInspectionMissingEvidenceSuppressesModelBody"
  --tests
  "dev.talos.cli.modes.ExecutionOutcomeTest.listOnlyWithReadFileIsAdvisoryWithMissingEvidenceWarning"
  --no-daemon` - failed for the expected body-containment assertions.
- GREEN targeted: same command - pass.
- `.\gradlew.bat test --tests "dev.talos.cli.modes.ExecutionOutcomeTest"
  --no-daemon` - pass.
- `.\gradlew.bat e2eTest --tests
  "dev.talos.harness.JsonScenarioPackTest.streamingNoToolEvidenceAnswerIsVisiblyUngrounded"
  --no-daemon` - pass.
- `.\gradlew.bat test e2eTest --rerun-tasks --no-daemon` - pass.
- `pwsh .\tools\manual-eval\run-talosbench.ps1 -ValidateOnly` - pass,
  validated 26 cases.
- `pwsh .\tools\manual-eval\run-talosbench.ps1 -SelfTest` - pass.

## Known Risks

- Overwriting the assistant body too aggressively can hide useful model
  explanations. Keep the allowed replacement text explicit and traceable.
