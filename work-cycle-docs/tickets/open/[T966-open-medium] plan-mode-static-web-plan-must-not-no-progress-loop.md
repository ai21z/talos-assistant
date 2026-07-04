# [T966-open-medium] Plan Mode Static Web Plan Must Not No-Progress Loop

Status: open
Priority: medium

## Evidence Summary

- Source: installed-product manual PTY release-confidence audit
- Date: 2026-07-04/05 Europe/Madrid
- Talos version / commit: 0.10.8 / 3369b237b297320915dad9dc25aa70769b2a4027
- Installed executable: `C:\Users\arisz\AppData\Local\Programs\talos\bin\talos.bat`
- Model/backend: `llama_cpp/qwen2.5-coder-14b`
- Workspace fixture: `C:\Users\arisz\Desktop\testtalos\release-confidence-0.10.8-20260704-233706\manual-workspaces\qwen`
- Audit report: `C:\Users\arisz\Desktop\testtalos\release-confidence-0.10.8-20260704-233706\RELEASE-CONFIDENCE-SUMMARY.md`
- Prompt-debug/provider-body: `C:\Users\arisz\Desktop\testtalos\release-confidence-0.10.8-20260704-233706\manual-testing\qwen\prompt-debug\prompt-debug-20260704-235116.md`
- Related done tickets: T891, T908, T912

Redacted prompt sequence:

```text
/mode plan
Plan how you would fix the selector bug in script.js, but do not edit any files.
```

Expected behavior:

```text
Plan mode may inspect relevant files with read-only tools, then should produce a
bounded implementation plan. It must not mutate, request approval, or loop on
the same reads until no-progress failure.
```

Observed behavior:

```text
Plan mode exposed only talos.read_file and did not mutate, which is correct.
However Qwen repeatedly read the same small file set, then the failure policy
stopped the tool loop after about 263.7s with no useful implementation plan.
```

## Classification

Primary taxonomy bucket:

- `MODEL_COMPETENCE`

Secondary buckets:

- `REPAIR_CONTROL`
- `ACTION_OBLIGATION`

Blocker level:

- candidate follow-up

Why this level:

```text
The safety invariant held: Plan stayed read-only. The product-quality invariant
failed: Plan did not plan. This is not a privacy or mutation blocker, but it is
visible in the public mode surface.
```

## Architectural Hypothesis

Bad ticket framing to avoid:

```text
Let Plan mutate so it can finish.
```

Architectural hypothesis:

```text
Plan mode's prompt/tool-loop constraints allow repeated read_file calls without
a strong enough "stop reading and plan now" transition for small static-web
diagnosis. The no-progress stop is safe but leaves an unhelpful user outcome.
```

Likely code/document areas:

- `src/main/java/dev/talos/cli/modes/PlanMode.java`
- prompt sections for plan posture
- tool-loop no-progress policy
- read-only diagnostic finalization

Why a one-off patch is insufficient:

```text
This is not only the selector fixture. Plan mode needs a general bounded
read-then-plan behavior for small workspaces, while preserving read-only policy.
```

## Goal

```text
Plan mode should turn enough inspected evidence into a concise plan before
no-progress exhaustion, without mutating or asking approval.
```

## Non-Goals

- No Plan-to-Agent automatic handoff.
- No mutation tools in Plan.
- No approval prompts in Plan.
- No model-specific Qwen-only prompt hack if a posture-level fix is practical.

## Implementation Notes

Possible direction:

- Add/strengthen a Plan-specific current-turn instruction: after reading the
  obvious target files once, stop calling tools and produce a plan.
- Add a read-only no-progress finalizer that summarizes inspected files and
  produces a best-effort plan when evidence is sufficient.
- Add deterministic tests that Plan exposes only read tools and does not
  consume pending mutation as apply intent.

## Architecture Metadata

Capability:

- Plan mode read-only diagnostic/planning.

Operation(s):

- read
- plan

Owning package/class:

- Plan mode prompt/posture and read-only tool-loop finalization.

New or changed tools:

- None.

Risk, approval, and protected paths:

- Risk level: medium UX/product risk, low mutation risk.
- Approval behavior: no approval prompts in Plan.
- Protected path behavior: unchanged; protected reads still require policy/approval.

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: not applicable.
- Evidence obligation: inspected files should be named in the plan.
- Verification profile: not applicable to Plan-only output.
- Repair profile: no mutation repair.

Outcome and trace:

- Outcome/truth warnings: if Plan stops early, final answer must state limits
  and inspected files, not pretend a complete implementation happened.
- Trace/debug fields: no-progress stop should be visible.

Refactor scope:

- Allowed: narrow Plan prompt/finalizer changes.
- Forbidden: broad mode refactor or Plan execution feature.

## Acceptance Criteria

- Plan mode for the static-web selector prompt reads the relevant files at most
  a bounded number of times and produces a useful plan.
- Plan mode exposes no mutation tools and never asks approval for the planning
  prompt.
- If no-progress failure still occurs, the final answer summarizes inspected
  evidence and the limitation truthfully.
- The Qwen installed-product Plan lane is rerun and no longer spends minutes in
  repeated reads for this simple fixture.
- No regressions to privacy, permissions, checkpointing, trace redaction, or
  outcome truth.

## Tests / Evidence

Required deterministic regression:

- Plan mode prompt/tool-surface test: only read tools visible.
- Tool-loop/finalizer test: repeated read-only no-progress on sufficient
  evidence produces bounded plan/limitation output.
- Installed PTY rerun: Qwen static-web plan prompt.

Manual/TalosBench rerun:

- Prompt family: Plan static-web diagnosis.
- Workspace fixture: `index.html`, `script.js`, `scripts.js`, `styles.css`.
- Expected trace: read-only tools only, bounded read count, no approval.
- Expected outcome: useful plan or truthful limited plan, no mutation.

Commands:

```powershell
.\gradlew.bat test --tests "*PlanMode*" --tests "*ToolCallLoop*" --no-daemon
.\gradlew.bat check --no-daemon
```

## Work-Test Cycle Notes

- Behavior-changing closeout requires a CHANGELOG entry under `## [Unreleased]`.
- This should follow T961/T962/T963/T964 unless Plan mode is in the public demo
  path.

## Known Risks

- Over-constraining Plan could under-inspect larger workspaces. The bounded rule
  should be sensitive to small obvious fixtures versus larger unknown projects.

## Known Follow-Ups

- Re-evaluate Plan mode on GPT-OSS after the Qwen loop is corrected.
