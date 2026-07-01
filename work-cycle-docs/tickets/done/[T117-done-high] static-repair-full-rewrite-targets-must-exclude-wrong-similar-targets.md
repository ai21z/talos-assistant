# T117 - Static Repair Full-Rewrite Targets Must Exclude Wrong Similar Targets

Status: done
Severity: high
Area: static verification / repair framing / expected targets

## Problem

The T116 focused Qwen/GPT-OSS audit showed a repair-plan ambiguity after a wrong similar target mutation.

Talos correctly detected that `script.js` did not satisfy required `scripts.js`, but the static repair context then included both `script.js` and `scripts.js` in `Full-file replacement targets`. That could reinforce the wrong target instead of making the missing expected target dominant.

`script.js` should be evidence of the mistake, not a required full-rewrite target, unless it was explicitly expected by the current task.

## Scope

- Update static repair full-rewrite target selection so wrong similar targets are not promoted into repair targets.
- Keep similar wrong targets in the diagnostic/evidence section.
- Preserve expected target dominance: missing expected targets must be named and prioritized.
- Preserve coherent web repair for originally expected HTML/CSS/JS targets.
- Do not suppress verifier reporting of similar wrong targets.

## Acceptance

- Tests cover expected target `scripts.js` with wrong similar changed target `script.js`.
- Repair context says `script.js` does not satisfy `scripts.js`.
- `Full-file replacement targets` includes `scripts.js` and other required expected targets needed for coherent repair.
- `Full-file replacement targets` does not include `script.js` unless `script.js` was also an expected target.
- Runtime-owned changed-files summary remains accurate and failure-dominant.
- No regression to T95/T99 expected-target repair tests.

## Completion Notes

Implemented in `src/main/java/dev/talos/runtime/repair/RepairPolicy.java`.

The repair planner now removes wrong similar evidence targets from full-rewrite repair targets unless the wrong similar path is itself a missing expected target. The diagnostic evidence remains visible, so `script.js does not satisfy scripts.js` is still shown, but only `scripts.js` is required for the narrow missing-target repair.

Added regression coverage in `src/test/java/dev/talos/runtime/repair/RepairPolicyTest.java` with `staticVerificationRepairDoesNotPromoteWrongSimilarTargetWhenOnlyExpectedTargetIsMissing`.

## Verification

- `.\gradlew.bat test --tests dev.talos.runtime.repair.RepairPolicyTest --no-daemon`
- `.\gradlew.bat test --tests dev.talos.runtime.repair.RepairPolicyTest --tests dev.talos.runtime.toolcall.ToolCallRepromptStageTest --tests dev.talos.runtime.ToolCallLoopTest --tests dev.talos.cli.modes.AssistantTurnExecutorTest --no-daemon`
- `git diff --check`
- `.\gradlew.bat test --no-daemon`
- `.\gradlew.bat installDist --no-daemon`

All passed.

## Focused Audit

Audit directory:

- `local/manual-testing/t117-static-repair-target-audit-20260504-002313/`

Models:

- `qwen2.5-coder:14b`
- `gpt-oss:20b`

Result:

- The bad frame `Full-file replacement targets: script.js, scripts.js` did not recur.
- GPT-OSS reproduced the wrong similar target evidence path, and the repair frame correctly narrowed the remaining full-file replacement target to `scripts.js`.
- Qwen did not reproduce the exact wrong similar target path, but its repair context also avoided the bad target list.
- Both model outputs remained failure-dominant when the task was not verified complete.

Follow-up created:

- T118 - Managed llama.cpp Server Lifecycle Cleanup
