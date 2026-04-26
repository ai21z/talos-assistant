# 30. CLI UI Output Architecture Audit

Date: 2026-04-26
Status: Ticket 1 audit note
Branch: ticket/talos-cli-ui-audit-architecture-note

## Purpose

This note audits Talos' current CLI output architecture before the beta CLI
redesign work begins. It is intentionally not a large implementation patch.
The goal is to identify where output is produced today, which boundaries are
already good enough to extend, where debug/internal output leaks into the user
path, and which implementation tickets can move the CLI toward a calmer,
trustworthy, line-based interface without destabilizing `v0.9.0-beta-dev`.

## Sources Read

Internal architecture and process sources:

- `local/tickets/new-work.md`
- `docs/new-architecture/talos-harness-source-of-truth.md`
- `docs/new-architecture/talos-harness-plan.md`
- `local/docs/talos-source-pack-safe-local-alternative-2026-04-19.md`
- `docs/work-test-cycle.md`
- `docs/work-test-cycle-step-by-step.md`
- `.github/assistant-instructions.md`
- `docs/new-architecture/29-v1-scenario-pack.md`

Current CLI/runtime source areas:

- `src/main/java/dev/talos/app/Main.java`
- `src/main/java/dev/talos/app/ui/TerminalFirstRun.java`
- `src/main/java/dev/talos/cli/launcher/*`
- `src/main/java/dev/talos/cli/repl/*`
- `src/main/java/dev/talos/cli/repl/slash/*`
- `src/main/java/dev/talos/cli/ui/*`
- `src/main/java/dev/talos/cli/modes/*`
- `src/main/java/dev/talos/runtime/CliApprovalGate.java`
- `src/main/java/dev/talos/core/rag/RagService.java`
- `src/main/resources/config/default-config.yaml`
- `src/main/resources/config/logback.xml`

Reference material checked for transferable discipline only:

- `.external assistant/openclaw/AGENTS.md`
- `.external assistant/openclaw/src/terminal/palette.ts`
- `.external assistant/openclaw/src/terminal/theme.ts`
- `.external assistant/openclaw/src/terminal/ansi.ts`
- `.external assistant/openclaw/src/terminal/safe-text.ts`
- `.external assistant/openclaw/src/terminal/table.ts`
- `.external assistant/openclaw/docs.acp.md`

The MEAP agent book remains useful only for conceptual vocabulary such as
tool-call/result abstractions and trajectory capture. It should not decide
Talos production CLI policy.

## Executive Verdict

Talos already has the beginning of a real CLI architecture. The REPL path has
a `Result` model and a `RenderEngine` that sanitizes and redacts model-facing
text before display. That is the right direction.

The gap is not lack of styling. The gap is that the output contract is only
partly enforced. Several important output paths bypass the renderer, debug and
status concepts are still binary or ad hoc, colors are global constants rather
than semantic theme tokens, and some core services still write directly to
`System.out` or `System.err`.

For beta, the right move is not a full-screen TUI or a broad rewrite. The
right move is a line-based output discipline:

```text
command / mode / runtime / tool
    -> structured Result or UI event
    -> presentation normalization
    -> trusted renderer
    -> semantic theme
    -> terminal capability policy
```

Manual installed-CLI verification for this audit used a non-mutating sequence:
`/help`, `/status`, `/exit`. The transcript confirmed the audit findings:
normal output currently includes console log lines, default `/help` is too
large for the normal path, and the dumb/non-interactive terminal path can still
show Unicode-heavy rendering poorly in captured output.

## What Is Already Strong

`RenderEngine` is a real boundary for REPL answers and command results.

- It receives `Result` values instead of raw strings for most slash command and
  prompt paths.
- It applies `Sanitize.sanitizeForOutput(...)` before user/model text reaches
  the terminal.
- It redacts untrusted text through `Redactor`.
- It suppresses spinner/progress output in non-interactive output.
- It separates normal answers, info, errors, tables, streaming lifecycle, and
  tool progress at least minimally.

`Result` is useful but still too coarse.

