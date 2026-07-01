# [done] T250: Verify-Only Command Repair Must Match Tool Surface

Date: 2026-05-11
Priority: medium-high
Status: done

## Why This Ticket Exists

The broader T245-T247 audit found a control contradiction in command verification turns.

The user asked:

```text
use talos.run_command with an approved bounded profile to check this workspace. if no approved profile applies, say that truthfully.
```

Talos narrowed the visible tool surface to `talos.run_command`, but the evidence-retry prompt told the model to start with `talos.list_dir`.

## Evidence

- Qwen transcript: `local/manual-testing/t245-t247-broader-audit-20260511-211949/TEST-OUTPUT-QWEN-14B.txt:8100`

```text
[error] Current-turn tool surface did not allow talos.list_dir. Allowed tools: talos.run_command.
```

- Qwen then tried `gradle_check`, which failed because the audit fixture has no `gradlew.bat`: `TEST-OUTPUT-QWEN-14B.txt:8106`.
- GPT-OSS also attempted `gradle_check` and failed because the fixture has no Gradle wrapper: `TEST-OUTPUT-GPT-OSS-20B.txt:9476`.
- Code path: `AssistantTurnExecutor.evidenceRetryPrompt(...)` instructs `talos.list_dir`; `ToolSurfacePlanner` can narrow explicit command requests to only `talos.run_command`.

## Problem

The runtime should not issue a repair instruction that names a tool unavailable in the current-turn tool surface.

For command verification requests, Talos also needs a deterministic way to say "no approved profile applies here" when a workspace has no Gradle wrapper, instead of letting the model guess a profile and then failing at process launch.

## Scope

In scope:

- Align verify-only evidence repair text with the actual visible tools.
- If the only visible tool is `talos.run_command`, do not instruct `talos.list_dir`.
- Add a preflight or planner check for Gradle profile applicability in the current workspace.
- For a workspace without `gradlew.bat`/`gradlew`, return a clear deterministic outcome such as "no Gradle command profile applies in this workspace" or a preflight failure before approval/process launch.
- Preserve real Gradle workspace behavior.

Out of scope:

- Adding arbitrary shell execution.
- Adding non-Gradle command profiles.
- Changing command approval policy.

## Acceptance Criteria

- A verify-only command request with native tools narrowed to `talos.run_command` never injects a repair prompt that tells the model to use `talos.list_dir`.
- In a non-Gradle fixture workspace, Talos does not attempt to execute `.\gradlew.bat` blindly after the user asks "if no approved profile applies, say that truthfully."
- The final answer is failure/truth dominant and does not claim a check was run when no applicable profile exists.
- In a real Gradle workspace, `gradle_check` still runs through `talos.run_command`.
- Trace/prompt-debug clearly shows the command applicability decision.

## Likely Files

- `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java`
- `src/main/java/dev/talos/runtime/toolcall/ToolSurfacePlanner.java`
- `src/main/java/dev/talos/runtime/command/CommandToolPlanner.java`
- `src/main/java/dev/talos/tools/impl/RunCommandTool.java`
- `src/test/java/dev/talos/tools/impl/RunCommandToolTest.java`
- `src/test/java/dev/talos/runtime/toolcall/ToolSurfacePlannerTest.java`
- `src/test/java/dev/talos/cli/modes/AssistantTurnExecutorTest.java`

## Verification Plan

- Add a prompt-construction test for verify-only command repair with command-only tool surface.
- Add command planner/tool tests for missing Gradle wrapper.
- Add one positive test in a fixture with `gradlew.bat`.
- Run targeted tests.
- Run `.\gradlew test --no-daemon`.
- Re-run a focused audit probe with both:
  - non-Gradle workspace;
  - Talos repo workspace.

## Done Notes

- Added command-specific verify-only retry framing that uses `talos.run_command` and does not name unavailable file-inspection tools.
- Added Gradle wrapper preflight for approved Gradle command profiles before approval or process launch.
- Updated command, approval, and trace fixtures to model real Gradle workspaces with a wrapper when command execution is expected.
- Verified targeted command/prompt tests, full `.\gradlew test --no-daemon`, and `.\gradlew build --no-daemon`.
