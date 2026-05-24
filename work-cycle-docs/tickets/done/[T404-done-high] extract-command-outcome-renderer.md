# [T404-done-high] Extract Command Outcome Renderer

## Status

Done.

## Decision

Command outcome result selection and command-specific final-answer replacement text now belong to
`dev.talos.runtime.outcome.CommandOutcomeRenderer`.

`ExecutionOutcome` remains the CLI-mode orchestration facade for final outcome assembly, but it no longer owns:

- `talos.run_command` success/failure/denial conclusion selection
- explicit command-required-but-not-run replacement text
- unsupported Python command replacement text
- verify-only command satisfaction predicates

## Scope

Implemented:

- Added `CommandOutcomeRenderer`.
- Delegated command conclusion and command replacement wording from `ExecutionOutcome`.
- Preserved backend alias support through `ToolAliasPolicy`.
- Added focused renderer tests for failure, timeout, denial, success punctuation, missing command, alias, and task-contract predicates.

Explicitly not changed:

- outcome dominance ordering
- evidence-obligation handling
- protected-read containment
- static verification annotations
- trace wording
- final summary selection
- command execution policy
- command approval policy

## Behavior Preservation

The renderer keeps the existing command wording:

- `[Command failed: talos.run_command did not finish successfully.]`
- `[Command timed out: talos.run_command did not finish successfully.]`
- `[Command not run: talos.run_command was blocked before execution.]`
- `[Command not run: talos.run_command was required for this explicit command request.]`
- `[Command not run: Python execution is outside the current bounded command profile.]`

It also preserves:

- first command failure dominance over later success
- first command success when no command failure exists
- punctuation normalization for successful command summaries
- default successful command summary when the tool summary is blank
- backend alias recognition for command tool names

## Verification

Local verification:

- RED `CommandOutcomeRendererTest` failed before implementation because `CommandOutcomeRenderer` did not exist.
- GREEN `CommandOutcomeRendererTest` passed after extraction.
- Focused outcome regression tests passed:
  - `CommandOutcomeRendererTest`
  - `ExecutionOutcomeTest`
  - `OutcomeDominancePolicyTest`

Final ticket gate:

- `.\gradlew.bat validateArchitectureBoundaries --no-daemon`
- `git diff --check`
- `.\gradlew.bat check --no-daemon`

## Next

Inspect post-T404 `ExecutionOutcome` shape before choosing T405.

The likely next area is static verification outcome rendering, but it must be source-checked first because it may mix verification annotation wording, protected-read/evidence containment, and dominance policy.