- Current variants: `Ok`, `Info`, `TrustedInfo`, `Error`, `Table`,
  `StreamStart`, `StreamChunk`, `StreamEnd`, `Streamed`, `ToolProgress`.
- This is enough for today's REPL, but not enough for first-class events such
  as approval requested, policy blocked, sources selected, verification failed,
  or trace available.

`TalosBootstrap` is a good composition root.

- It wires tools, modes, session memory, approval, progress sink, streaming
  filtering, and the renderer in one place.
- Tool progress already flows through `ToolProgressSink` into `RenderEngine`.
- Streaming output is routed through JLine's terminal writer when available,
  which protects prompt redraw behavior on Windows.

The V1 scenario harness and work-test-cycle provide the right testing culture.
The CLI redesign should add focused unit/snapshot tests first, then only widen
to manual installed-CLI runs when a ticket changes runtime interaction.

## Current Output Architecture

### Process Entry

`Main` logs a startup line with build identity:

- `src/main/java/dev/talos/app/Main.java`
- Uses SLF4J/logback.
- Runs `TerminalFirstRun` when no args and first-run setup is needed.
- Dispatches Picocli through `RootCmd`.

Risk: logback currently has a console appender. User-facing CLI and diagnostic
logs are not clearly separated at process level yet.

### Launcher Commands

Top-level Picocli commands mostly print directly.

- `RunCmd`
- `RagIndexCmd`
- `RagAskCmd`
- `TopLevelStatusCmd`
- `NetCmd`
- `SetupCmd`
- `DiagnoseCmd`
- `PromptRenderCmd`
- `VersionCmd`

This is acceptable for old thin commands, but it is not the target architecture.
These commands do not share one output policy, one theme, or one stdout/stderr
contract.

Important examples:

- `RunCmd` prints banner, startup notice, rate-limit messages, unknown-command
  messages, fallback messages, goodbye, and fatal errors directly.
- `RagAskCmd` prints status, answer, sources, and timing directly.
- `TopLevelStatusCmd` duplicates status rendering outside the REPL status
  command.
- `RagIndexCmd` has JSON output, which must stay machine-readable and free of
  decorative output.
- `PromptRenderCmd` is intentionally a diagnostic command and should remain
  explicit, but its output should still obey color/plain and stream policy.

### REPL Dispatch

`ReplRouter` is thin and mostly correct.

- Slash commands are routed through `CommandRegistry`.
- Non-command prompts go through `TurnProcessor`.
- `ExecutionPipeline` wraps execution, classifies errors, redacts error
  messages, and returns `Result`.
- `RenderEngine` owns display for those `Result` values.

Current user-visible extras:

- Auto route hint: `[auto -> unified]` style status.
- Spinner: governed by `ui.show_status_during_answer`.
- Post-turn stats: governed by `ui.show_timing_after_answer`.

These are useful, but they need clearer debug/normal layering because normal
mode should show outcomes and compact state, not incidental internals.

### Render Engine

`RenderEngine` is the main trusted renderer.

Good:

- Sanitizes untrusted text.
- Redacts untrusted text.
- Suppresses spinner and route hints in non-interactive mode.
- Provides a single place for answer borders, errors, tables, stream suffixes,
  and tool progress.

Weak:

- Uses direct `AnsiColor` constants rather than semantic theme tokens.
- Has hardcoded answer border/color choices.
- Has only simple table rendering and simple string-width assumptions.
- Does not own launcher command output.
- Does not own approval prompt output.
- Does not own lazy indexing progress output.
- `TrustedInfo` bypasses redaction. That is valid for known local command
  output, but it should remain narrow and documented.

### Slash Commands

Most slash commands return `Result` and are renderer-owned. This is good.

Notable commands:

- `HelpCommand` already groups commands, but default help is still closer to a
  full command wall than a layered beta help surface.
- `StatusCommand` has useful concise/verbose split, including XML compatibility
  telemetry in verbose mode.
- `ExplainLastTurnCommand` already points toward last-run introspection.
- `DebugCommand` is binary on/off only. There is no `brief`, `rag`, `tools`, or
  `trace` level yet.
