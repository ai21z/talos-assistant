# T150 - Stop Or Recover After Satisfied Workspace Operation Postconditions

Status: done
Priority: medium

## Evidence Summary

- Source: focused managed llama.cpp product workflow re-audit
- Date: 2026-05-05
- Talos version / commit at discovery: `v0.9.8` / `c3de157`
- Models/backend: `llama_cpp/qwen2.5-coder-14b`, `llama_cpp/gpt-oss-20b`
- Raw transcript paths:
  - `local/manual-testing/llama-cpp-product-workflow-reaudit-20260505-170318/TEST-OUTPUT-LLAMA-CPP-PRODUCT-WORKFLOW-QWEN-14B.txt`
  - `local/manual-testing/llama-cpp-product-workflow-reaudit-20260505-170318/TEST-OUTPUT-LLAMA-CPP-PRODUCT-WORKFLOW-GPT-OSS-20B.txt`
- Verification status: requested final workspace state was correct, but outcome was partial due later redundant or extraneous tool attempts.

Redacted prompt sequence:

```text
Organize these files using workspace operation tools only: copy README.md to
docs/notes/README-copy.md, move scratch/todo.md to docs/todo.md, then rename
docs/todo.md to tasks.md.

Use talos.apply_workspace_batch only. Apply operations_json for exactly these
operations: mkdir archive, copy_path docs/notes/README-copy.md to
archive/README-copy.md, and rename_path scratch/old-name.txt to
archived-note.txt.
```

Expected behavior:

```text
Once final-state operation facts are satisfied, Talos should not keep asking the
model for more mutation attempts that can turn a completed operation sequence
into a partial outcome. If redundant retries still occur, recovered duplicate
failures should not dominate a successful final state.
```

Observed behavior:

```text
Both final workspaces contained the requested copied, moved, renamed, and
batched destinations. Qwen repeated copy/move/rename operations after the first
success, causing destination-exists and source-not-found failures. GPT-OSS also
attempted an extra nonrequested write after correct operation actions. Final
answers were partial even though requested final-state operation facts were
present.
```

## Classification

Primary taxonomy bucket:

- `ACTION_OBLIGATION`

Secondary buckets:

- `VERIFICATION`
- `OUTCOME_TRUTH`

Blocker level:

- candidate follow-up

Why this level:

```text
This does not corrupt the workspace, but it lowers reliability and makes
successful organize/batch workflows look failed or partial.
```

## Goal

```text
A satisfied workspace operation final state should become a deterministic
success/terminal condition or, at minimum, should dominate redundant duplicate
failures that occurred after the successful operation sequence.
```

## Non-Goals

- No provider-specific prompting.
- No broad planner.
- No delete support.
- No weakening permission checks for nonrequested extra targets.

## Implementation

- Added `MutationFailureRecovery` to classify later duplicate workspace
  operation failures as recovered only when:
  - the failed outcome is mutating, non-denied, and has a workspace operation
    plan,
  - an identical workspace operation plan already succeeded earlier in the same
    turn, and
  - the failure text is duplicate/final-state shaped, such as destination
    already exists or source not found.
- Wired that recovery into:
  - visible partial-mutation answer shaping, and
  - structured `MutationOutcome` classification.
- Updated `ToolCallRepromptStage` expected-target progress so successful
  workspace operation plan effects satisfy expected paths, including:
  - copy sources and destinations,
  - moved/renamed sources that are expected to become absent,
  - batch operation effects,
  - basename aliases such as `tasks.md` for `docs/tasks.md`.

## Acceptance Criteria

- Copy/move/rename sequence that reaches the requested final state is not reported partial only because of later duplicate source-not-found or destination-exists retries.
- Batch workspace apply that reaches requested final state is not reported partial only because of later duplicate batch attempts.
- Extraneous blocked writes remain visible as warnings and are not silently hidden.
- No infinite loop or extra model calls after the expected-target progress path is satisfied.
- No regressions to privacy, permissions, checkpointing, trace redaction, or outcome truth.

## Tests / Evidence

Targeted tests:

```powershell
.\gradlew.bat --no-daemon test --tests dev.talos.runtime.toolcall.ToolCallRepromptStageTest --tests dev.talos.runtime.outcome.MutationOutcomeTest --tests dev.talos.cli.modes.ExecutionOutcomeTest --tests dev.talos.runtime.verification.WorkspaceOperationStaticVerifierTest
```

Full verification:

```powershell
.\gradlew.bat --no-daemon check
.\gradlew.bat --no-daemon installDist
```

Focused audit:

- Directory: `local/manual-testing/t150-workspace-operation-recovery-reaudit-20260505-180421`
- Findings: `local/manual-testing/t150-workspace-operation-recovery-reaudit-20260505-180421/FINDINGS-T150-WORKSPACE-OPERATION-RECOVERY-REAUDIT.md`

Audit result:

- Qwen organize prompt used 3 tools in 1 iteration and readback passed.
- Qwen batch prompt used 1 tool in 1 iteration and readback passed.
- GPT-OSS organize prompt used 1 batch tool in 1 iteration and readback passed.
- GPT-OSS batch prompt used 1 batch tool in 1 iteration and readback passed.
- Both final workspaces contained:
  - `README.md`
  - `docs/notes/README-copy.md`
  - `docs/tasks.md`
  - `archive/README-copy.md`
  - `scratch/archived-note.txt`
- No transcript contained partial/failure truth checks, tool-call limit stops,
  destination-exists failures, or source-not-found failures.

## Known Risks

- Recovery intentionally remains narrow. It does not recover a different failed
  operation plan, denied operation, unsupported operation, or extraneous
  non-workspace mutation.

## Known Follow-Ups

- Broader deterministic postcondition-stop design may still be useful for other
  verifier profiles, but the audited T150 workspace operation path is closed.
