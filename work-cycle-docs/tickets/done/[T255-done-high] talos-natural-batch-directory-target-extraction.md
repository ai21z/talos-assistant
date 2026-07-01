# T255 - Natural Batch Directory Target Extraction
Date: 2026-05-12
Status: Done
Priority: High

## Why This Ticket Exists

The model setup two-model audit used a normal user phrasing:

```text
batch this: create batch-one and batch-two, then copy styles.css to batch-one/styles-copy.css.
```

Expected:
- Create `batch-one`.
- Create `batch-two`.
- Copy `styles.css` to `batch-one/styles-copy.css`.
- Prefer `talos.apply_workspace_batch`, or at least expose `mkdir` and `copy_path`.

Observed:
- Current-turn frame exposed only `talos.copy_path`.
- Expected targets were only `styles.css, batch-one/styles-copy.css`.
- `batch-two` was not planned or verified.
- GPT-OSS copied the file but did not create `batch-two`.
- Qwen produced an invalid tool-call payload and made no changes.

Evidence:
- `local/manual-testing/model-setup-two-model-audit-20260512-192757/TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt` lines 6135-6205.
- `local/manual-testing/model-setup-two-model-audit-20260512-192757/TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt` lines 6829-6925.

## Problem

`TaskContractResolver.BATCH_DIRECTORY_CREATION_SPAN` recognizes explicit phrases
like:

```text
create directories batch-one and batch-two
```

but not common natural phrasing:

```text
create batch-one and batch-two
make assets and drafts
create folder docs and copy README.md into docs/README.md
```

## Goal

Natural multi-step workspace operation requests should expose the right
workspace-operation tools and include all directory targets in verification.

## Scope

In scope:
- Improve extraction for natural `create <dir> and <dir>, then copy/move/rename...` requests.
- Prefer `talos.apply_workspace_batch` when the user explicitly says `batch this` or describes multiple workspace operations.
- Add tests for directory targets plus copy/move/rename destination targets.

Out of scope:
- Full planner.
- Shell command execution.
- File content creation.

## Acceptance

- The audit prompt exposes `talos.apply_workspace_batch` or a sufficient workspace operation surface.
- Expected targets include `batch-one`, `batch-two`, `styles.css`, and `batch-one/styles-copy.css`.
- A successful batch creates both directories and copies the file.
- Existing exact workspace-operation tests continue passing.

## Required Verification

- Unit tests for natural batch directory extraction and workspace-operation intent detection.
- Integration/scripted REPL test proving both directories and the copied file exist.
- Focused two-model audit coverage before closing the milestone batch.

## Closure Evidence

Closed after focused Qwen/GPT-OSS llama.cpp re-audit:

- `local/manual-testing/t252-t258-focused-reaudit-20260513-140552/TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt` lines 2038-2056.
- `local/manual-testing/t252-t258-focused-reaudit-20260513-140552/TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt` lines 2851-2869.

Both models used `talos.apply_workspace_batch`, approval was requested once, and the final workspace state contains `batch-one/`, `batch-two/`, and `batch-one/styles-copy.css`. The audit also exposed a separate source/destination accounting issue for copied source files in later uncertainty summaries, tracked separately as T261.