- `ReindexCommand` prints progress directly to `System.out`.
- `SecretCommand` prints prompts directly.

The slash command model is a good extension point. The next help/debug work
should extend it rather than replace it.

### Assistant Modes

`UnifiedAssistantMode`, `RagMode`, `AskMode`, and `DevMode` return `Result`.
The assistant modes generally do not print directly.

Important distinction:

- `RagMode` captures retrieval trace through `TurnTraceCapture`.
- `UnifiedAssistantMode` encourages tool-based retrieval instead of pre-packed
  RAG snippets.
- `AssistantTurnExecutor` already centralizes many truth-shaping decisions.

The CLI should expose the results of these runtime concepts as compact phase,
tool, source, approval, verification, and outcome events. It should not add
more scattered string patches to assistant answer text.

### Tool Progress

Current path:

```text
ToolCallExecutionStage
    -> ToolProgressSink
    -> RenderEngine.printToolProgress(...)
```

This is a good early event path. It is not yet a full UI event architecture.
The event payload is only `(toolName, action, detail)`, and the action strings
are ad hoc.

Expected future shape:

```text
ToolRequested / ToolRunning / ToolSucceeded / ToolFailed
ApprovalRequested / ApprovalGranted / ApprovalDenied
PolicyBlocked
TaskCompleted / TaskFailed
TraceAvailable
```

Do not implement all of this in one ticket. Evolve `Result.ToolProgress` and
runtime audit objects only when a focused ticket needs the new fact.

### Approval UI

`CliApprovalGate` prints directly to its `PrintStream`.

Current display:

```text
Approval required: <description>
  <detail>
Allow? [y=yes, a=yes for session, N=no]
```

Good:

- Uses the same JLine reader when available.
- Stops the spinner before prompting.
- Supports yes, yes-for-session, and denial.
- EOF and Ctrl+C fail closed.

Weak:

- Not renderer-owned.
- Not themed centrally.
- Does not show a structured risk level.
- Does not distinguish policy-blocked from user-denied in the UI layer.
- Does not produce a display event that can be replayed or tested as part of
  last-run introspection.

### RAG and Indexing Output

`RagService.ensureIndexExists(...)` prints directly from the core layer:

- `System.out.print("\rIndexing workspace (first RAG query)... ")`
- `System.out.println()`
- `System.err.println("\rIndexing failed: ...")`

This is the clearest layering violation in the current output architecture.
Core retrieval should not own terminal output. It should report a status event
or return a structured indexing result for the caller to render.

This should be a dedicated ticket because it crosses core/service boundaries.

### Color and Terminal Capability

Current implementation:

- `AnsiColor` owns global static ANSI constants and wrappers.
- It respects `NO_COLOR`.
- It supports `TALOS_COLOR=true|false`.
- It disables color when `System.console() == null`.
- It checks common terminal indicators such as `WT_SESSION`, `COLORTERM`,
  `TERM_PROGRAM`, and `TERM` containing color/xterm/256.
- It has Unicode detection and ASCII fallbacks in some render paths.

Gaps:

- No explicit `--color=auto|always|never`.
- No global `--no-color`.
- No `TERM=dumb` hard block documented in tests.
- No central terminal capability object passed to renderers.
- Static initialization makes environment-driven tests weak.
- Colors are named by hue, not by semantic role or Talos brand token.

Target token mapping for beta should be semantic:

```text
brand / section        bronze
active / selected      aquamarine
success / verified     pistachio
debug / trace / memory eggplant
error / blocked        pomegranate
warning / approval     bronze or amber
metadata               muted gray
body                   off-white
```

Do not scatter those hex/ANSI codes through commands. Add a central theme/token
adapter first, then migrate renderers gradually.

### Logs and Debug

Current state:

- `logback.xml` sends logs to a console appender.
- `dev.talos` logger is INFO, root is WARN.
- Many runtime internals use `LOG.debug`, which is good.
- `/debug` toggles only the REPL session flag; it does not currently provide a
  layered output model for `brief`, `rag`, `tools`, or `trace`.
