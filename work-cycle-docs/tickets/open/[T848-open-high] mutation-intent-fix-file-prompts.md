# [T848-open-high] Mutation Intent Fix-File Prompts

Status: open
Priority: high
Type: implementation
Branch: `v0.9.0-beta-dev`
Talos version: `0.10.5`

## Evidence Summary

- Source: T842 manual beta scenario evidence.
- Date: 2026-06-21.
- Talos version / commit: `0.10.5`; local summaries do not capture a commit.
- Model/backend: `qwen2.5-coder-14b` and `gpt-oss-20b` through managed `llama.cpp`.
- Workspace fixture: `local/beta-pre-release-test-scenarios/scn-13-fix-bug-edit`.
- Raw transcript path: local T842 artifacts under the scenario directories.
- Trace path or `/last trace` summary: local T842 artifacts.
- File diff summary: empty diff in both models because mutation tools were not exposed.
- Approval choices: no approval prompt because the turn was classified read-only.
- Verification status: no edit verification ran.

Redacted prompt sequence:

```text
There is a bug in calc.py: multiply returns the wrong result. Fix multiply so
it returns the product of a and b. Change only what is necessary.
```

Expected behavior:

```text
The turn is classified as FILE_EDIT, exposes mutating edit/write tools under
normal approval policy, reads the named file, and attempts the requested fix.
```

Observed behavior:

```text
Both audited models received READ_ONLY classification and no edit tool. Talos
made no change, produced an empty diff, and did not overclaim a mutation.
```

## Classification

Primary taxonomy bucket:

- `INTENT_BOUNDARY`

Secondary buckets:

- `TOOL_SURFACE`
- `ACTION_OBLIGATION`

Blocker level:

- candidate follow-up

Why this level:

```text
This affects both audited beta models and blocks a normal user edit phrase. The
trust boundary held, but the assistant failed a common code-fix request.
```

## Architectural Hypothesis

Bad ticket framing to avoid:

```text
Tell the model harder to edit.
```

Architectural hypothesis:

```text
The deterministic mutation-intent/task-contract layer does not treat a
file-scoped defect mention followed by an imperative fix sentence as an
explicit mutation request unless the user uses more direct edit wording.
```

Likely code/document areas:

- `src/main/java/dev/talos/runtime/MutationIntent.java`
- `src/main/java/dev/talos/runtime/task/TaskContractResolver.java`
- `src/test/java/dev/talos/runtime/MutationIntentTest.java`
- `src/test/java/dev/talos/runtime/task/TaskContractResolverTest.java`

Why a one-off patch is insufficient:

```text
This is a recurring natural edit phrasing, not a scenario-specific prompt bug.
The tool surface must be correct before the model can act.
```

## Goal

```text
Classify direct "fix <problem> in <file>" requests and file-scoped defect
requests followed by imperative fix action as FILE_EDIT while preserving
read-only negation, proposal-only, and explanatory "how would I fix" behavior.
```

## Non-Goals

- No LLM classifier for safety-critical mutation policy.
- No loosening of read-only or proposal-only requests.
- No automatic approval bypass.
- No retrieval, RAG, or model-selection change.

## Implementation Notes

Keep the classifier deterministic and narrow. Prefer adding phrase coverage for
explicit fix requests that name a target file or target code object, then prove
through `TaskContractResolver` that mutating tools are exposed only for the
mutating form.

## Architecture Metadata

Capability:

- Code edit intent classification.

Operation(s):

- edit/write planning only; actual mutation still goes through existing tools
  and approval policy.

Owning package/class:

- `dev.talos.runtime.MutationIntent`
- `dev.talos.runtime.task.TaskContractResolver`

New or changed tools:

- None.

Risk, approval, and protected paths:

- Risk level: medium; tool-surface expansion for a common edit request.
- Approval behavior: unchanged; mutating tools still require existing approval.
- Protected path behavior: unchanged; protected paths remain governed by
  existing policy.

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: unchanged.
- Evidence obligation: named file must still be read before edit.
- Verification profile: unchanged.
- Repair profile: unchanged.

Outcome and trace:

- Outcome/truth warnings: unchanged.
- Trace/debug fields: task contract/classification should reflect FILE_EDIT.

Refactor scope:

- Allowed: narrow classifier/resolver changes and tests.
- Forbidden: broad prompt rewrite, new planner, new LLM intent classifier.

## Acceptance Criteria

- `Fix the bug in calc.py` resolves to a mutation-capable file-edit contract.
- `There is a bug in calc.py... Fix multiply...` resolves to a
  mutation-capable file-edit contract.
- Equivalent explicit fix phrases with named file targets expose edit tools.
- Proposal-only/read-only variants remain read-only.
- Existing mutation, denial, checkpoint, and verification tests remain green.
- No regressions to privacy, permissions, checkpointing, trace redaction, or
  outcome truth.

## Tests / Evidence

Required deterministic regression:

- Unit test: `MutationIntentTest` for direct fix-file phrasing and negative
  read-only/proposal phrasing.
- Integration test: `TaskContractResolverTest` proving FILE_EDIT contract for
  the real scn-13 prompt shape.
- Tool-surface test: `ToolSurfacePlannerTest` proving the real scn-13 prompt
  exposes the file-edit target apply surface.
