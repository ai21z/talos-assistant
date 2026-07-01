# T187 - Prompt Debug Must Save All Provider Requests In Tool Loops

Status: done
Severity: medium

## Problem

Focused T186 command auditing exposed a prompt-debug observability gap.

After a tool loop, `/prompt-debug last` and `/prompt-debug save` show only the latest provider request. For a command turn, that latest request can be the post-tool answer request after `talos.run_command` has already executed. It does not preserve the initial pre-tool provider request where the tool surface and forced tool-choice policy were selected.

This can mislead audits:

- The runtime behavior is correct.
- The model called `talos.run_command`.
- The visible tool surface was command-only.
- But the saved provider body showed `Tool choice: AUTO` because it was the post-tool answer request, not the initial command request.

## Evidence

Audit:

`local/manual-testing/llama-cpp-t186-command-focused-audit-20260507-144029/FINDINGS-T186-COMMAND-FOCUSED-AUDIT.md`

Observed shape:

- Transcript prompt audit showed `nativeTools: talos.run_command`.
- Both models called `talos.run_command`.
- `/prompt-debug last` showed `Tool choice: AUTO`.
- Saved provider body only represented the post-tool answer request.

## Scope

Add prompt-debug history capture and an internal save command for all user-facing provider requests captured during the latest turn/process window.

In scope:

- Keep all non-background prompt-debug snapshots since the last `PromptDebugCapture.clear()`.
- Add hidden maintainer command `/prompt-debug save-all`.
- Save each prompt render and provider body in order.
- Write an index file listing the saved artifacts.
- Keep `/prompt-debug last` and `/prompt-debug save` behavior compatible.
- Keep background maintenance captures excluded from user-facing history.

Out of scope:

- Changing model prompts.
- Changing provider request behavior.
- Changing redaction policy beyond preserving existing redaction behavior for each saved snapshot.

## Acceptance

- Tests prove `save-all` writes multiple captures in order.
- Tests prove background maintenance captures are excluded from `save-all`.
- Tests prove provider bodies are still redacted.
- Existing prompt-debug command tests pass.
- Future audits can use `/prompt-debug save-all` after tool-loop turns when initial and post-tool requests both matter.

## Completion Notes

Implemented:

- `PromptDebugCapture` now keeps non-background user-facing prompt-debug history since the last clear.
- `/prompt-debug save-all` saves each captured render and provider-body JSON in order.
- `save-all` writes a history index under `local/prompts`.
- Existing `/prompt-debug last` and `/prompt-debug save` behavior remains compatible.

Verification:

- Red test first:
  - `PromptDebugCommandTest*saveAllWritesUserFacingCaptureHistoryInOrderAndSkipsBackground`
- Targeted tests passed after implementation:
  - `PromptDebugCommandTest`
  - `LlmClientPromptDebugCaptureTest`
  - `CompatChatClientTest`
  - `OllamaPromptDebugCaptureTest`
- Full verification passed:
  - `.\gradlew.bat test --no-daemon`
  - `.\gradlew.bat build --no-daemon`
  - `.\gradlew.bat installDist --no-daemon`

Focused audit:

- `local/manual-testing/llama-cpp-t187-prompt-debug-history-audit-20260507-144811/FINDINGS-T187-PROMPT-DEBUG-HISTORY-AUDIT.md`
- Qwen and GPT-OSS both produced `save-all` histories with the initial provider body preserving `"tool_choice" : "required"` and the command-only `talos.run_command` surface.
