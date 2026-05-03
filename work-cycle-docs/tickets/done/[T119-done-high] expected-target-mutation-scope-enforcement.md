# T119 - Expected-Target Mutation Scope Enforcement

Severity: high
Status: done

## Problem

The focused managed llama.cpp audit showed that Talos correctly injected expected targets and static verification correctly failed wrong targets, but unrelated writes could still execute before verification. GPT-OSS wrote `README.md` and `notes.md` during a task whose expected targets were only `index.html`, `styles.css`, and `scripts.js`.

This was not a prompt-construction problem. It was a pre-execution policy gap: expected targets were verifier-owned after the fact, but not yet an execution allowlist for mutating tools.

## Implementation

- Added pre-approval expected-target validation in `TurnProcessor`.
- Blocks `talos.write_file` and `talos.edit_file` when the current mutation-allowed task contract has expected targets and the tool path is outside that exact set.
- Preserves exact sibling distinction such as `script.js` versus `scripts.js`.
- Records traceable `TOOL_CALL_BLOCKED` events for pre-approval validation failures.
- Converts expected-target scope blocks in the tool loop into failure-dominant stops.
- Preserves the legacy off-scope warning scenario for broad mutation prompts that do not have exact expected targets.

## Verification

- `./gradlew.bat --no-daemon test --tests dev.talos.runtime.TurnProcessorTest --tests dev.talos.runtime.ToolCallLoopTest`
- `./gradlew.bat --no-daemon test`
- `./gradlew.bat --no-daemon installDist`
- `./gradlew.bat --no-daemon e2eTest --tests dev.talos.harness.JsonScenarioPackTest.offScopeMutationWarning`
- `./gradlew.bat --no-daemon build`
- `git diff --check`

## Result

Expected-target writes are now blocked before approval, checkpointing, or file mutation when the model chooses an unrelated path. Valid writes to exact expected targets still execute.
