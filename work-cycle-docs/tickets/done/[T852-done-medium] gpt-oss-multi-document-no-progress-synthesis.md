# [T852-done-medium] GPT-OSS Multi-Document No-Progress Synthesis

Status: done
Priority: medium
Type: implementation
Branch: `v0.9.0-beta-dev`
Talos version: `0.10.5`

## Evidence Summary

- Source: T842 manual beta scenario evidence.
- Date: 2026-06-21.
- Talos version / commit: `0.10.5`; local summaries do not capture a commit.
- Model/backend: `gpt-oss:20b` through managed `llama.cpp`; qwen passed the
  same scenario.
- Workspace fixture: `local/beta-pre-release-test-scenarios/scn-11-mixed-format-analysis`.
- Raw transcript path: local T842 gpt-oss artifacts.
- Trace path or `/last trace` summary: local T842 gpt-oss artifacts.
- File diff summary: none; read-only turn.
- Approval choices: none.
- Verification status: failure-policy stop; no fabricated answer.

Redacted prompt sequence:

```text
Analyze multiple local documents and produce the requested cross-document
answer.
```

Expected behavior:

```text
After reading the needed documents, Talos either synthesizes from available
evidence or stops with a useful bounded failure that explains what evidence was
already gathered.
```

Observed behavior:

```text
gpt-oss read all three files, redundantly re-read two of them, hit the
no-progress loop guard, and failed to deliver the cross-document analysis.
The trust surface held: no fabrication and no false success.
```

## Classification

Primary taxonomy bucket:

- `REPAIR_CONTROL`

Secondary buckets:

- `MODEL_COMPETENCE`
- `OUTCOME_TRUTH`

Blocker level:

- candidate follow-up

Why this level:

```text
This is model-specific and trust-safe, but it affects a beta capability class:
multi-document local analysis.
```

## Architectural Hypothesis

Bad ticket framing to avoid:

```text
Increase the loop limit.
```

Architectural hypothesis:

```text
The no-progress terminal path can provide better synthesis context or a bounded
answer path after sufficient evidence has already been gathered, especially for
models prone to redundant reads.
```

Likely code/document areas:

- `src/main/java/dev/talos/runtime/toolcall/ToolCallRepromptStage.java`
- no-progress/failure policy and terminal answer shaping components.

Why a one-off patch is insufficient:

```text
Simply allowing more re-reads burns iterations and context. The runtime should
make the already-collected evidence easier to use or stop more constructively.
```

## Goal

```text
Improve the terminal no-progress behavior for multi-document read-only turns so
GPT-OSS either synthesizes from gathered evidence or reports a specific
evidence-complete failure instead of generic loop exhaustion.
```

## Non-Goals

- No increase to unsafe loop limits as the primary fix.
- No retrieval ranking change.
- No model-specific hidden chain-of-thought.
- No weakening of the no-progress guard.

## Implementation Notes

Characterize before changing behavior. Prefer a report/test that pins the
current no-progress shape, then evaluate whether terminal answer context or a
reprompt adjustment can improve synthesis without increasing risk.

Implementation pass:

- Added deterministic regression coverage in
  `ToolCallLoopTest.multiTargetReadOnlyDuplicateLoopStopsWithEvidenceCompleteFailure`.
- Added a narrow terminal read-only stop in
  `TerminalReadOnlyStopAnswer` for multi-target `READ_ONLY_QA` turns where all
  requested targets were already read and the current iteration made no further
  progress.
- The new stop returns a bounded evidence-complete failure that lists gathered
  targets and states that no files were changed, instead of falling through to
  the generic no-progress failure policy.
- The loop limit is unchanged.
- Implementation commit:
  `6ec410a71fd2219356e3c80cc624b75be7c67002`.
- Live closeout rerun:
  `local/beta-pre-release-test-scenarios/runs/t852-6ec410a7/gpt-oss-20b/scn-11-mixed-format-analysis/transcript.txt`.
- The live GPT-OSS scn-11 rerun on the installed build returned the bounded
  `Read evidence complete` stop, listed `budget.xlsx`, `q3.pdf`, and
  `targets.csv`, required zero approvals, and left git status/diff artifacts
  empty.

## Architecture Metadata

Capability:

- Multi-document read-only synthesis.

Operation(s):

- read/answer only.

Owning package/class:

- Tool-loop reprompt/failure policy owner.

New or changed tools:

- None.

Risk, approval, and protected paths:

- Risk level: medium; answer quality and loop stability.
- Approval behavior: none.
- Protected path behavior: unchanged.

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: none.
- Evidence obligation: preserve read evidence and cite gathered files.
- Verification profile: readback-only, not semantic proof.
- Repair profile: no-progress bounded terminal path.

Outcome and trace:

- Outcome/truth warnings: failure must stay honest if no answer is produced.
- Trace/debug fields: no-progress reason should remain visible.

Refactor scope:

- Allowed: characterization and narrow no-progress terminal wording/context.
- Forbidden: broad tool-loop rewrite or retrieval architecture change.

## Acceptance Criteria

- Current gpt-oss no-progress behavior is characterized before modification.
- Any behavior change preserves fail-safe loop stopping.
- The final outcome does not fabricate an answer after insufficient synthesis.
- Existing qwen/multi-document behavior remains green.
- No regressions to privacy, permissions, checkpointing, trace redaction, or
  outcome truth.

## Tests / Evidence

Required deterministic regression:

- `ToolCallLoopTest.multiTargetReadOnlyDuplicateLoopStopsWithEvidenceCompleteFailure`
  characterizes repeated read-only no-progress after all requested evidence is
  gathered.
- Report:
  `work-cycle-docs/reports/t852-gpt-oss-multi-document-no-progress-synthesis.md`.

Commands:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.*" --tests "dev.talos.runtime.ToolCallLoop*" --no-daemon
.\gradlew.bat check --no-daemon
.\gradlew.bat wikiEvidenceCloseGate --rerun-tasks --no-daemon
```

## Work-Test Cycle Notes

- Use characterization-first.
- Do not bump version.
- Add a `CHANGELOG.md` `Unreleased` note if production behavior changes.

## Known Risks

- Over-helpful terminal synthesis could drift into unsupported answer claims.

## Known Follow-Ups

- Broader GPT-OSS multi-document synthesis quality remains a model/runtime
  quality topic for future retrieval or synthesis work. T852 closes only the
  generic no-progress terminal outcome for the evidence-complete read-only
  case.
