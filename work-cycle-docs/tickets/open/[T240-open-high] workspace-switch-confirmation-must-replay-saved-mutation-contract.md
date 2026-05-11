# T240 - Workspace-Switch Confirmation Must Replay Saved Mutation Contract

Severity: high

## Problem

After a failed/unsupported workspace switch, Talos correctly fences the next relative mutation and asks for confirmation. But when the user confirms, the actual `CurrentTurnCapability` frame still treats the confirmation text as a read-only workspace explanation instead of replaying the saved mutation request.

Result: the confirmation turn exposes read-only tools and the folder is not created.

## Evidence

Audit:
`local/manual-testing/user-perspective-broad-reaudit-20260511-143729/FINDINGS-USER-PERSPECTIVE-BROAD-REAUDIT.md`

Transcript:
- Qwen confirmation prompt: `TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt:9820`
- Qwen actual frame is read-only: `TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt:9833`
- Qwen deterministic failure: `TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt:9842`
- GPT-OSS confirmation prompt: `TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt:9898`
- GPT-OSS actual frame is read-only: `TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt:9911`
- GPT-OSS failed read attempts: `TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt:9940` through `9945`

## Scope

- When a pending workspace mutation confirmation is accepted, replay the saved original mutation as the effective current request before:
  - task contract resolution,
  - capability-frame construction,
  - tool-surface planning,
  - prompt audit rendering.
- Ensure prompt audit summary and injected frame agree.
- Expose the correct workspace operation tool, for example `talos.mkdir`, or perform a runtime-owned deterministic operation if that is the established local pattern.
- Preserve the existing safety behavior: if the user does not confirm, no mutation is applied.

## Acceptance

- Test the sequence:
  1. "Change your workspace to Desktop."
  2. "Create a folder named should-not-be-on-desktop."
  3. "Yes, create it in the current workspace."
- Step 2 produces the confirmation fence and no mutation.
- Step 3 uses the saved folder-create request as the effective task, exposes mutating workspace-operation tools, and creates the directory in the unchanged workspace after approval.
- Prompt audit summary and `CurrentTurnCapability` frame both identify a mutating workspace operation.
- Both no-confirmation and rejection paths remain non-mutating.
- Targeted tests and full Gradle tests pass.

