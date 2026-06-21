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
Fix the bug in calc.py.
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
The deterministic mutation-intent/task-contract layer does not treat "fix
<thing> in <file>" as an explicit mutation request unless the user uses more
direct edit wording.
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
Classify direct "fix <problem> in <file>" requests as FILE_EDIT while preserving
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
- Equivalent explicit fix phrases with named file targets expose edit tools.
- Proposal-only/read-only variants remain read-only.
- Existing mutation, denial, checkpoint, and verification tests remain green.
- No regressions to privacy, permissions, checkpointing, trace redaction, or
  outcome truth.

## Tests / Evidence

Required deterministic regression:

- Unit test: `MutationIntentTest` for direct fix-file phrasing and negative
  read-only/proposal phrasing.
- Integration/executor test: `TaskContractResolverTest` proving FILE_EDIT tool
  surface for the scn-13 prompt shape.
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

Verified broad gates:

```powershell
.\gradlew.bat check --no-daemon
.\gradlew.bat wikiEvidenceCloseGate --rerun-tasks --no-daemon
```

Result: PASS.

Remaining before closeout:

- Review by owner/independent review.

## Work-Test Cycle Notes

- Use the inner dev loop.
- Do not bump version.
- Add a `CHANGELOG.md` `Unreleased` note when implementation lands.

## Known Risks

- Over-broad fix detection could expose mutation tools for advice-only turns.

## Known Follow-Ups

- Re-run T842 scn-13 on both audited models after implementation.