- Diagnostic commands such as `/route`, `/prompt`, `/explain-last-turn`, and
  `diagnose` already exist, but they are not organized under one debug UX.

This is better than dumping everything into normal answers, but not yet a
reference-grade debug interface.

## Current UI/UX Pain Points

1. Normal startup is still presentation-heavy. The full logo and context block
   are useful in a demo but too large for repeated beta use.

2. Output ownership is inconsistent. REPL command results are renderer-owned;
   top-level commands, approval prompts, setup, indexing, first-run setup, and
   some core services print directly.

3. Debug has no layered model. There are diagnostic commands, but no coherent
   `off / brief / rag / tools / trace` policy.

4. Color is centralized technically but not semantically. `AnsiColor` is a
   useful utility, not yet a theme system.

5. Help is grouped but not layered. Default help should become shorter and
   task-oriented, with explicit `all`, `debug`, `security`, and `rag` detail.

6. Approval output is safe but plain. It needs clearer action, target, reason,
   risk, and result display without weakening the approval gate.

7. Core RAG indexing writes to terminal streams. This makes normal output,
   tests, and future JSON/script modes harder to trust.

8. Top-level and REPL status output duplicate concepts. The CLI needs one
   status/dashboard presentation model reused across entry points.

9. Model output sanitization is good in `RenderEngine`, but direct streaming
   and suffix paths must keep the invariant: model text is sanitized before any
   trusted renderer styling is applied.

10. Machine-readable commands need an explicit stdout/stderr contract before
    UI polish expands. JSON stdout must stay clean.

## Comparison Against Reference Patterns

OpenClaw is not a product direction for Talos. It is multi-channel and
platform-like; Talos is a local Java workspace operator. The transferable
patterns are narrower:

- Shared CLI palette, not scattered hardcoded colors.
- ANSI-safe text utilities and table wrapping.
- Verbose/debug material routed away from normal stdout when appropriate.
- Status surfaces that split quick health from deeper diagnostic probes.
- Tests around terminal rendering and sanitization.

These patterns support the Talos direction, but Talos should keep a smaller
line-based interface. No full-screen TUI, no channel platform, no multi-agent
presentation.

The agent-book concepts also support the target shape: a turn should have a
trajectory of tool calls, observations, approvals, and outcomes. In Talos, that
trajectory should surface through structured runtime results and audit records,
not through model-written terminal styling or chatty debug prose.

## Target Architecture

The target architecture should be introduced incrementally:

```text
Picocli command / REPL command / assistant mode / runtime tool loop
    -> Result or CliEvent
    -> CliPresentationModel
    -> RenderEngine
    -> CliTheme
    -> TerminalCapabilities
```

Important constraints:

- The model never controls terminal styling.
- Untrusted text is sanitized before rendering.
- Trusted renderer code applies style after sanitization.
- Normal mode shows compact outcome and next action.
- Debug details are available on demand.
- Color is optional and centrally controlled.
- Non-TTY/script output stays clean.
- Approval/security output remains explicit and fail-closed.

The first implementation slices should extend current seams:

- Keep `Result` and `RenderEngine`.
- Add theme/capability policy around `AnsiColor`.
- Add small result/event variants only when a ticket needs them.
- Move direct output producers behind renderer or local presenters gradually.

## Proposed Ticket Sequence

### Ticket 2: Theme and Color Capability Foundation

Goal:

- Add a central theme/token layer and explicit terminal color policy.
- Preserve current sanitization and redaction behavior.
- Support `NO_COLOR`, `TERM=dumb`, non-TTY, `--no-color`, and
  `--color=auto|always|never` if the current Picocli parser can accept it
  cleanly.

Likely files:

- `src/main/java/dev/talos/cli/ui/AnsiColor.java`
- new `src/main/java/dev/talos/cli/ui/CliTheme.java`
- new `src/main/java/dev/talos/cli/ui/TerminalCapabilities.java`
- new `src/main/java/dev/talos/cli/ui/ColorPolicy.java`
- `src/main/java/dev/talos/cli/launcher/RootCmd.java`
- `src/main/java/dev/talos/cli/launcher/RunCmd.java`
- `src/main/java/dev/talos/cli/repl/RenderEngine.java`
- `src/test/java/dev/talos/cli/ui/AnsiColorTest.java`
- new theme/capability tests

Acceptance:

- Renderer styling still happens after sanitization.
- Existing sanitize tests continue to pass.
- NO_COLOR and TERM=dumb paths produce no ANSI.
- Non-interactive/piped output remains plain.
- No broad UI redesign yet.

### Ticket 3: Clean Startup and Status Dashboard

Goal:

- Replace noisy repeated startup with a compact beta dashboard.
- Reuse one status presentation model for `run` startup and `/status`.

Show:

- app/version/build
- workspace
- mode
- model
- index state
- local/network policy state
- debug state
- one next useful command

Likely files:

- `src/main/java/dev/talos/cli/ui/TalosBanner.java`
- `src/main/java/dev/talos/cli/repl/slash/StatusCommand.java`
- `src/main/java/dev/talos/cli/launcher/TopLevelStatusCmd.java`
- `src/test/java/dev/talos/cli/ui/TalosBannerTest.java`
- `src/test/java/dev/talos/cli/repl/slash/InfraCommandsTest.java`

Acceptance:

- Startup is calm in normal mode.
- Full details still available via verbose status.
- No direct exposure of raw debug internals in normal startup.

### Ticket 4: Layered Help

Goal:

- Make default `/help` short and practical.
- Add `/help all`, `/help debug`, `/help security`, and `/help rag` or an
  equivalent compatible syntax.

Likely files:

- `src/main/java/dev/talos/cli/repl/slash/HelpCommand.java`
- `src/main/java/dev/talos/cli/repl/slash/CommandGroup.java`
- `src/test/java/dev/talos/cli/repl/slash/SimpleCommandsTest.java`

Acceptance:

- Default help is not a wall.
- Full command inventory remains available.
- Debug/security/RAG help has clear focused sections.

### Ticket 5: Debug and Trace Layering

Goal:

- Replace or extend binary `/debug on|off` toward levels:
  `off`, `brief`, `rag`, `tools`, `trace`.
- Keep backward compatibility for `/debug on` and `/debug off`.

Likely files:

- `src/main/java/dev/talos/cli/repl/SessionState.java`
- `src/main/java/dev/talos/cli/launcher/RunCmd.java`
- `src/main/java/dev/talos/cli/repl/slash/DebugCommand.java`
- `src/main/java/dev/talos/cli/repl/slash/ExplainLastTurnCommand.java`
- `src/main/java/dev/talos/runtime/TurnRecord.java`
- related tests

Acceptance:

- Normal mode stays quiet.
- Developers can inspect RAG/tool/trace details without reading raw logs.
- Existing `/debug on|off` tests remain compatible or are intentionally
  updated.

### Ticket 6: Role and Result Rendering Cleanup

Goal:

- Make user, Talos, tool, sources, warning, error, and trace sections
  structurally distinct while keeping normal answer output compact.

Likely files:

- `src/main/java/dev/talos/cli/repl/Result.java`
- `src/main/java/dev/talos/cli/repl/RenderEngine.java`
- `src/main/java/dev/talos/cli/modes/RagMode.java`
- `src/main/java/dev/talos/cli/modes/UnifiedAssistantMode.java`
- render tests

Acceptance:

- Normal answers remain readable.
- Sources are compact and easy to scan.
- Tool/status/debug lines do not look like assistant prose.

### Ticket 7: Approval and Security UI Polish

Goal:

- Render risky actions with action, target, reason, risk level, and choices.
- Preserve current fail-closed behavior.

Likely files:

- `src/main/java/dev/talos/runtime/CliApprovalGate.java`
- `src/main/java/dev/talos/runtime/TurnProcessor.java`
- `src/main/java/dev/talos/tools/ToolDescriptor.java`
- `src/test/java/dev/talos/runtime/CliApprovalGateTest.java`
- approval scenario tests

