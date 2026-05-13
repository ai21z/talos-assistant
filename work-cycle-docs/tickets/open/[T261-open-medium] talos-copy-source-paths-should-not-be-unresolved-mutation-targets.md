# T261 - Copy Source Paths Should Not Be Unresolved Mutation Targets
Date: 2026-05-13
Status: Open
Priority: Medium

## Why This Ticket Exists

The T252-T258 focused re-audit showed that natural batch workspace operations now work, but later uncertainty summaries reported the copied source file as unresolved:

```text
Unresolved target(s): styles.css.
```

This happened after a successful batch:

```text
mkdir batch-one; mkdir batch-two; copy styles.css -> batch-one/styles-copy.css
```

Evidence:

- Successful Qwen batch: `local/manual-testing/t252-t258-focused-reaudit-20260513-140552/TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt` lines 2038-2056.
- Successful GPT-OSS batch: `local/manual-testing/t252-t258-focused-reaudit-20260513-140552/TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt` lines 2851-2869.
- Qwen uncertainty: `local/manual-testing/t252-t258-focused-reaudit-20260513-140552/TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt` lines 3145-3192.
- GPT-OSS uncertainty: `local/manual-testing/t252-t258-focused-reaudit-20260513-140552/TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt` lines 3966-4013.

## Problem

For copy operations, the source path is evidence/input and the destination path is the mutation target. Current expected-target accounting includes both `styles.css` and `batch-one/styles-copy.css`, so later status logic can report the source as an unresolved mutation target even though it was not supposed to be mutated.

## Goal

Workspace-operation accounting should distinguish source paths from destination mutation targets.

## Scope

In scope:

- Separate copy/move/rename source paths from destination targets in task contract metadata or verification status.
- Keep source existence/readback validation for copy sources.
- Ensure uncertainty/change summaries do not call unchanged copy sources unresolved mutation targets.

Out of scope:

- Full planner rewrite.
- Changing successful batch execution semantics.
- Shell command support.

## Acceptance

- Batch copy still creates both directories and the copied file.
- Source `styles.css` is validated as an input/source, not reported as an unresolved mutation target.
- Uncertainty answer after successful batch does not list `styles.css` as unresolved.
- Tests cover copy source/destination role separation.

## Required Verification

- Unit tests for workspace-operation target extraction/accounting.
- Integration/scripted REPL test for batch copy followed by session uncertainty question.
- Focused audit coverage after implementation.
