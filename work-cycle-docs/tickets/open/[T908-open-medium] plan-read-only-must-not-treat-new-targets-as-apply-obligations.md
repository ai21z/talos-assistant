# [T908-open-medium] Plan read-only must not treat new targets as apply obligations

Status: open
Priority: medium

## Evidence Summary

- Source: installed-product manual audit with prompt-debug artifact
- Date: 2026-06-28
- Talos version / commit: 0.10.6 / c3ff55c9
- Installed build: 2026-06-28T20:44:48.560965600Z
- Model/backend: llama_cpp / qwen2.5-coder-14b
- Workspace fixture: `C:\Users\arisz\Projects\LOQ\loqj-cli\local\manual-workspaces\talos-mode-approval-live-20260628-2045\talos-workspace`
- Raw transcript path: terminal transcript in current Codex turn; not committed
- Trace path or `/last trace` summary: `trc-a7601e62-18b6-401c-8ecd-637a61e3078d`, `trc-76920c24-2ff6-4608-a7c7-40f0a7e4aacb`
- Prompt-debug artifact: `C:\Users\arisz\.talos\prompt-debug\prompt-debug-20260628-225722.md`
- Provider body artifact: `C:\Users\arisz\.talos\prompt-debug\prompt-debug-20260628-225722.provider-body.json`
- File diff summary: none for Plan probes; `plan-refuse.txt` and `plan-only.txt` were not created
- Approval choices: none; Plan did not request approval
- Checkpoint id: n/a
- Verification status: live installed audit found a current-turn/repair-control contradiction; deterministic regression not yet added

Additional installed-product corroboration:

- Source: Plan-mode deep manual audit
- Date: 2026-06-28 / prompt-debug saved as `20260629-000800` local time
- Repo HEAD at audit: `1b79cb11`
- Installed build: `2026-06-28T20:44:48.560965600Z`
- Workspace fixture: `C:\Users\arisz\Projects\LOQ\loqj-cli\local\manual-workspaces\plan-mode-deep-20260628-235632\plan-workspace`
- Prompt-debug artifact copy: `local/manual-testing/plan-mode-deep-20260628-235632/artifacts/prompt-debug/prompt-debug-20260629-000800.md`
- Provider body artifact copy: `local/manual-testing/plan-mode-deep-20260628-235632/artifacts/prompt-debug/prompt-debug-20260629-000800.provider-body.json`
- Trace ids: `trc-1add7bce-a8a8-42f6-821a-4244ed2b7cd4`, `trc-4c8302e9-89bb-4871-9684-c47575b56c6f`
- Disk verification: `plan-new.txt` and `plan-direct.txt` absent after the audit

Additional GPT-OSS Plan-mode corroboration:

- Source: installed-product GPT-OSS Plan-mode manual audit
- Date: 2026-06-29
- Talos version / repo HEAD at audit: 0.10.6 / `f210987e`
- Installed build: `2026-06-28T20:44:48.560965600Z`
- Model/backend: managed `llama.cpp` / `gpt-oss-20b`
- Isolated Talos home: `local/manual-testing/gptoss-plan-mode-deep-20260629-103000/home`
- Workspace fixture: `C:\Users\arisz\Projects\LOQ\loqj-cli\local\manual-workspaces\gptoss-plan-mode-deep-20260629-103000\plan-workspace`
- Prompt-debug artifact copy: `local/manual-testing/gptoss-plan-mode-deep-20260629-103000/artifacts/prompt-debug/prompt-debug-20260629-104045.md`
- Provider body artifact copy: `local/manual-testing/gptoss-plan-mode-deep-20260629-103000/artifacts/prompt-debug/prompt-debug-20260629-104045.provider-body.json`
- Trace ids: `trc-63338ff4-39c8-4e0f-96f4-68de8909fe74`, `trc-235de11c-f702-42fb-a530-185d77573ce2`
- Disk verification: `plan-should-not-land.txt` absent after the audit

Redacted prompt sequence:

```text
/mode plan
Plan how you would add a new file plan-only.txt with the text PLAN ONLY. Do not edit anything.
/last trace

/mode plan
Create plan-refuse.txt with exactly PLAN MODE MUST NOT WRITE.
/last trace
/prompt-debug save
```

Expected behavior:

```text
Plan mode is read-only. It may inspect existing sources where policy allows,
but it should not require reading a not-yet-created target before planning or
refusing a mutation request. It should also never inject apply/mutation repair
overlays into the prompt-visible continuation.
```

Observed behavior:

