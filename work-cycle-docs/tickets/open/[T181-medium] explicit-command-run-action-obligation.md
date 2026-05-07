# [T181-medium] Explicit Command-Run Action Obligation

Status: open
Priority: medium

## Evidence Summary

- Source: managed llama.cpp T61-K full E2E audit
- Date: 2026-05-07
- Branch: `v0.9.0-beta-dev`
- Commit under audit: `417ab98`
- Findings report:
  - `local/manual-testing/llama-cpp-t61k-full-e2e-audit-20260507-071629/FINDINGS-LLAMA-CPP-T61K-FULL-E2E-AUDIT.md`

Observed prompt:

```text
Run the approved Gradle test command profile for this workspace and report the exact command result. Do not invent a pass if the command cannot run.
```

Observed behavior:

- GPT-OSS did not call `talos.run_command`.
- Prompt audit classified the turn as `VERIFY_ONLY`.
- The model used read/search tools and answered that no Gradle project was present.
- Qwen did call `talos.run_command`, requested approval, and reported the command failure truthfully.

Concrete evidence:

- GPT-OSS prompt: `TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt:16471`
- GPT-OSS task contract: `TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt:16534`
- Repeated command prompt context: `TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt:17146`

## Problem

Explicit command-run requests are still model-controlled. Talos exposes
`talos.run_command`, but the runtime does not represent "run this approved
command profile" as a deterministic action obligation.

## Goal

When the user explicitly asks Talos to run an approved command profile, the turn
should require `talos.run_command` or produce a deterministic failure explaining
why the command could not be run.

## Scope

In scope:

- Detect explicit command-run requests for approved command profiles.
- Classify them as command action obligations, not generic read-only verification.
- Require `talos.run_command` in the tool-loop path when the command profile is available.
- If the model fails to emit a command tool call, record a typed no-command breach.
- Preserve approval flow and safe command policy.

Out of scope:

- New command profiles.
- Automatically running arbitrary unapproved shell commands.
- Changing command sandbox policy.
- Full provider abstraction work.

## Acceptance

- The audited prompt is not classified as generic `VERIFY_ONLY` without a command obligation.
- GPT-OSS-like no-command/read-only behavior becomes a deterministic obligation breach or bounded enforced retry.
- Qwen-like successful command-tool behavior still follows the existing approval path.
- Final output reports the exact command result when run, and never invents a pass.
- Tests cover:
  - explicit approved Gradle test command request,
  - no-command model response,
  - approval path,
  - command failure path,
  - no regression for read-only "tell me what command I should run" prompts.

## Suggested Verification

```powershell
./gradlew.bat test --tests "*Command*" --tests "*ToolCallLoop*" --no-daemon
./gradlew.bat test --tests dev.talos.cli.modes.AssistantTurnExecutorTest --no-daemon
./gradlew.bat check --no-daemon
```