- JSON e2e scenario: optional follow-up after unit/integration coverage.
- Trace assertion: contract type and classification reason should be visible in
  existing prompt-debug/trace surfaces when relevant.

Commands:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.MutationIntentTest" --tests "dev.talos.runtime.task.TaskContractResolverTest" --no-daemon
.\gradlew.bat check --no-daemon
.\gradlew.bat wikiEvidenceCloseGate --rerun-tasks --no-daemon
```

## Implementation Evidence

Implementation commit: this implementation commit; exact SHA to be recorded at
closeout.

Implementation summary:

- Added deterministic coverage for direct `fix <problem> in <file>` wording.
- Added deterministic coverage for the actual T842 scn-13 prompt shape: a
  file-scoped defect mention followed by an imperative `Fix multiply...`
  sentence.
- Added a `.py`-aware target matcher local to that fix-problem detector so
  `calc.py` is covered without broadening the existing source/artifact target
  capture used by unrelated Python creation flows.
- Kept advisory and instructional forms read-only.
- Pinned the resulting apply-phase tool surface in `ToolSurfacePlannerTest`:
  the audited prompt now uses the narrowed file-edit target surface with
  `talos.read_file`, `talos.write_file`, and `talos.edit_file` available.

Verified focused gate:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.MutationIntentTest" --tests "dev.talos.runtime.task.TaskContractResolverTest" --tests "dev.talos.runtime.toolcall.ToolSurfacePlannerTest" --no-daemon
```

Result: PASS.

Second implementation pass:

- independent review/owner review showed the first implementation covered the simplified
  `Fix the bug in calc.py.` shape but not the real T842 scn-13 prompt.
- Added red tests for the real prompt and for advisory/explanatory/no-change
  negatives:
  - `There is a bug in calc.py: multiply returns the wrong result. Fix multiply
    so it returns the product of a and b. Change only what is necessary.`
  - `How would you fix multiply in calc.py?`
  - `There is a bug in calc.py. Explain how to fix multiply.`
  - `There is a bug in calc.py. Should I fix multiply?`
  - `There is a bug in calc.py, but do not change files.`
- Added a narrow file-scoped-defect plus imperative-fix detector in
  `MutationIntent`, ordered after read-only/advisory/instructional gates.

Verified focused gate:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.MutationIntentTest" --tests "dev.talos.runtime.task.TaskContractResolverTest" --tests "dev.talos.runtime.toolcall.ToolSurfacePlannerTest" --no-daemon
```

Result: PASS.

Third implementation pass:

- independent review/owner review verified the real scn-13 prompt now edits on both audited
  models, but adversarial live prompts exposed pre-existing `fix it`
  false positives through `explicit-mutation-marker`.
- Added red tests for:
  - `There is a bug in calc.py. How would you fix it?`
  - `There is a bug in calc.py. Don't fix it yet, just tell me what is wrong.`
  - `There is a bug in calc.py. Do not fix it yet, just tell me what is wrong.`
  - `There is a bug in calc.py. Dont fix it yet, just tell me what is wrong.`
- Added `fix` forms to the global read-only negation set and added a narrow
  embedded advisory pronoun-question detector so `How would you fix it?` stays
  non-mutating.
- Kept the real scn-13 positive on the file-edit target apply surface.

Verified focused gate:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.MutationIntentTest" --tests "dev.talos.runtime.task.TaskContractResolverTest" --tests "dev.talos.runtime.toolcall.ToolSurfacePlannerTest" --no-daemon
```

Result: PASS.

Fourth implementation pass:

- independent review/owner live battery confirmed the third pass fixed the owner gate prompts
  but still left modal self-question prompts exposed through the old `fix it`
  marker.
- Added red tests for:
  - `There is a bug in calc.py. Should I fix it?`
  - `There is a bug in calc.py. Can I fix it?`
  - `There is a bug in calc.py. May I fix it?`
  - `There is a bug in calc.py. Would we fix it?`
  - `There is a bug in calc.py. Could we fix it?`
- Added positive guards proving assistant-directed requests still mutate:
  - `There is a bug in calc.py. Can you fix it?`
  - `There is a bug in calc.py. Would you fix it?`
  - `There is a bug in calc.py. Could you fix it?`
  - `Fix it.`
- Added a narrow embedded modal self-question detector for `I|we` only. It does
  not include `you`, because `Can you fix it?` is a direct assistant request.

Verified focused gate:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.MutationIntentTest" --tests "dev.talos.runtime.task.TaskContractResolverTest" --tests "dev.talos.runtime.toolcall.ToolSurfacePlannerTest" --no-daemon
```

Result: PASS.

Verified broad gates:

```powershell
.\gradlew.bat check --no-daemon
.\gradlew.bat wikiEvidenceCloseGate --rerun-tasks --no-daemon
```

Result: PASS.

Remaining before closeout:

- Review by owner/independent review.
- Live scn-13 rerun on both audited beta models.

## Work-Test Cycle Notes

- Use the inner dev loop.
- Do not bump version.
- Add a `CHANGELOG.md` `Unreleased` note when implementation lands.

## Known Risks

- Over-broad fix detection could expose mutation tools for advice-only turns.

## Known Follow-Ups

- Re-run T842 scn-13 on both audited models after implementation.
