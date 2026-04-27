# [done] Ticket: Normal CLI Output Must Not Leak Runtime Logs
Date: 2026-04-26
Priority: medium
Status: done
Architecture references:
- `docs/new-architecture/30-cli-ui-output-architecture-audit.md`
- `work-cycle-docs/tickets/done/talos-cli-ui-audit-and-architecture-note.md`
- `work-cycle-docs/tickets/done/talos-embedding-nan-retrieval-diagnostic.md`
- `work-cycle-docs/work-test-cycle.md`
- `work-cycle-docs/work-test-cycle-step-by-step.md`

## Why This Ticket Exists

Installed CLI verification still shows runtime/process logs mixed into the
normal user transcript.

Observed in `local/manual-testing/test-output` on 2026-04-26:

```text
WARNING: Using incubator modules: jdk.incubator.vector
WARNING: Unable to create a system terminal, creating a dumb terminal
WARN dev.talos.core.rag.RagService - Embedding failed, proceeding BM25-only...
```

The embedding NaN diagnostic ticket correctly identified and documented the
local Ollama/model failure. The remaining product issue is output ownership:
normal Talos conversations should be rendered through the CLI output layer, not
interleaved with raw JVM/JLine/SLF4J diagnostic lines.

## Problem

Talos currently has multiple output channels:

- renderer-owned REPL results
- approval prompt output
- process/JVM warnings
- JLine terminal warnings
- SLF4J/logback console warnings
- core-service warnings such as `RagService` embedding fallback

The renderer path is now much calmer, but raw process/log output can still
appear in captured transcripts. This weakens the CLI trust model and makes
manual evidence harder to review.

## Goal

Keep normal CLI output user-facing and renderer-owned. Preserve diagnostics,
but route them to debug/trace surfaces or log files instead of dumping raw
runtime logs into normal conversation output.

## Scope

### In scope

- Audit current log/output sources that bypass `RenderEngine`.
- Decide which diagnostics should be:
  - structured user-facing warnings,
  - debug/trace-only details,
  - log-file-only records,
  - suppressed in non-interactive transcript mode.
- Reduce normal transcript noise from `RagService` embedding fallback.
- Investigate JLine dumb-terminal warning source in piped/manual capture.
- Decide whether the incubator vector JVM warning should remain accepted,
  be avoided for installed CLI startup, or be documented as unavoidable while
  vector acceleration is enabled.

### Out of scope

- Reworking embedding-provider architecture.
- Removing safety/error visibility.
- Hiding actionable failures.
- Broad CLI redesign beyond log/output boundary cleanup.

## Proposed Work

1. Review `src/main/resources/logback.xml` and launcher startup behavior.
2. Prefer file/debug logging for SLF4J diagnostics in normal interactive mode,
   while keeping explicit user-facing errors rendered through `Result`.
3. Demote recoverable `RagService` embedding fallback from raw `LOG.warn`
   in normal output to structured `Prepared.errorReason()` / trace-visible
   evidence, or keep the warning only in a log file.
4. Check whether non-interactive `talos run --no-logo --root ...` can avoid
   constructing a JLine system terminal when stdin/stdout are piped.
5. Add tests that assert normal rendered output does not include raw
   `dev.talos.*` log lines for recoverable retrieval fallback.

## Likely Files / Areas

- `src/main/resources/logback.xml`
- `src/main/java/dev/talos/cli/launcher/RunCmd.java`
- `src/main/java/dev/talos/cli/repl/TalosBootstrap.java`
- `src/main/java/dev/talos/core/rag/RagService.java`
- `src/main/java/dev/talos/cli/modes/RagMode.java`
- `src/main/java/dev/talos/tools/impl/RetrieveTool.java`
- `src/test/java/dev/talos/core/rag/`
- `src/test/java/dev/talos/cli/repl/`

## Test / Verification Plan

Focused tests:

```powershell
./gradlew.bat test --tests "dev.talos.core.rag.*"
./gradlew.bat test --tests "dev.talos.cli.repl.*"
```

Widen:

```powershell
./gradlew.bat test
./gradlew.bat e2eTest
./gradlew.bat check
```

Installed verification:

- Uninstall/build/install Talos.
- Clear the horror-synth session.
- Run the standard manual prompt sequence into
  `local/manual-testing/test-output`.
- Confirm user-facing transcript contains no raw `dev.talos.*` log line in
  normal mode.
- Confirm recoverable retrieval fallback remains visible through debug/trace
  or explicit structured status when appropriate.

## Acceptance Criteria

- Normal CLI transcripts do not leak raw SLF4J `dev.talos.*` warnings for
  recoverable internal fallback paths.
- Retrieval fallback remains non-crashing and inspectable.
- Scripted/manual transcript evidence is easier to review.
- No machine-readable output path is polluted with decorative or raw log text.

## Completion Notes

- Routed SLF4J WARN diagnostics to local log files with console output limited
  to hard errors.
- Routed Java Util Logging dependency warnings, including Lucene vectorization
  warnings, to `~/.talos/logs/talos-jul.log`.
- Removed installed-launcher Vector API module flags so `talos --version` no
  longer prints the JVM incubator-module warning.
- Disabled JLine bracketed-paste escape sequences in REPL readers.
- Added a safer non-system terminal path for redirected/manual transcript runs.
- Demoted recoverable retrieval fallback logs from WARN to DEBUG.

Verification completed:

```powershell
./gradlew.bat test --tests "dev.talos.cli.launcher.RunCmdTerminalModeTest" --tests "dev.talos.cli.ui.LogbackOutputPolicyTest" --tests "dev.talos.cli.ui.ConsoleNoisePolicyTest"
./gradlew.bat test
./gradlew.bat e2eTest
./gradlew.bat check
```

Installed Talos was rebuilt and manually verified in
`local/playground/horror-synth-site`. The transcript in
`local/manual-testing/test-output` no longer contains the previous JVM
incubator startup warning, JLine bracketed-paste sequences, or raw
`dev.talos.*`/Lucene warning lines. Denied mutation left the playground clean.
