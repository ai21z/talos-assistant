# [T849-open-high] Absent Named Target Mutation Guard

Status: open
Implementation state: implemented-awaiting-review
Priority: high
Type: implementation
Branch: `v0.9.0-beta-dev`
Talos version: `0.10.5`

## Evidence Summary

- Source: T842 manual beta scenario evidence.
- Date: 2026-06-21.
- Talos version / commit: `0.10.5`; local summaries do not capture a commit.
- Model/backend: `qwen2.5-coder-14b` and `gpt-oss-20b` through managed `llama.cpp`.
- Workspace fixture: `local/beta-pre-release-test-scenarios/scn-14-anti-overclaim-live`.
- Raw transcript path: local T842 artifacts under qwen and gpt-oss scenario directories.
- Trace path or `/last trace` summary: local T842 artifacts.
- File diff summary: both models mutated something other than the requested
  absent `foo()` target; gpt-oss also wrote read-display text into the file.
- Approval choices: mutation approval flow was exercised by the live scenario.
- Verification status: Talos correctly did not claim semantic verification.

Redacted prompt sequence:

```text
Modify foo() in helper.py so it returns 99.
```

Expected behavior:

```text
If the named target is absent, Talos reports that the target was not found and
does not let the model retarget the mutation to another symbol.
```

Observed behavior:

```text
Neither model reported that foo() was absent. qwen retargeted bar(); gpt-oss
corrupted the file by writing line-numbered read-display text. The
anti-overclaim file-mutation guard still prevented a false success claim.
```

## Classification

Primary taxonomy bucket:

- `VERIFICATION`

Secondary buckets:

- `INTENT_BOUNDARY`
- `OUTCOME_TRUTH`
- `TOOL_SURFACE`

Blocker level:

- candidate follow-up

Why this level:

```text
The trust layer prevented a false success claim, but the runtime still allowed
wrong-target mutation attempts after a named-target request could not be
grounded.
```

## Architectural Hypothesis

Bad ticket framing to avoid:

```text
Prompt the model not to change the wrong function.
```

Architectural hypothesis:

```text
The runtime has no pre-mutation guard that checks whether a user-named code
target exists before allowing the model to improvise an edit.
```

Likely code/document areas:

- `src/main/java/dev/talos/runtime/task/TaskContractResolver.java`
- `src/main/java/dev/talos/runtime/toolcall/ToolCallExecutionStage.java`
- `src/main/java/dev/talos/runtime/toolcall/ToolCallPreExecutionGuardChain.java`
- symbol/index helpers if a deterministic symbol lookup is available.

Why a one-off patch is insufficient:

```text
The invariant is not specific to foo(). Any request naming a symbol, selector,
function, or target should fail honestly when that target is absent instead of
retargeting.
```

## Goal

```text
Detect absent named mutation targets before applying a wrong-target edit, and
return a clear "named target not found" outcome without weakening existing
anti-overclaim or exact-edit guards.
```

## Non-Goals

- No broad semantic code understanding.
- No LLM classifier for target existence.
- No weakening of exact edit/readback verification.
- No retrieval ranking or symbol-index overhaul unless strictly needed for the
  guard.

## Implementation Notes

Start with a narrow deterministic guard for named target forms that the runtime
can parse with high confidence. If the target cannot be checked deterministically,
fail honest or leave unclaimed rather than guessing. Do not block ordinary
free-form edits that do not name a specific absent target.

Implementation update:

- Added package-private `NamedTargetExistenceGuard` in
  `dev.talos.runtime.toolcall`.
- The guard currently covers high-confidence `name()` in `file` requests such
  as `Modify foo() in helper.py`.
- Pass 2 expands the same edit-verb guard to the real scn-14 wording
  `Modify the existing function foo() in helper.py...` while keeping
  `add` / `create` outside the trigger so new-function requests are not
  over-blocked.
- The guard requires complete same-turn read evidence before approval. If the
  readback proves the named Python function is absent, it blocks `write_file`
  and `edit_file` before execution, records a failed pre-execution mutation,
  emits a tool-result error, and records a trace action-obligation failure.
- The guard deliberately does not broaden into semantic symbol lookup or
  retrieval ranking.
- T849 remains open pending owner/Opus live scn-14 rerun on the audited models.

Implementation report:

- `work-cycle-docs/reports/t849-absent-named-target-mutation-guard.md`

## Architecture Metadata

Capability:

- Code edit target grounding.

Operation(s):

- edit/write guard before mutation.

Owning package/class:

- Runtime task/guard layer; exact owner to be selected during implementation.

New or changed tools:

- None.

Risk, approval, and protected paths:

- Risk level: high; prevents wrong-target mutations.
- Approval behavior: unchanged; approval is not a substitute for target
  existence.
- Protected path behavior: unchanged.

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: unchanged for allowed mutations.
- Evidence obligation: same-turn read or deterministic target evidence before
  mutation.
- Verification profile: target-existence failure should dominate attempted
  mutation.
- Repair profile: no automatic retargeting.

Outcome and trace:

- Outcome/truth warnings: report named target absent.
- Trace/debug fields: include target-absence guard decision where practical.

Refactor scope:

- Allowed: narrow guard/policy owner and tests.
- Forbidden: broad symbol-index rewrite or model-driven target validator.

## Acceptance Criteria

- A request to modify absent `foo()` in a file reports the missing target and
  does not mutate another function.
- Existing valid explicit edits still work.
- Existing anti-overclaim old-string-not-found protection remains intact.
- The final answer does not claim success when the named target is absent.
- No regressions to privacy, permissions, checkpointing, trace redaction, or
  outcome truth.

## Tests / Evidence

Required deterministic regression:

- Unit/integration test: absent named function in an edit request produces a
  blocked/honest outcome.
- Integration/executor test: valid named target still edits normally.
- Trace assertion: target guard decision is visible where relevant.

Commands:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.*" --tests "dev.talos.runtime.toolcall.*" --no-daemon
.\gradlew.bat check --no-daemon
.\gradlew.bat wikiEvidenceCloseGate --rerun-tasks --no-daemon
```

## Work-Test Cycle Notes

- Use the inner dev loop.
- Do not bump version.
- Add a `CHANGELOG.md` `Unreleased` note when implementation lands.

## Known Risks

- Too-broad target extraction could false-block legitimate refactors.

## Known Follow-Ups

- Re-run T842 scn-14 on both audited models after implementation.
