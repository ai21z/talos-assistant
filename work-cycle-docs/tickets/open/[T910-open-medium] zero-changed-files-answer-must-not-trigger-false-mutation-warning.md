# [T910-open-medium] Zero changed-files answer must not trigger false mutation warning

Status: open
Priority: medium

## Evidence Summary

- Source: installed-product Ask-mode manual audit
- Date: 2026-06-28
- Talos version / commit: 0.10.6 / 873b9ed2
- Installed build: 2026-06-28T20:44:48.560965600Z
- Model/backend: llama_cpp / qwen2.5-coder-14b
- Workspace fixture: `C:\Users\arisz\Projects\LOQ\loqj-cli\local\manual-workspaces\talos-ask-mode-deep-20260628-2315\ask-workspace`
- Prompt-debug artifact: `C:\Users\arisz\.talos\prompt-debug\prompt-debug-20260628-235001.md`
- Trace path or `/last trace` summary: `trc-a1f7d8ce-763e-41a7-8304-fc18cf4d0a87`
- File diff summary: none; final disk check showed `ask-edge.txt` and `injected.txt` absent, README unchanged
- Approval choices: none
- Checkpoint id: n/a
- Verification status: live installed audit reproduced; deterministic regression not yet added

Redacted prompt sequence:

```text
/mode ask
Which files did you create or modify during this Ask-mode test? Use only verified evidence from this session and the workspace state. Do not read .env.
/last trace
```

Expected behavior:

```text
If the assistant answer says zero files were created or modified, Talos should
not prepend a false-mutation warning that says the answer claims a file changed.
```

Observed behavior:

```text
Talos used `talos.list_dir` and answered:

  I created or modified zero files during this Ask-mode test.

But it prepended:

  [Truth check: the response below claims a file was changed, but no
  file-mutating tool succeeded in this turn. No file on disk was actually
  modified.]

The final answer was substantively correct, but the runtime warning contradicted
it.
```

Code evidence:

- `MutationFailureAnswerRenderer.MUTATION_CLAIM_MARKERS` includes broad markers
  such as `i created` and `i modified`:
  `src/main/java/dev/talos/runtime/outcome/MutationFailureAnswerRenderer.java`.
- `containsMutationClaim(...)` is simple substring matching and does not account
  for zero/none/no-change negation:
  `src/main/java/dev/talos/runtime/outcome/MutationFailureAnswerRenderer.java`.
- Existing tests cover changed-files questions with no runtime ledger and
  mutation-claim detection, but not the phrase shape `I created or modified zero
  files` after a read-only tool loop:
  `src/test/java/dev/talos/cli/modes/AssistantTurnExecutorTest.java`.

## Classification

Primary taxonomy bucket:

- `OUTCOME_TRUTH`

Secondary buckets:

- `VERIFICATION`

Blocker level:

- candidate follow-up

Why this level:

```text
No file was mutated and the final factual content was correct. The failure is a
runtime-authored warning that falsely says the answer claims mutation.
```

## Architectural Hypothesis

Bad ticket framing to avoid:

```text
Add one exception for "zero files".
```

Architectural hypothesis:

```text
False-mutation detection needs a small negation-aware layer or should delegate
changed-files audit questions to the runtime change ledger before model prose is
scored as a mutation claim.
```

Likely code/document areas:

- `MutationFailureAnswerRenderer`
- `AssistantTurnExecutor`
- `ExecutionOutcome`
- `ChangeSummaryContext`

Why a one-off patch is insufficient:

```text
Changed-files questions naturally produce phrases like "I changed no files",
"zero files were modified", or "nothing was created". The invariant is that
no-change answers are not mutation claims.
```

## Goal

```text
No-change or zero-change answers to changed-files questions must not receive the
false mutation warning.
```

## Non-Goals

- No weakening of false-success detection for real mutation claims.
- No accepting unsupported changed-files claims without runtime ledger evidence.
- No model-only determination of workspace mutation truth.

## Implementation Notes

```text
Add negation-aware tests around `containsMutationClaim` and the changed-files
read-only tool-loop path. Candidate implementation could recognize nearby
no/none/zero/no files/no file/nothing wording before broad `i created` and
`i modified` markers, or force changed-files prompts through the runtime ledger
answer when no mutation history exists.
```

## Architecture Metadata

Capability:

- outcome truthfulness warning

Operation(s):

- read-only changed-files summary

Owning package/class:

- `MutationFailureAnswerRenderer`, `AssistantTurnExecutor`, `ExecutionOutcome`

New or changed tools:

- none

Risk, approval, and protected paths:

- Risk level: medium
- Approval behavior: unchanged
- Protected path behavior: unchanged

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: unchanged
- Evidence obligation: runtime change ledger or explicit no-change evidence
- Verification profile: focused unit tests; installed Ask-mode smoke on closeout
- Repair profile: n/a

Outcome and trace:

- Outcome/truth warnings: suppress false mutation warning for no-change answers
- Trace/debug fields: unchanged unless warning reason is added

Refactor scope:

- `<allowed: mutation-claim predicate refinement, changed-files no-change tests>`
- `<forbidden: removal of false-mutation guard, broad outcome renderer rewrite>`

## Acceptance Criteria

- `I created or modified zero files` does not trigger
  `FALSE_MUTATION_ANNOTATION`.
- Equivalent no-change phrases do not trigger the false mutation warning.
- Real false mutation claims such as `I created README.md` still trigger when no
  mutating tool succeeded.
- Changed-files prompts with no runtime mutation history answer from the runtime
  ledger or with a correctly unannotated no-change answer.
- No regressions to privacy, permissions, checkpointing, trace redaction, or
  outcome truth.

## Tests / Evidence

Required deterministic regression:

- Unit test: `MutationFailureAnswerRendererTest` and/or `AssistantTurnExecutorTest`
- Integration/executor test: changed-files Ask/read-only tool-loop path
- JSON e2e scenario: n/a
- Trace assertion: no false mutation warning in final answer

Manual/TalosBench rerun:

- Prompt family: Ask mode changed-files question after no mutations
- Workspace fixture: fresh local fixture
- Expected trace: read-only tools only, no mutation, no false warning
- Expected outcome: clear zero-files answer

Commands:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.outcome.MutationFailureAnswerRendererTest" --tests "dev.talos.cli.modes.AssistantTurnExecutorTest" --no-daemon
```

Add broader commands if runtime code changes:

```powershell
.\gradlew.bat check --no-daemon
```

## Work-Test Cycle Notes

- Use the inner dev loop.
- No candidate bump.
- Add a `CHANGELOG.md` `Unreleased` line when complete.

## Known Risks

- Negation detection that is too broad could let real false-success claims pass.
  Keep positive and negative phrase matrices together.

## Known Follow-Ups

- None.