Acceptance:

- Approval denied, policy blocked, and approved-for-session are clear.
- No safety checks are weakened.
- Non-interactive/EOF behavior still denies.

### Ticket 8: Core Output Boundary Cleanup

Goal:

- Remove direct terminal writes from `RagService.ensureIndexExists(...)` and
  similar core services.

Likely files:

- `src/main/java/dev/talos/core/rag/RagService.java`
- `src/main/java/dev/talos/cli/modes/RagMode.java`
- `src/main/java/dev/talos/cli/launcher/RagAskCmd.java`
- `src/main/java/dev/talos/cli/repl/Result.java`
- tests around lazy indexing and RAG output

Acceptance:

- Core retrieval does not print to stdout/stderr.
- Lazy indexing state is still visible through renderer-owned status.
- JSON/script output stays clean.

### Ticket 9: Last-Run and Log Access

Goal:

- Build on `/explain-last-turn` with practical aliases such as `/last`,
  `/last sources`, `/last trace`, and `/logs` if they fit cleanly.

Likely files:

- `src/main/java/dev/talos/cli/repl/slash/ExplainLastTurnCommand.java`
- new command classes if needed
- `src/main/java/dev/talos/runtime/TurnRecord.java`
- tests

Acceptance:

- User can inspect why a turn behaved a certain way without reading raw logs.
- Sensitive data remains redacted.
- Output is compact by default with deeper detail on demand.

## Recommended First Implementation Slice

Start with Ticket 2: theme and color capability foundation.

Reason:

- It is architectural, not cosmetic.
- It protects all later UI work from hardcoded styling.
- It can be tested without live model calls.
- It reduces risk before startup/help/result rendering changes.

Keep it narrow:

- Add terminal capability and color policy classes.
- Add semantic theme tokens mapped to existing ANSI codes.
- Keep `AnsiColor` backward-compatible for current callers.
- Add tests for color disabled paths and policy decisions.
- Do not redesign help, startup, approval, or result rendering in this slice.

## Risks

- Static environment detection in `AnsiColor` makes policy tests weaker than
  they should be. New capability code should be injectable for tests.
- Changing prompt/banner styling can break snapshot-like tests or manual
  transcript expectations.
- Moving top-level commands into a renderer may accidentally pollute JSON
  stdout if not done carefully.
- Approval UI changes are high-trust and should be isolated in their own
  branch.
- Lazy indexing output cleanup crosses the CLI/core boundary and should not be
  bundled with theme work.
- Unicode/ANSI width handling is currently simple. Better wrapping should be
  tested before widening it.

## Test Plan

Ticket-specific tests should come first:

```powershell
./gradlew.bat test --tests "dev.talos.cli.ui.AnsiColorTest"
./gradlew.bat test --tests "dev.talos.cli.repl.RenderEngineSanitizeTest"
./gradlew.bat test --tests "dev.talos.cli.repl.RenderEngineTest"
./gradlew.bat test --tests "dev.talos.cli.repl.slash.SimpleCommandsTest"
./gradlew.bat test --tests "dev.talos.cli.repl.slash.InfraCommandsTest"
```

Widen when a ticket changes runtime interaction:

```powershell
./gradlew.bat test
./gradlew.bat e2eTest
```

Manual installed-CLI verification is required after any ticket that changes
startup, prompt, help, approval, debug, streaming, or normal answer rendering.

Manual review should check:

- no ANSI/control characters from model output survive sanitization
- no model-controlled terminal styling
- no raw debug logs in normal output
- NO_COLOR/no-color paths are plain
- JSON/machine-readable commands keep clean stdout
- approval prompts remain clear and fail closed
- `/status` and `/help` remain useful in normal mode
- `/explain-last-turn` or successor commands expose deeper trace facts on demand

## Decision

Proceed with the CLI redesign as a sequence of small architecture tickets.
Do not start with visual polish. Establish theme/capability policy first, then
use it to calm startup, layer help, separate debug, and polish approval/result
rendering.
