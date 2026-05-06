# T160 - Capability Answer Must Reflect Current Bounded Command Support

Status: done

Severity: medium

## Problem

Talos's deterministic capability answer is stale.

It currently says Talos "cannot use browser, shell, or unsupported binary-document tools unless those capabilities are added." Browser and unsupported binary-document wording is still accurate, but shell/command execution is no longer accurate because Talos now has bounded command execution through `talos.run_command`.

## Evidence

T61-F managed llama.cpp response-quality review:

- `local/manual-testing/llama-cpp-t61f-full-audit-20260506-075339/MODEL-RESPONSE-QUALITY-REVIEW.md`
- Turn 1 for both Qwen and GPT-OSS returned the stale capability answer.

Relevant code:

- `src/main/java/dev/talos/runtime/policy/CapabilityAnswerPolicy.java`
- `src/main/java/dev/talos/cli/repl/TalosBootstrap.java` registers `RunCommandTool`.
- `src/main/java/dev/talos/runtime/command/CommandToolPlanner.java` defines `talos.run_command`.
- `src/main/java/dev/talos/runtime/toolcall/ToolSurfacePlanner.java` exposes `talos.run_command` for command/verification-capable turns.

## Scope

- Update the deterministic capability answer to reflect current Talos capabilities:
  - inspect/list/read/search/retrieve workspace context;
  - create/edit/move/copy/organize files after approval;
  - run approved bounded command profiles such as Gradle verification through `talos.run_command`;
  - no browser automation unless that capability is added;
  - unsupported binary documents cannot be inspected as document contents through the current text-tool surface.
- Keep the answer brief.
- Keep no-inspection behavior for capability questions.

## Acceptance

- Capability-answer tests assert the updated command-capable wording.
- The answer does not claim raw shell access or arbitrary command execution.
- The answer does not claim browser support.
- Existing identity/small-talk tests still pass.
- `.\gradlew.bat --no-daemon check installDist` passes.

## Non-Goals

- Do not expand command execution scope.
- Do not expose hidden/internal debug commands.
- Do not add browser support.
