# [done] Ticket: Scripted REPL Stdin Approval Alignment
Date: 2026-04-26
Priority: medium
Status: done
Architecture references:
- `work-cycle-docs/work-test-cycle.md`
- `work-cycle-docs/work-test-cycle-step-by-step.md`
- `docs/new-architecture/30-cli-ui-output-architecture-audit.md`
- `work-cycle-docs/tickets/talos-cli-normal-output-log-noise.md`

## Why This Ticket Exists

Installed manual verification is part of the Talos work-test cycle. The current
scripted capture path can drive the REPL through redirected stdin, but the
captured transcript still shows prompt/input alignment artifacts.

Observed during installed verification on 2026-04-26:

```text
talos [auto] > Now apply ...
  Allow? [y=yes, a=yes for session, N=no]
...
No file changes were applied because approval was denied for:
- index.html: approval denied
...
talos [auto] > n
I'm sorry, I didn't understand your last message.
```

The denial itself worked and the playground stayed clean, but the scripted `n`
also reached the next REPL turn. This makes manual evidence noisier and can
confuse review.

## Problem

The REPL uses JLine for both normal prompts and approval prompts. In redirected
stdin mode on Windows, CRLF/scripted input can produce extra blank prompt turns
and approval-answer drift. This is separate from model behavior and separate
from approval safety: the write was denied, but the transcript alignment is not
clean enough for reliable scripted manual verification.

## Goal

Make non-interactive/scripted REPL runs consume prompt lines and approval
responses deterministically, without echo drift, blank prompt turns, or approval
answers leaking into the next user turn.

## Scope

### In scope

- Detect scripted stdin reliably for installed/manual verification.
- Use a non-JLine or JLine-safe input path for scripted REPL mode.
- Keep approval prompts visible and approval responses consumed exactly once.
- Preserve interactive JLine behavior for normal human sessions.
- Add focused tests for scripted prompt + approval sequencing.

### Out of scope

- Changing approval policy semantics.
- Weakening approval gates.
- Building a full TUI.
- Replacing JLine for normal interactive sessions.

## Proposed Work

1. Add a small REPL input abstraction around line reading:
   - interactive JLine reader for normal sessions,
   - scripted reader for redirected stdin.
2. Ensure `CliApprovalGate` can share the same scripted reader without a second
   `Scanner` or second buffering layer.
3. Normalize CRLF/LF handling so each submitted prompt is consumed once.
4. Suppress scripted input echo/control characters in captured evidence.
5. Add tests that feed:
   - `/debug trace`
   - mutation request
   - `n`
   - `/exit`
   and assert `n` is consumed as approval, not as a later user turn.

## Likely Files / Areas

- `src/main/java/dev/talos/cli/launcher/RunCmd.java`
- `src/main/java/dev/talos/cli/repl/TalosBootstrap.java`
- `src/main/java/dev/talos/runtime/CliApprovalGate.java`
- `src/test/java/dev/talos/cli/launcher/`
- `src/test/java/dev/talos/runtime/`

## Test / Verification Plan

Focused tests:

```powershell
./gradlew.bat test --tests "dev.talos.cli.launcher.*"
./gradlew.bat test --tests "dev.talos.runtime.CliApprovalGateTest"
```

Widen:

```powershell
./gradlew.bat test
./gradlew.bat e2eTest
./gradlew.bat check
```

Installed verification:

- Rebuild and install Talos.
- Run the standard horror-synth manual prompt sequence with redirected stdin.
- Confirm:
  - no raw runtime logs,
  - approval prompt is visible,
  - `n` denies exactly once,
  - `n` is not handled as a later user prompt,
  - playground files remain unchanged.

## Acceptance Criteria

- Scripted manual runs consume approval responses exactly once.
- No extra blank user turns are created by CRLF handling.
- Interactive REPL behavior remains unchanged.
- Approval denial remains fail-closed and truthful.

## Completion Notes

- Added a shared REPL input owner for interactive and scripted sessions.
- Interactive sessions keep JLine and slash completion; approval prompts use
  the same JLine-backed reader.
- Scripted/redirected sessions use a plain buffered reader shared by normal
  prompts and approval prompts.
- `TalosBootstrap` now accepts an explicit approval prompt reader, so scripted
  mode does not fall back to a second `Scanner(System.in)` buffering layer.
- Installed manual verification in `local/playground/horror-synth-site`
  confirmed:
  - approval prompt is visible,
  - `n` denies exactly once,
  - `n` is not handled as a later user turn,
  - no playground file changed,
  - no raw runtime log/control-sequence noise returned.

Verification completed:

```powershell
./gradlew.bat test --tests "dev.talos.cli.launcher.*" --tests "dev.talos.cli.repl.TalosBootstrapWiringTest" --tests "dev.talos.runtime.CliApprovalGateTest"
./gradlew.bat test
./gradlew.bat e2eTest
./gradlew.bat check
```
