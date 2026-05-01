# [T75-open-high] Static Repair Context Requires Target Overlap

Status: open
Priority: high
Date: 2026-05-01

## Evidence Summary

- Source: T61-B milestone QA audit
- Transcript:
  `local/manual-workspaces/t61-b-milestone-qa-20260501-210434/TEST-OUTPUT-T61-B.txt`
- Findings:
  `local/manual-workspaces/t61-b-milestone-qa-20260501-210434/FINDINGS-T61-B.md`
- Analysis:
  `local/manual-testing/t61-b-milestone-qa-20260501-210434/analysis.md`
- Recovered session:
  `%USERPROFILE%/.talos/sessions/5e4d68c1ddb286b1946c8c01c4f4e21e02756ab2.turns.jsonl`

Observed behavior:

- Turn 36, trace `trc-b06ca565-3dbd-47cd-9429-0f54e1233c43`
  - Prompt requested a fresh BMI calculator with `index.html`, `styles.css`,
    and `scripts.js`.
  - Static repair context from a previous README verification failure was still
    injected.
  - Tool calls wrote README-like content instead of the requested web artifact
    set.
- Follow-up traces:
  - `trc-84e449a2-aa86-4fbc-9aaa-2a54bae269de`
  - `trc-0ae7b23f-14d7-4862-9ead-6711de1e75fa`
  - `trc-a4715625-7288-4b80-b333-1f4a6c16458a`

Related open tickets:

- T47 covers cross-file web repair coherence after full writes.
- T62 covers the minimal capability profile spine and T47 sequencing.
- This ticket should be implemented before updating T47/T62 because it fixes
  generic stale repair-context contamination across unrelated targets.

## Classification

Primary taxonomy bucket: `REPAIR_POLICY`

Secondary buckets:

- `TARGET_RESOLUTION`
- `STATIC_VERIFICATION`
- `CONTROL_PLANE`

Blocker level: high before T47/T62 implementation and before the next full
T61-style audit

Why this level:

A failed repair context for one target must not steer a later unrelated task.
This is a control-plane bug independent of web verifier quality.

## Architectural Hypothesis

Bad ticket framing to avoid:

```text
Clear repair context after every failed verifier.
```

Architectural hypothesis:

```text
Static repair continuation should require target overlap between the previous
failed verification context and the current task's explicit targets, unless the
current prompt is a clear deictic repair of the immediately previous failed
artifact. Fresh explicit targets must win over stale repair context.
```

Likely code/document areas:

- `src/main/java/dev/talos/runtime/repair/RepairPolicy.java`
- `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java`
- `src/main/java/dev/talos/runtime/task/TaskContractResolver.java`
- `src/test/java/dev/talos/runtime/repair/`
- `src/test/java/dev/talos/cli/modes/`
- `tools/manual-eval/talosbench-cases.json`

## Goal

Prevent stale static repair instructions from applying to unrelated current-turn
targets.

## Non-Goals

- No full active-memory redesign.
- No disabling repair for legitimate same-target follow-ups.
- No implementation of full T47 web coherence.
- No implementation of full T62 capability profile spine.

## Acceptance Criteria

- If previous static verification failed for `README` and the current prompt
  explicitly targets `index.html`, `styles.css`, and `scripts.js`, repair
  context is not injected.
- If the current prompt explicitly repairs the same target as the failed
  verifier, repair context is still available.
- If target overlap is absent, trace records that static repair context was
  skipped because targets did not overlap.
- Fresh explicit targets dominate broad repair-continuation words such as
  `complete`, `finish`, or `write_file`.
- Existing same-target static repair tests remain passing.

## Tests / Evidence

Required deterministic regressions:

- `RepairPolicy` test: previous README failure plus current BMI web targets
  skips repair plan.
- `RepairPolicy` test: previous README failure plus current README repair keeps
  repair plan.
- Executor test: stale repair instruction is not injected into fresh unrelated
  mutation task.
- TalosBench/manual sequence: exact README failure/retry followed by BMI create
  does not write README.

Suggested commands:

```powershell
.\gradlew.bat test --tests "*RepairPolicy*" --tests "*AssistantTurnExecutor*" --no-daemon
.\gradlew.bat test e2eTest --rerun-tasks --no-daemon
pwsh .\tools\manual-eval\run-talosbench.ps1 -ValidateOnly
pwsh .\tools\manual-eval\run-talosbench.ps1 -SelfTest
```

## Known Risks

- Too-strict overlap could suppress legitimate repair after a vague follow-up
  such as `fix it`; allow immediate previous failed-target repair when the
  prompt is clearly deictic and no conflicting explicit targets are present.
