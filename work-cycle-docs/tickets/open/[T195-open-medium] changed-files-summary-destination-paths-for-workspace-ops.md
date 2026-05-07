# T195 - Changed-Files Summary Destination Paths For Workspace Operations

Status: open
Severity: medium

## Evidence

Source audit:

- `local/manual-testing/llama-cpp-t61o-full-e2e-audit-20260507-162435/FINDINGS-LLAMA-CPP-T61O-FULL-E2E-AUDIT.md`

Concrete evidence:

- `TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt:23903-23921`
- `TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt:22922-22935`

## Problem

Runtime-owned changed-files summaries report source or intermediate paths for `copy_path`, `rename_path`, and `move_path` operations instead of the resulting destination path.

Example:

- Copy reports `README.md` instead of `workspace-notes/readme-copy.md`.
- Rename reports `workspace-notes/readme-copy.md` instead of `workspace-notes/readme-renamed.md`.
- Move reports `workspace-notes/readme-renamed.md` and leaves `archive/readme-renamed.md` unresolved even though the destination exists.

## Scope

In scope:

- Ensure changed-files history records resulting changed paths for copy, rename, and move operations.
- Ensure expected-target/readback summaries resolve destination paths for these workspace operations.
- Preserve existing successful operation output.

Out of scope:

- Redesigning all trace history.
- Changing command approval or workspace mutation permissions.
- Model prompt changes.

## Acceptance

- Tests cover copy, rename, and move summaries.
- Changed-files output lists final destination paths for successful operations.
- No unresolved expected target is reported when the destination file exists and readback passed.
- Existing write/edit summaries keep their current behavior.

