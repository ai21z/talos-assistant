# T259 - Source-Derived Artifacts Must Read Source Before Writing
Date: 2026-05-13
Status: Open
Priority: High

## Why This Ticket Exists

The T252-T258 focused re-audit showed that Talos now builds the correct source-to-target contract, but Qwen can still write an ungrounded target artifact without reading the required source first.

Prompt:

```text
summarize long-notes.txt into ideas/summary.md. keep it tight. don't touch private files.
```

Observed:

- The current-turn frame correctly set `ideas/summary.md` as the expected mutation target.
- The current-turn frame correctly set `long-notes.txt` as the source evidence target.
- Qwen wrote `ideas/summary.md` without calling `talos.read_file` on `long-notes.txt`.
- Runtime marked the turn evidence-incomplete, but the placeholder file was still created after approval.
- A later read-only answer treated that placeholder as meaningful evidence.

Evidence:

- `local/manual-testing/t252-t258-focused-reaudit-20260513-140552/TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt` lines 429-468.
- `local/manual-testing/t252-t258-focused-reaudit-20260513-140552/TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt` lines 474-491.
- Final Qwen workspace: `local/manual-workspaces/t252-t258-focused-reaudit-20260513-140552/llama-cpp-qwen-14b-workspace/ideas/summary.md` contained only `This is a summary of long-notes.txt.`

## Problem

`EvidenceObligationVerifier` can detect the missing source read, and `ExecutionOutcome` can suppress success prose, but the write has already happened. For source-derived artifact tasks, that is too late: the runtime allowed an ungrounded artifact to be created.

The issue is not scoped privacy negation anymore. T253 fixed that contract path. The remaining bug is source-evidence gating for source-derived mutations.

## Goal

For source-derived artifact tasks, Talos must not commit or present ungrounded derived files as useful output when the required source was not read in the same turn.

## Scope

In scope:

- Treat `READ_TARGET_REQUIRED` source evidence as a precondition for derived writes where the output content depends on that source.
- If the model proposes a write before reading the source, either:
  - force a bounded read-source retry before approval/write execution, or
  - reject/contain the write before it mutates the workspace.
- Preserve privacy clauses such as `don't touch private files`.
- Add tests for source summary and source-to-static-artifact cases.

Out of scope:

- General planner rewrite.
- Long-term memory/RAG summary validation.
- Binary PDF/DOCX support.

## Acceptance

- A scripted model that writes `ideas/summary.md` without reading `long-notes.txt` does not leave a misleading source-derived artifact as a successful output.
- Runtime outcome names the missing source target.
- A model that reads `long-notes.txt` and then writes `ideas/summary.md` still succeeds.
- Protected/private files are not read when the user says not to touch private files.
- Tests assert both tool-order transition and final workspace state.

## Required Verification

- Unit tests for source-evidence gating.
- AssistantTurnExecutor or ToolCallLoop integration test covering write-before-read.
- Focused two-model audit probe using Qwen and GPT-OSS after implementation.
