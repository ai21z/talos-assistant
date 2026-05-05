# T150 - Stop Or Recover After Satisfied Workspace Operation Postconditions

Status: open
Priority: medium

## Evidence Summary

- Source: focused managed llama.cpp product workflow re-audit
- Date: 2026-05-05
- Talos version / commit: `v0.9.8` / `c3de157`
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

## Architectural Hypothesis

Bad ticket framing to avoid:

```text
Tell the model not to repeat operations.
```

Architectural hypothesis:

```text
The tool loop does not have a deterministic terminal transition for "requested
operation postconditions are now satisfied." It keeps the model-controlled phase
open, so repeated tool calls can create avoidable failures. The runtime should
either stop after verified postcondition success or recover redundant duplicate
failures when final operation facts pass.
```

Likely code/document areas:

- `src/main/java/dev/talos/runtime/toolcall/ToolCallLoop.java`
- `src/main/java/dev/talos/runtime/verification/StaticTaskVerifier.java`
- `src/main/java/dev/talos/cli/modes/ExecutionOutcome.java`
- `src/main/java/dev/talos/runtime/outcome/MutationOutcome.java`

Why a one-off patch is insufficient:

```text
This is a state-machine boundary, not a wording issue. Similar failures can
occur for copy/move/rename and batch operations whenever a model repeats an
already satisfied operation.
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

## Architecture Metadata

Capability:

- Workspace organization and batch workspace apply.

Operation(s):

- `talos.copy_path`
- `talos.move_path`
- `talos.rename_path`
- `talos.apply_workspace_batch`

Owning package/class:

- `dev.talos.runtime.toolcall`
- `dev.talos.runtime.verification.StaticTaskVerifier`
- `dev.talos.cli.modes.ExecutionOutcome`

New or changed tools:

- None.

Risk, approval, and protected paths:

- Risk level: medium; outcome truth and loop termination.
- Approval behavior: unchanged.
- Protected path behavior: unchanged.

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: unchanged.
- Evidence obligation: none.
- Verification profile: workspace operation final-state facts.
- Repair profile: bounded tool-loop continuation.

Outcome and trace:

- Outcome should distinguish requested operation success from redundant retry failure.
- Trace should expose any deterministic postcondition-stop or recovered duplicate failure.

Refactor scope:

- Allowed: small postcondition helper or recovered-failure classifier.
- Forbidden: full planner/state-machine rewrite in this ticket.

## Acceptance Criteria

- Copy/move/rename sequence that reaches the requested final state is not reported partial only because of later duplicate source-not-found or destination-exists retries.
- Batch workspace apply that reaches requested final state is not reported partial only because of later duplicate batch attempts.
- Extraneous blocked writes remain visible as warnings and are not silently hidden.
- No infinite loop or extra model calls after a deterministic success-stop path.
- No regressions to privacy, permissions, checkpointing, trace redaction, or outcome truth.

## Tests / Evidence

Required deterministic regression:

- Unit/integration test for copy/move/rename success followed by duplicate failures.
- Unit/integration test for batch apply success followed by duplicate failure.
- Outcome test proving final requested state is not downgraded to partial by recovered duplicate retries.

Manual/TalosBench rerun:

- Prompt family: focused organize and batch prompts from the product workflow audit.
- Expected outcome: requested operation facts pass; redundant retries do not dominate.

Commands:

```powershell
.\gradlew.bat --no-daemon test --tests dev.talos.runtime.verification.WorkspaceOperationStaticVerifierTest --tests dev.talos.cli.modes.ExecutionOutcomeTest
.\gradlew.bat --no-daemon check
```

## Work-Test Cycle Notes

- Implement after T149 unless it turns out the loop-control change is needed to close T149.

## Known Risks

- Incorrectly recovering a real failed operation as duplicate would overstate completion.

## Known Follow-Ups

- Broader postcondition-stop design for all deterministic verifier profiles.
