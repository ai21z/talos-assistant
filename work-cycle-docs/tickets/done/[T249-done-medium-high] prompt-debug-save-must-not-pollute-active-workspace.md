# [done] T249: Prompt-Debug Save Must Not Pollute Active Workspace

Date: 2026-05-11
Priority: medium-high
Status: done

## Why This Ticket Exists

The broader T245-T247 audit used `/prompt-debug save` after natural turns. The command wrote prompt-debug Markdown and provider-body JSON files under `local/prompts` inside the active audited workspace.

Those files then appeared in later provider prompts as normal workspace files.

## Evidence

- `local/manual-testing/t245-t247-broader-audit-20260511-211949/TEST-OUTPUT-QWEN-14B.txt` provider bodies include many `local/prompts/prompt-debug-*.md` and `.provider-body.json` entries in the file structure.
- GPT-OSS provider bodies show the same pattern.
- `src/main/java/dev/talos/cli/repl/slash/PromptDebugCommand.java:60` and `:84` use `Path.of("local", "prompts").toAbsolutePath().normalize()`.

## Problem

Clean milestone audits require transcript and prompt artifacts, but those artifacts should not become part of the workspace being audited.

Current behavior creates three risks:

- The model sees internal audit files as user workspace files.
- File listings and context become noisy and less user-realistic.
- Long sessions spend context budget on prompt-debug artifacts. Qwen later failed a mutation retry with a context-budget error.

## Scope

In scope:

- Add a way to save prompt-debug artifacts outside the active workspace.
- Prefer one of:
  - explicit `/prompt-debug save <directory>` support;
  - environment/configured artifact root such as `TALOS_PROMPT_DEBUG_DIR`;
  - Talos state directory outside workspace.
- Keep the current simple `/prompt-debug save` workflow ergonomic.
- Ensure saved prompt-debug files are not included in normal workspace file-structure context unless they are truly inside the user's workspace by explicit choice.
- Update maintainer/audit docs if command syntax changes.

Out of scope:

- Removing prompt-debug.
- Removing provider-body JSON capture.
- Changing redaction policy except where needed for output location.

## Acceptance Criteria

- A clean audit can save prompt-debug artifacts outside the model's active workspace.
- `local/prompts/prompt-debug-*.md` does not appear in file-structure context during the audit unless the operator explicitly chooses an in-workspace destination.
- `/prompt-debug save` and `/prompt-debug save-all` tests cover the default destination and an explicit/configured destination.
- Prompt-debug save output clearly prints the destination path.
- Existing prompt-debug redaction tests still pass.

## Likely Files

- `src/main/java/dev/talos/cli/repl/slash/PromptDebugCommand.java`
- `src/test/java/dev/talos/cli/repl/slash/PromptDebugCommandTest.java`
- `docs` or `work-cycle-docs` audit workflow documentation

## Verification Plan

- Add tests for save destination behavior.
- Run prompt-debug command tests.
- Run `.\gradlew test --no-daemon`.
- Re-run a short audit probe that saves prompt-debug after several turns and confirms prompt-debug files do not enter active workspace context.

## Done Notes

- Changed hidden `/prompt-debug save` and `/prompt-debug save-all` to default outside the active workspace at `~/.talos/prompt-debug`.
- Added destination precedence: explicit command directory, `talos.promptDebugDir`, `TALOS_PROMPT_DEBUG_DIR`, default state directory.
- Added explicit destination support for `save` and `save-all`.
- Verified prompt-debug tests, full `.\gradlew test --no-daemon`, and `.\gradlew build --no-daemon`.
