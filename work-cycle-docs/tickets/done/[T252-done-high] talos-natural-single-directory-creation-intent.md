# T252 - Natural Single Directory Creation Intent
Date: 2026-05-12
Status: Done
Priority: High

## Why This Ticket Exists

The model setup two-model audit used a normal user phrasing:

```text
make me a folder called ideas.
```

Expected:
- Talos classifies the turn as mutation allowed.
- `talos.mkdir` is visible.
- `ideas/` is created after approval.

Observed:
- Qwen received `READ_ONLY_QA`, `mutationAllowed=false`, and no mutation tools.
- GPT-OSS received the same read-only contract and tried to inspect `ideas/.gitkeep`.
- No `ideas/` directory was created by this turn.

Evidence:
- `local/manual-testing/model-setup-two-model-audit-20260512-192757/TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt` lines 1415-1493.
- `local/manual-testing/model-setup-two-model-audit-20260512-192757/TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt` lines 1503-1594.

## Problem

`MutationIntent` recognizes direct mutation verbs like `create`, `mkdir`, and `make` for artifact nouns, but the natural phrase `make me a folder called ideas` falls through to `non-mutating`.

`WorkspaceOperationIntent` already knows how to detect mkdir-like requests, but it is only consulted after a task contract is mutation allowed. The current ordering means a clear directory-creation request can lose mutation capability before the workspace-operation detector can help.

## Goal

Natural directory creation requests should be first-class mutation requests.

Examples:

```text
make me a folder called ideas
make a folder called docs
create a directory named reports
mkdir scratch
```

## Scope

In scope:
- Add deterministic mutation-intent coverage for natural single directory creation.
- Ensure `TaskContractResolver` returns mutation allowed for these prompts.
- Ensure `ToolSurfacePlanner` exposes `talos.mkdir` for single-directory creation.
- Add focused tests for `make me a folder called ideas`.
- Preserve read-only behavior for questions like `what is a folder called ideas?`.

Out of scope:
- Full natural-language planner.
- Batch multi-operation extraction, already covered by `talos-natural-batch-directory-target-extraction.md`.
- Shell command execution.

## Acceptance

- `make me a folder called ideas` resolves to a mutation-allowed task.
- Visible tool surface contains `talos.mkdir`, not only read-only tools.
- The expected target includes `ideas`.
- A scripted tool-loop test creates `ideas/` after approval/readback.
- Existing small-talk and read-only classification tests still pass.

## Required Verification

- Unit tests for `MutationIntent`, `TaskContractResolver`, and `ToolSurfacePlanner`.
- A focused scripted REPL/e2e scenario proving `talos.mkdir` is exposed and `ideas/` is created.
- Focused two-model audit coverage before closing the milestone batch.

## Closure Evidence

Closed after focused Qwen/GPT-OSS llama.cpp re-audit:

- `local/manual-testing/t252-t258-focused-reaudit-20260513-140552/TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt` lines 79-117.
- `local/manual-testing/t252-t258-focused-reaudit-20260513-140552/TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt` lines 79-117.

Both models received a mutation-allowed mkdir contract, approval was requested, and `ideas/` was created. The same audit exposed a separate natural `List names only...` DevMode interception issue, tracked separately as T260.
