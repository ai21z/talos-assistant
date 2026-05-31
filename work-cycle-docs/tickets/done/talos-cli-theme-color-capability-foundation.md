# [done] Ticket: CLI Theme and Color Capability Foundation
Date: 2026-04-26
Priority: high
Status: done
Architecture references:
- docs/architecture/30-cli-ui-output-architecture-audit.md
- work-cycle-docs/tickets/new-work.md
- docs/architecture/talos-harness-source-of-truth.md
- docs/architecture/talos-harness-plan.md
- local/docs/talos-source-pack-safe-local-alternative-2026-04-19.md

## Why This Ticket Exists

Talos' CLI needs semantic, optional, centrally controlled styling before the
startup dashboard, layered help, debug, and approval UI are redesigned.

## Problem

`AnsiColor` centralizes ANSI constants, but styling is still hue-based and
static. There is no first-class color policy object, no semantic Talos theme,
no explicit `--color=auto|always|never` or global `--no-color`, and tests cannot
easily exercise environment/capability decisions because detection happens at
class load.

## Goal

Introduce a beta-safe theme and terminal capability foundation while keeping
existing renderer behavior compatible.

## Scope

In scope:
- central color policy model
- terminal capability detection for color and Unicode
- semantic CLI theme tokens
- `NO_COLOR` and `TERM=dumb` behavior covered by tests
- preserve existing `AnsiColor` callers through compatibility wrappers

Out of scope:
- startup dashboard redesign
- help redesign
- approval prompt redesign
- large result/event model expansion
- core RAG output cleanup

## Proposed Work

Add small, injectable classes under `dev.talos.cli.ui`, likely:

- `ColorPolicy`
- `TerminalCapabilities`
- `CliTheme`

Then adapt `AnsiColor` to delegate to or coexist with the new policy without
breaking existing callers.

If Picocli integration is narrow enough, add compatible global options:

- `--no-color`
- `--color=auto|always|never`

Do not force these if the parser change becomes too broad for this ticket.

## Likely Files / Areas

- `src/main/java/dev/talos/cli/ui/AnsiColor.java`
- `src/main/java/dev/talos/cli/ui/ColorPolicy.java`
- `src/main/java/dev/talos/cli/ui/TerminalCapabilities.java`
- `src/main/java/dev/talos/cli/ui/CliTheme.java`
- `src/main/java/dev/talos/cli/launcher/RootCmd.java`
- `src/main/java/dev/talos/cli/launcher/RunCmd.java`
- `src/main/java/dev/talos/cli/repl/RenderEngine.java`
- `src/test/java/dev/talos/cli/ui/AnsiColorTest.java`
- new `src/test/java/dev/talos/cli/ui/*` tests

## Test / Verification Plan

Focused:

```powershell
./gradlew.bat test --tests "dev.talos.cli.ui.AnsiColorTest"
./gradlew.bat test --tests "dev.talos.cli.repl.RenderEngineSanitizeTest"
./gradlew.bat test --tests "dev.talos.cli.repl.RenderEngineTest"
```

Then widen if launcher/parser behavior changes:

```powershell
./gradlew.bat test --tests "dev.talos.cli.repl.slash.SimpleCommandsTest"
./gradlew.bat test --tests "dev.talos.cli.repl.slash.InfraCommandsTest"
./gradlew.bat test
```

Installed CLI verification is required if startup, prompt rendering, or global
CLI options change.

## Acceptance Criteria

- semantic theme/capability classes exist
- `NO_COLOR` disables renderer ANSI output
- `TERM=dumb` disables renderer ANSI output
- non-interactive output remains plain
- model output sanitization tests still pass
- current callers of `AnsiColor` remain compatible
- no startup/help/approval redesign is included in this branch
