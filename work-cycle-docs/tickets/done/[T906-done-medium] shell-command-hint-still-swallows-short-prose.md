# [T906-done-medium] Shell-command hint still swallows short prose

Status: done
Priority: medium

## Evidence Summary

- Source: static review of T903 remediation
- Date: 2026-06-28
- Talos version / commit: 0.10.6 / 7ac05041
- Model/backend: n/a
- Workspace fixture: n/a
- Raw transcript path: n/a
- Trace path or `/last trace` summary: n/a
- File diff summary: none
- Approval choices: none
- Checkpoint id: n/a
- Verification status: focused red/green regression added and passing locally

Redacted prompt sequence:

```text
talos run tests
talos status repo
talos diagnose issue
```

Expected behavior:

```text
Short prose that starts with "talos" plus a subcommand-homonym should reach the
model unless it is clearly a shell invocation.
```

Observed behavior:

```text
`ShellCommandHint.detect(...)` treats any known subcommand with three or fewer
tokens as a shell command, even without a flag. That still swallows plausible
short prose such as "talos run tests" and "talos status repo".
```

Code evidence:

- `ShellCommandHint.detect(...)` sets `looksShell = tokens.length <= 3 ||
  hasFlagToken(tokens)` for known subcommands:
  `src/main/java/dev/talos/cli/repl/ShellCommandHint.java`.
- `ShellCommandHintTest` covers longer prose (`talos run the tests please`,
  `talos status of the repo`) but not the short three-token forms above:
  `src/test/java/dev/talos/cli/repl/ShellCommandHintTest.java`.

## Classification

Primary taxonomy bucket:

- `INTENT_BOUNDARY`

Secondary buckets:

- `OUTCOME_TRUTH`

Blocker level:

- candidate follow-up

Why this level:

```text
This does not mutate files or weaken trust policy, but it can silently divert a
user prompt away from the model and replace it with a shell-command nudge.
```

## Architectural Hypothesis

Bad ticket framing to avoid:

```text
Add another phrase exception.
```

Architectural hypothesis:

```text
The shell-command hint needs a deterministic syntactic boundary: known command
with a flag, exact binary plus subcommand, or exact documented command shape.
Token count alone is too broad because short natural-language prompts are common.
```

Likely code/document areas:

- `ShellCommandHint`
- `ShellCommandHintTest`
- `ReplRouter` shell-hint wiring, if behavior needs an integration test

Why a one-off patch is insufficient:

```text
The failure mode is an intent-boundary heuristic. It should be pinned by a
matrix of command-shaped versus prose-shaped examples rather than individual
reported phrases only.
```

## Goal

```text
Keep the useful nudge for real shell commands typed into the REPL, while
allowing short natural-language prompts that happen to start with
`talos <subcommand-word>` to reach the model.
```

## Non-Goals

- No shell execution from the REPL prompt.
- No model classifier for command/prose safety.
- No broad rewrite of line classification.

## Implementation Notes

```text
Likely tighten known-subcommand detection to exact two-token invocations or
forms with explicit flags/options. Add regression tests for "talos run tests",
"talos status repo", and real command forms such as "talos setup models --write"
and "talos status --verbose".
```

## Architecture Metadata

Capability:

- REPL prompt intent boundary

Operation(s):

- prompt routing only

Owning package/class:

- `ShellCommandHint`, `ReplRouter`

New or changed tools:

- none

Risk, approval, and protected paths:

- Risk level: medium
- Approval behavior: unchanged
- Protected path behavior: unchanged

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: unchanged
- Evidence obligation: focused unit tests
- Verification profile: no model call required
- Repair profile: n/a

Outcome and trace:

- Outcome/truth warnings: n/a
- Trace/debug fields: n/a

Refactor scope:

- `<allowed: ShellCommandHint heuristic and tests>`
- `<forbidden: shell execution, model-based command classification, broad router rewrite>`

## Acceptance Criteria

- `talos run tests`, `talos status repo`, and `talos diagnose issue` are not
  detected as shell commands.
- Real shell-command forms such as `talos setup models --write`, `talos status
  --verbose`, `talos doctor`, and path-qualified invocations still trigger the
  hint.
- No regressions to privacy, permissions, checkpointing, trace redaction, or
  outcome truth.

## Tests / Evidence

Required deterministic regression:

- Unit test: `ShellCommandHintTest`
- Integration/executor test: optional `ReplRouter` slash/prompt routing test
- JSON e2e scenario: n/a
- Trace assertion: n/a

Manual/TalosBench rerun:

- Prompt family: installed REPL shell-hint smoke
- Workspace fixture: current Talos repo
- Expected trace: n/a
- Expected outcome: shell-shaped commands hint; prose-shaped prompts do not

Commands:

```powershell
.\gradlew.bat test --tests "dev.talos.cli.repl.ShellCommandHintTest" --no-daemon
```

Add broader commands if runtime code changes:

```powershell
.\gradlew.bat check --no-daemon
```

## Work-Test Cycle Notes

- Use the inner dev loop.
- No candidate bump.
- Add a `CHANGELOG.md` `Unreleased` line when complete.

## Resolution - 2026-06-30

- Replaced the known-subcommand `tokens.length <= 3` heuristic with a narrower
  syntactic boundary: exact two-token command, flag-bearing command, or the
  documented `talos setup models` positional command shape.
- Added `ShellCommandHintTest` regressions proving `talos run tests`,
  `talos status repo`, and `talos diagnose issue` reach the model instead of
  receiving the shell-command hint.
- Kept the existing useful hints for `talos doctor`, `talos status --verbose`,
  `talos setup models --write --force`, `talos setup models`, and
  path-qualified invocations such as `./talos setup`.
- Focused evidence: `.\gradlew.bat test --tests
  "dev.talos.cli.repl.ShellCommandHintTest" --no-daemon` failed red on the
  three short-prose examples, then passed after the heuristic change.

## Known Risks

- A clean installed-product smoke should still verify the REPL hint after the
  next global install/candidate build.

## Known Follow-Ups

- None.