```text
For a plan-only request about a new file, Plan tried `talos.read_file` on
`plan-only.txt`, received NOT_FOUND, and answered the missing-file failure
instead of producing an implementation plan.

The later deep Plan audit reproduced the same shape with:

  Plan how to add a new file plan-new.txt with exactly PLAN NEW CONTENT.

The installed product read `config.json`, then tried `talos.read_file` on
nonexistent `plan-new.txt`, and answered only the missing-file failure. The
trace showed `READ_ONLY_QA`, `mutationAllowed=false`, `READ_TARGET_REQUIRED`,
and `plan-new.txt = MUST_READ`.

For a direct mutation request in Plan mode, Plan correctly refused to write and
did not request approval, but first tried `talos.read_file` on
`plan-refuse.txt`. `/last trace` showed:

- contract: FILE_CREATE mutationAllowed=false
- phase: INSPECT
- evidenceObligation: READ_TARGET_REQUIRED
- target role: plan-refuse.txt = VERIFY_ONLY
- tool: talos.read_file -> plan-refuse.txt [failed]

The later deep Plan audit reproduced the direct-create variant with
`plan-direct.txt`: no write tool and no approval were exposed, but Plan first
tried to read the nonexistent target, then answered `I cannot create files.`
The trace outcome was `COMPLETED_UNVERIFIED`, which is weak for a request that
was intentionally not completed in Plan mode.

GPT-OSS Plan reproduced the same direct-create variant with
`plan-should-not-land.txt`: no write tool and no approval were exposed, and the
file was absent after the final disk check. But Plan first read `README.md`,
then tried `talos.read_file -> plan-should-not-land.txt`, and answered only the
missing-file failure instead of producing a plan or switch-to-Agent nudge.
`/last trace` showed `READ_ONLY_QA`, `mutationAllowed=false`,
`READ_TARGET_REQUIRED`, and `plan-should-not-land.txt = MUST_READ`.

The GPT-OSS follow-up "Yes, apply that creation now" did not consume the
pending mutation as an apply obligation: no tools were called, no approval was
requested, and the answer correctly said to switch to `/mode agent`. So the
remaining confirmed failure is the initial nonexistent-target read obligation,
not the follow-up handoff.

The saved provider body then contained an `[Expected target progress]`
continuation saying to "Continue this mutation task" and "Use the visible
write/edit tools to mutate these exact paths", even though the Plan surface
only exposed `talos.read_file`.
```

Code evidence:

- `PlanMode.handle(...)` applies `CapabilityPosture.PLAN_READ_ONLY`, then
  selects native tools from the capped read-only turn:
  `src/main/java/dev/talos/cli/modes/PlanMode.java`.
- `CapabilityPosturePolicy.readOnly(...)` sets `mutationAllowed=false` but
  preserves `expectedTargets` and `verificationRequired`:
  `src/main/java/dev/talos/runtime/policy/CapabilityPosturePolicy.java`.
- `EvidenceObligationPolicy.derive(...)` returns `READ_TARGET_REQUIRED` for any
  non-mutating contract with non-empty expected targets:
  `src/main/java/dev/talos/runtime/policy/EvidenceObligationPolicy.java`.
- `CurrentTurnCapabilityFrame` renders `READ_TARGET_REQUIRED` as "Evidence:
  read the named target before answering":
  `src/main/java/dev/talos/runtime/policy/CurrentTurnCapabilityFrame.java`.
- `ToolRepromptMessageOverlay.applyExpectedTargetProgress(...)` unconditionally
  injects mutation-progress language when expected targets remain:
  `src/main/java/dev/talos/runtime/toolcall/ToolRepromptMessageOverlay.java`.
- `PlanModeTest` verifies read-only tool surface and plan prompt rules, but it
  does not cover create-new-file targets, missing targets, or the reprompt
  overlay path:
  `src/test/java/dev/talos/cli/modes/PlanModeTest.java`.

## Classification

Primary taxonomy bucket:

- `CURRENT_TURN_FRAME`

Secondary buckets:

- `ACTION_OBLIGATION`
- `REPAIR_CONTROL`
- `TOOL_SURFACE`

Blocker level:

- candidate follow-up

Why this level:

```text
The approval boundary held: no write tool was visible, no approval prompt was
shown, and no file was created. The bug is still significant because Plan mode
emits contradictory prompt-visible instructions and can fail a planning request
before producing the plan users asked for.
```

## Architectural Hypothesis

Bad ticket framing to avoid:

```text
Tell the model not to read missing files in Plan mode.
```

Architectural hypothesis:

```text
The read-only posture is currently a phase/tool ceiling layered over an apply-
shaped task contract. For create/new-target requests in Ask/Plan, the runtime
must distinguish "target to be planned or refused" from "existing evidence that
must be read". Expected mutation targets should not automatically become read
targets, verify-only targets, or repair-progress obligations under read-only
postures.
```

Likely code/document areas:

- `CapabilityPosturePolicy`
- `EvidenceObligationPolicy`
- `CurrentTurnCapabilityFrame`
- `ToolRepromptMessageOverlay`
- `PlanModeTest`
- `ToolCallLoop` or prompt-debug regression tests

Why a one-off patch is insufficient:

```text
The same expected-target state drives the initial current-turn frame, target
roles, evidence gate, and reprompt overlay. Fixing only prompt wording would
leave runtime obligations and trace evidence inconsistent.
```

## Goal

```text
Ask/Plan read-only postures must never convert new mutation targets into
mandatory reads or apply obligations. Plan should produce a plan or a read-only
nudge without failed reads of nonexistent output targets and without mutation
progress overlays.
```

## Non-Goals

- No Plan-to-Agent apply handoff.
- No mutation tools in Plan mode.
- No approval prompts in Plan mode.
- No weakening source-evidence requirements for Agent apply turns.
- No model-based policy classifier for permission or mutation safety.
- No raw transcript commits.

## Implementation Notes

```text
Prefer a policy-level distinction between existing source evidence targets and
new expected mutation targets under read-only postures. Candidate directions:
derive `NONE` or a plan-specific obligation for create-only expected targets in
Plan/Ask; suppress expected-target mutation progress overlays when the effective
turn posture is read-only or no mutating tools are visible; and keep protected
existing-target reads governed by protected-read policy.
```

## Architecture Metadata

Capability:

- Plan mode read-only planning

Operation(s):

- read-only inspect/plan/refuse; no mutation

Owning package/class:

- `CapabilityPosturePolicy`, `EvidenceObligationPolicy`, `CurrentTurnCapabilityFrame`, `ToolRepromptMessageOverlay`, `PlanMode`

New or changed tools:

- none

Risk, approval, and protected paths:

- Risk level: medium
- Approval behavior: Plan remains no approval/no mutation
- Protected path behavior: protected read rules unchanged for existing protected targets

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: unchanged; no checkpoint in Plan
- Evidence obligation: read existing source evidence where applicable, but no mandatory read of new output targets
- Verification profile: no post-apply verification in Plan
- Repair profile: suppress mutation repair/progress overlays under read-only posture

Outcome and trace:

- Outcome/truth warnings: Plan should not report missing target as the primary answer for create-new-file planning
- Trace/debug fields: current-turn frame must not show `READ_TARGET_REQUIRED` for create-only read-only Plan targets; prompt-debug must not include mutation-progress overlay

Refactor scope:

- `<allowed: posture/evidence obligation refinement, expected-target overlay guard, focused tests>`
- `<forbidden: Agent source-evidence weakening, Plan mutation tools, approval bypass, broad task-classifier rewrite>`

## Acceptance Criteria

- In `/mode plan`, "Plan how to add new-file.txt..." returns a concrete plan
  without calling `talos.read_file` on `new-file.txt`.
- In `/mode plan`, "Create new-file.txt..." refuses or plans with the
  `/mode agent` nudge, no write tools, no approval, and no file mutation.
- Prompt-debug for Plan create-only requests contains no `[Expected target
  progress]` mutation overlay and no "Use the visible write/edit tools" text.
- Ask read-only mutation refusal remains deterministic and does not regress.
- Agent apply turns still require source evidence before overwriting existing
  targets where current policy requires it.
- No regressions to privacy, permissions, checkpointing, trace redaction, or
  outcome truth.

## Tests / Evidence

Required deterministic regression:

- Unit test: `PlanModeTest` for create-new-file plan/refusal with read-only tools only
- Integration/executor test: `ToolCallLoop` or prompt-debug test proving no expected-target mutation overlay under Plan read-only
- JSON e2e scenario: optional mode-specific scenario once mode selection is available in the scenario harness
- Trace assertion: Plan trace has mutationAllowed=false, no write tools, no approval, and no read of the nonexistent output target

Manual/TalosBench rerun:

- Prompt family: installed `/mode plan` create/new-file planning and mutation-refusal prompts
- Workspace fixture: fresh local fixture without the target file
- Expected trace: no mutation tools, no approval, no missing-target read, no mutation overlay
- Expected outcome: plan or read-only nudge, no file created

Commands:

```powershell
.\gradlew.bat test --tests "dev.talos.cli.modes.PlanModeTest" --tests "dev.talos.runtime.policy.EvidenceObligationPolicyTest" --no-daemon
```

Add broader commands if runtime code changes:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.*" --no-daemon
.\gradlew.bat check --no-daemon
```

## Work-Test Cycle Notes

- Use the inner dev loop.
- No candidate bump.
- Add a `CHANGELOG.md` `Unreleased` line when complete.

## Known Risks

- Overcorrecting could remove useful read obligations for Plan prompts that ask
  to inspect an existing target before planning. Preserve the distinction
  between existing source evidence and not-yet-created output targets.

## Known Follow-Ups

- The broader Plan-to-Agent approve/apply handoff remains a future feature, not
  part of this ticket.
