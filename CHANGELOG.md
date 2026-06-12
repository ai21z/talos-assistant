# Changelog

## [Unreleased]

### Changed
- [T788] The workspace `.talos` directory is now a protected CONTROL
  path (like `.git` and `.github/workflows`): it will hold
  workspace-declared verification profiles (`.talos/profiles.yaml`) and
  template commands (`.talos/commands/*.md`) — content that influences
  what Talos executes — so the model can no longer write it with an
  ordinary write approval; writes escalate through the protected-path
  flow and diff previews of its content fail closed. The protected-path
  `POLICY_VERSION` was bumped v3 → v4, which makes existing RAG indexes
  rebuild their privacy partition once on the next index check (stale
  partitions would misclassify `.talos` content). Names merely
  containing the word talos (`docs/talos-notes.md`) stay unprotected.
  One deliberate read-side exemption: the project-memory loader still
  reads its own canonical `<dir>/.talos/rules.md` memory tiers into the
  prompt (that is their purpose, and the CONTROL classification now
  protects them from un-escalated model WRITES — closing a
  memory-injection vector); nothing else under `.talos` is exempt, so
  `.talos/profiles.yaml` can never flow into a prompt.
- [T797] Characterization pins ahead of the context/session work (tests
  only, no behavior change): the compaction failure-breaker's exact
  operational skip string, the fact that compaction today sets a status
  and nothing else (no event, no notice — T798/T805 change that
  deliberately), and the exact `/session` info/save/load/clear/usage
  bytes that T799–T801 evolve.
- [T787] Characterization pins ahead of the wave-4 trust work (tests
  only, no behavior change): the not-yet-protected `.talos` workspace
  directory, the gradle `run_command` approval-detail bytes, the `/status`
  dashboard render bytes, `/undo`'s pre-existing approval/protected-path
  bypass, and the `/checkpoint` list shape (id-sorted, not
  chronological) plus restore approval bytes — the byte baselines the
  following tickets are reviewed against.
- [T785] First run is now an honest preflight: the flow runs the same
  doctor probe set as `talos doctor` (default probes only — it never loads
  a model) and prints per-check PASS/WARN/FAIL/SKIP lines instead of the
  previous unconditional "✓ Setup complete", which verified nothing. The
  verdict line is now truthful: "Setup verified." only when every check
  passed, "Setup complete with warnings." when the only finding is e.g. a
  managed server that has not started yet, and "Setup incomplete — N
  check(s) failed." with fix hints and a pointer to `talos doctor`
  otherwise. The stale hardcoded configuration block (which claimed model
  `talos-agent` regardless of config) is gone. The sentinel is still
  written once the flow has been shown — even on failure — because the
  launcher exits when first-run refuses, and an unconfigured user must
  never be locked out of the REPL; recurring verification belongs to
  `talos doctor`. First-run tests are now hermetic (injected doctor
  runner, output stream, and sentinel path — the old test wrote the
  developer's real `~/.talos/first_run_done`).

### Changed (checkpoints)
- [T796] `FileUndoStack` is deleted along with its write/edit tool push
  sites — its sole functional consumer was the pre-T795 ungated `/undo`,
  and checkpoints capture a strict superset (every gated mutation
  including batch/move/delete operations, durable on disk rather than
  20 in-memory entries lost at session end). One undo system remains:
  the governed one. The write/edit tools' output bytes are unchanged
  (the snapshot push was side-effect-only).
- [T795] `/undo` is re-routed through checkpoints — the headline trust
  fix of the wave. It now restores the NEWEST checkpoint behind a full
  approval whose detail shows the capture time, trigger, affected files
  (with explicit "will be DELETED" warnings for paths that did not
  exist at capture), and capped redacted diffs; a safety checkpoint of
  the current state is captured FIRST, so `/undo` is itself undoable
  (`/undo` twice = redo) and a failed safety capture aborts with zero
  changes. The previous implementation popped an in-memory per-file
  stack and wrote files directly — including protected paths like
  `.env` — with no approval gate, no checkpoint, and no protected-path
  classification, and its memory vanished at session end. Semantic
  change to be aware of: undo now operates on the last CHECKPOINTED
  mutation set (which can span multiple files for batch operations),
  not the last single write/edit; with checkpointing disabled, `/undo`
  says so explicitly instead of silently reverting from memory. The
  "Nothing to undo." empty-state wording is preserved byte-for-byte;
  restores are traced (`CHECKPOINT_RESTORED`). Checkpoint metadata also
  gained a monotonic capture `sequence` used as the createdAt tiebreak:
  two checkpoints captured within the same clock tick (exactly the
  undo-then-safety pattern) previously fell to a random UUID tiebreak,
  making "newest" a coin flip.
- [T794] `/checkpoint list` renders the unified timeline — id, local
  capture time, turn number, trigger, file count, and size, newest
  first — and a new `/checkpoint show <id>` renders per-file stats with
  capped, redacted restore diffs (captured content vs the CURRENT
  files, via a new `ApprovalDiffPreview.forRestore` that reuses every
  write/edit guard: protected paths including `.talos`, binary and
  oversized content all fail closed; entries that did not exist at
  capture are annotated "restore DELETES it"). Diff previews cap at 3
  files per show with an honest "(N more...)" marker. The
  `/checkpoint restore` approval description/detail bytes stay frozen —
  the rich previewed restore path is `/undo` (T795).
- [T793] Checkpoints gained a read model: `listSummaries`/`describe`/
  `blob` expose createdAt, turn number, a new human `trigger` (the tool
  and target that caused the capture; pre-T793 checkpoints render
  "(unknown)" — schemaVersion stays 1), file/byte counts, and manifest
  entries. `/checkpoint list` (and `listIds`) is now truly
  newest-first by `createdAt` — it was reverse-lexicographic on random
  UUIDs, i.e. arbitrary. Restores can now be traced
  (`CHECKPOINT_RESTORED`/`CHECKPOINT_RESTORE_FAILED`, counts only,
  best-effort), and a new `captureBeforeRestore` records the CURRENT
  state of the affected paths under a `restore-safety` backend before a
  restore overwrites them — the mechanism that makes `/undo` itself
  undoable in T795. Corrupt or pre-T793 metadata stays listable
  (tolerant reads).

### Changed (verification)
- [T792] A user-approved, successful, verification-class `run_command`
  (gradle_test / gradle_check / gradle_e2e_test, or any trusted `ws:`
  workspace profile) that ran AFTER the turn's last successful mutation
  now upgrades the post-apply verification verdict from READBACK_ONLY to
  PASSED ("Command verification passed: <profile> exited 0.") —
  command-level proof is strictly stronger than readback, and this is
  what makes workspace profiles useful on non-Java workspaces. The
  upgrade is additive only: FAILED is never overridden (failed runs
  already dominate the answer), runs ordered before the mutation prove
  nothing, build profiles (gradle_build/install_dist) are deliberately
  not verification-class in v1, and ambiguous outcome shapes change
  nothing (fail closed). Turns that already ran a passing gradle check
  after a mutation will now read PASSED instead of READBACK_ONLY.

### Added
- [T805] Automatic context compaction is no longer invisible. When the
  auto-compactor summarizes older exchanges mid-session, one muted line
  now renders after the turn stats — `[context compacted: 6 older
  exchanges summarized · 4 kept verbatim]` — so the user sees their
  context change shape at the moment it happens instead of discovering
  it later through degraded recall. The notice is interactive-only
  render chrome: scripted and redirected transcripts are byte-identical
  to before, it never enters any Result, and it gets a defensive
  history-stripper entry (`UiChrome.CONTEXT_COMPACTED_PREFIX`, the
  WROTE_PREFIX precedent) so a model imitating the visible line cannot
  seed history with fake compaction claims. Driven by the one-shot
  T798 compaction event, polled race-free after the turn completes.
- [T804] `/compact` compacts the conversation on demand. The forced
  path skips the pair-threshold and over-budget gates and runs even
  when the auto-compaction failure breaker is open (explicit user
  intent — a forced failure still counts toward the breaker, a forced
  success resets it). Outcomes are reported honestly: "Compacted: N
  older exchanges summarized - M kept verbatim (~before -> ~after
  tokens, est.)", "Nothing to compact" when everything already fits
  (or the conversation is empty — that fast path never touches the
  model), and a failure renders the full status/category/reason with
  the guarantee that applies: history is preserved verbatim, nothing
  is lost. Uses the same compaction-mode flag as the auto path and
  `/context`, so the budget `/compact` enforces is the budget the
  meter shows.
- [T803] `/context` shows what occupies the context window — previously
  invisible state: an estimated history meter bar against the active
  compaction budget, the configured maximum
  (`limits.llm_context_max_tokens`), the response reserve and
  structural overhead, both mode budgets (assist 55% / rag 25%, active
  one marked), exchange count, sketch size, the last prompt's pinned
  @-files, the auto-compaction rule, and the last compaction attempt's
  full status line. The engine row surfaces the silent divergence
  between `limits.llm_context_max_tokens` and
  `engines.llama_cpp.context`: a smaller engine context warns about
  overflow risk, a larger one is noted as safe-but-unused, and Ollama
  shows "managed by Ollama" (reconciling the two keys stays deferred —
  this makes the gap visible). All figures are the chars/4 estimates
  the budget logic itself uses, labeled `(est.)`. One bootstrap flag
  now feeds the compaction listener, `/context`, and the upcoming
  `/compact`, so the surfaces cannot drift apart.
- [T802] `@file` pinning in prompts (unified/auto mode). Typing
  `@src/Main.java` (or `@"path with spaces"`) in a prompt pins that
  file's content into the turn: up to 4 explicit workspace-relative
  paths per prompt — no fuzzy matching, no directory walks — at 4,000
  chars per file and 12,000 total, with visible truncation markers. The
  content rides as ONE user-role `[PinnedFiles]` message injected
  immediately before the user's line, framed as untrusted reference
  data (ProjectMemory pattern); it never reaches task classification
  and never gains system authority, and the `@token` stays visible in
  the user's own words. Everything skipped says so before the spinner
  starts: missing files, directories, paths outside the workspace,
  files over 2 MiB, and binary content all produce one-line notices —
  and protected paths (`.env`, `.talos/**`, `.git/**`, keys) are
  refused with a pointer at the approval-gated `read_file` flow, with
  the identical refusal whether or not the file exists, so pinning can
  neither leak protected content nor probe for its existence. RAG mode
  (`/mode rag`) keeps its existing implicit pinning unchanged; e-mail
  addresses and mid-word `@` are never treated as pins.
- [T801] `/session export [id-prefix] [path] [--raw]` writes a markdown
  transcript of a stored session — header (id, workspace, created,
  model, exchanges, sketch) plus `## Turn N` blocks — from the snapshot
  when one exists, else from the crash log's completed-ok rows (aborted/
  error-turn residue never leaves the machine as transcript). Content
  was already redacted when it was written to the session store; the
  assembled document gets one more idempotent redaction pass, and a
  seeded placeholder is pinned to survive export verbatim. The default
  target is `~/.talos/exports/talos-session-<id>-<timestamp>.md` — the
  user's own home, never the workspace unless an explicit path says so
  — and explicit paths are never overwritten. No approval is asked
  (PromptCommand precedent: a user-initiated write of the user's own
  data to the user's own directory); the absolute path is reported.
  `--raw` copies the per-turn JSONL beside the markdown.
- [T800] `/session list` and `/session resume [id]`. `list` renders the
  workspace's stored sessions newest-first — display id (the UTC
  timestamp suffix; the one possible legacy file shows its short hash),
  age, exchange count, model, and `(current)`/`(legacy)`/`(crash log
  only)` markers. `resume` restores the latest OTHER session by default
  ("pick up where the previous session left off"); an id prefix selects
  a specific one (matched against the display id, ambiguous prefixes
  list the candidates) and may explicitly target the current session as
  a reload-from-disk. `/session load` is now an alias of `resume` — its
  former meaning ("re-read this workspace's single file") stopped
  existing when T799 introduced per-run instance files. `save`, `clear`,
  and the info block's `Saved file` row now operate on the ACTIVE
  session's instance id (save→quit no longer leaves two files for one
  session), `/session info` gained a `Session:` row showing the active
  instance id, and the usage line is now
  `/session [info|list|resume|save|load|clear|export]` (`export` lands
  in T801). Restored-session bytes ("Session restored: N exchanges
  (saved X ago).") and the no-saved-session/save/clear strings are
  unchanged.
- [T799] One workspace can now hold many sessions. Each REPL run
  persists under its own session instance id
  (`<workspace-hash>-<UTC timestamp>`) instead of overwriting the
  workspace's single bare-hash file: the close snapshot, the per-turn
  crash log, and turn traces all key on the instance id, and `/last`
  reads the active session's log through an injected id rather than
  re-deriving the workspace hash (which would have silently read the
  wrong log). Startup auto-restore and the "saved session found" notice
  pick the NEWEST stored session across legacy and instance files —
  legacy bare-hash files remain loadable and win when they are all that
  exists. Empty sessions (no turns, no sketch, no active task context)
  are no longer saved on close, so quitting an idle REPL leaves no
  file behind. `SessionStore` gains `listSessions(workspaceId)`
  (newest-first summaries covering snapshots, crash logs, and corrupt
  files, which list with epoch timestamps instead of hiding). The
  workspace hash itself is unchanged and still keys checkpoints and
  trace metadata. The `/session` command catches up in T800.
- [T798] Core context-meter and manual-compaction machinery (consumed by
  the `/context`, `/compact`, and compaction-notice tickets):
  `ConversationManager.meter(assistMode)` snapshots history-token
  estimates, the active mode's budget and pair threshold, sketch state,
  and the last compaction status; `compactNow` forces a compaction that
  skips the pair-threshold and over-budget gates and bypasses an OPEN
  failure breaker (explicit user intent — forced failures still count
  toward it, successes reset it) while keeping the recent
  budget-fitting tail verbatim exactly like the auto path (shared body,
  proven behavior-preserving by the T797 pins); and a one-shot
  `pollCompactionEvent()` signal set only by the automatic path, the
  hook for the T805 render-side notice. No user-visible change yet.
- [T791] New `/profiles` and `/verify` commands plus a `Verify` status
  row. `/profiles list` shows the declaration state and resolved
  profiles; `/profiles trust` is the chain's explicit-consent step — it
  renders the resolved profiles (absolute executable paths) and the
  declaration's SHA-256 behind an approval, then pins those exact bytes;
  `/profiles revoke` withdraws the pin. `/verify [ws:<id>]` evaluates
  the declaration and trust live (a just-pinned profile runs
  immediately; the model-facing run_command surface still registers at
  session start), plans through the same validation pipeline, asks
  per-run approval with the standard command detail, and prints the exit
  verdict with capped output tails. `/status`, `talos status`, and the
  startup banner gain a `Verify` row (`none declared` / `N profile(s)
  (untrusted - run /profiles trust)` / `N profile(s) (trusted)` /
  `invalid: ...`) — strictly additive: a blank value renders
  byte-identically to the pre-T791 output, pinned.
- [T790] Trusted workspace profiles are now invocable through
  `talos.run_command` as `ws:<id>`: one merged `CommandProfileRegistry`
  (built at session start) is threaded through the planner, the tool,
  and the turn processor, replacing three hardcoded default-registry
  call sites. Declared profiles register ONLY when the declaration is
  content-hash trusted; an untrusted, changed, invalid, or undeclared
  state is rejected at plan time with an instructive message (review
  with `/profiles trust`) — before any approval prompt is spent, which
  is the chain's proof obligation. Workspace profiles accept no caller
  arguments (declared fixed argv only), keep the per-run approval and
  BUILD_OR_TEST risk gates, and render through the same approval-detail
  format (the gradle byte-pins are unchanged). An invalid declaration
  prints one visible startup notice; the tool descriptor now mentions
  workspace profiles to the model.
- [T789] Workspace verification-profile declaration model (inert until
  T790 registers it): `<workspace>/.talos/profiles.yaml` declares up to 8
  fixed-argv command profiles (id, executable, args, timeout_ms,
  expected_writes — approval, network, interactivity, and risk are NOT
  declarable and always pinned to the hardened values). The loader
  validates fail-closed: one bad profile rejects the whole file with one
  human-readable reason, unknown keys are rejected so typos cannot
  silently default, args are screened against shell syntax, and
  workspace-relative executables (wrappers like `./gradlew`) must exist
  inside the workspace and register as their resolved absolute path
  (displayed in every trust and approval prompt). A new content-hash
  trust store (`~/.talos/trust/workspace-profiles/`) records explicit
  user consent over the declaration's raw bytes — any byte change
  returns the workspace to untrusted and requires re-consent; corrupted
  pins fail closed.
- [T786] New `/doctor` REPL command running the same default doctor probe
  set from inside a session (DEBUG group, listed by `/help`). It
  deliberately has no `--start` equivalent: a slash command must not block
  the session on a multi-minute model load or churn the GPU
  mid-conversation — end-to-end server verification stays CLI-only
  (`talos doctor --start`).
- [T784] New `talos doctor` subcommand: a fast environment preflight that
  verifies the config loads (and the user config parses), the engine
  backend resolves and a model is configured, the managed llama.cpp server
  binary and GGUF model file exist (reusing the engine's own pre-launch
  validation through a new public `LlamaCppPreflight` facade — one source
  of truth, the manager now delegates), the server responds (a managed
  server that simply is not running is honestly a WARN, since Talos starts
  it automatically on first prompt; unreachable connect-only servers FAIL),
  and the index/home directories are writable. Exit code 0 only when no
  check fails. `--start` opt-in additionally starts the managed server and
  runs a one-word chat inside try-with-resources, so the model is always
  released again; doctor never loads a model otherwise. Output is plain
  ASCII one-line-per-probe (`PASS/WARN/FAIL/SKIP`) with fix hints on
  failures. Unlike `talos diagnose` (a retrieval/answer deep dive), doctor
  is side-effect-light and finishes in seconds.

### Fixed
- [T783] `talos.delete_path` is now documented in the README tool table —
  it was registered and approval-gated since its introduction but missing
  from the user-facing table, a claims drift on the most dangerous tool.
  The `/checkpoint` and `/undo` commands gained their missing README rows
  (wording taken from their `spec()` summaries so docs and `/help` agree).
  A new `ReadmeToolTableDriftTest` pins the tool table bidirectionally
  against the canonical descriptor catalog — names and approval columns
  both — so a registered tool can never silently vanish from the docs
  again. Ride-along: `TokenBudgetFromConfigTest` was reading the
  developer's real `~/.talos/config.yaml` (a machine-local 32k
  `llm_context_max_tokens` failed its built-in-default assertion); it now
  removes the machine overlay first, the same hermeticity fix
  `LlmClientSamplingConfigTest` received at 0.10.3.

## [0.10.4] - 2026-06-11

### Changed
- [T782] Added the inline-TUI architecture decision record
  (`docs/architecture/31-inline-tui-strategy-and-fullscreen-rejection.md`):
  full-screen TUI (Lanterna/Jexer/alternate-screen) is rejected with
  evidence — the alternate screen would destroy the plain-scrollback
  transcripts the PTY evidence chain string-matches — and the Wave 3
  standing rules (byte-frozen chrome contracts, byte-identical
  degradation, visible markdown markers, Talos-authored nanorc, single
  authoritative writer, additive status row, JLine bumps as isolated
  PTY-revalidation events) are locked in with explicit revisit criteria.
- [T781] JLine upgraded 3.26.3 → 3.30.13 as an isolated change (not 4.x:
  the JNA provider and parts of the 3.x API are removed there). 3.30.12+
  fixes the status-bar duplication on terminal resize affecting the T779
  status row. The inert `.jna(true)` builder flag (no JNA on the
  classpath — resolution always fell through to the bundled JNI
  provider) is replaced by an explicit `.provider("jni")` pin for
  deterministic provider selection on JDK 21. Found and absorbed one
  3.30 behavior change: a terminal's writer now encodes output with the
  stdout-specific `outputEncoding()` which can differ from `encoding()`;
  `TerminalOutput` now uses the writer's actual charset so non-ASCII
  chrome cannot mangle. Full check green; redirected transcript
  byte-identical to the pre-wave smoke. This bump gates on the wave-close
  fresh true-PTY cycle.
- [T780] The status row now carries live session context next to the
  spinner: routing decision, active model id, and 1-based turn number
  (`⠹ Answering…  12s · route unified · qwen2.5-coder:14b · turn 3`),
  polled per tick and truncated ANSI-aware to the terminal width. All
  values are renderer-owned; the printed route-hint and turn-stat lines
  that the evidence chain matches remain printed scrollback lines,
  byte-unchanged — the row only mirrors them. Broken suppliers degrade
  silently to a context-free row.
- [T779] The thinking spinner now renders as a JLine Status bottom row
  on capable terminals (`cli.ui.StatusRowPresenter`): the row lives in a
  managed scroll region below the output, so no raw `\r` frames ever
  interleave with streamed answers and JLine's cursor model stays
  authoritative (completing T774). Terminals without scroll-region
  capabilities (dumb, legacy consoles) keep the legacy `\r` spinner; the
  capability probe mirrors JLine's own protected check. The status
  region is closed on session shutdown so the terminal scroll area is
  restored. Content is unchanged this ticket (spinner glyph + label +
  elapsed); the row is strictly additive — route hints, turn stats, and
  approval lines remain printed scrollback lines.
- [T778] Fenced code blocks are now syntax-highlighted in capable
  interactive terminals using JLine's bundled nanorc engine with
  Talos-authored minimal syntax definitions under `/nanorc/` (java,
  python, javascript/typescript, json, yaml, bash, diff, xml, html,
  css — GNU nano's GPLv3 files are deliberately not vendored). The
  complete code line is highlighted once and cut ANSI-aware at the pane
  width so token colors survive the cut; unknown languages, missing
  definitions, and parse failures all degrade to plain text, and
  highlighting never alters the characters (pinned).
- [T777] Trusted streaming markdown in capable interactive terminals:
  headings, bullets, inline `**bold**`/`*italic*`/`` `code` `` spans,
  and ``` fence delimiters are styled by a renderer-owned state machine
  (`cli.ui.md.StreamingMarkdownShaper` + `MarkdownLineStyler`) operating
  on already-sanitized, already-wrapped rows. Markers stay visible —
  styling only colors the original characters, so stripping ANSI always
  recovers the plain wrapped text byte-for-byte (the pinned invariant),
  and Talos chrome lines gain zero ANSI. Fenced code preserves spacing
  and hard-cuts at the pane width instead of word-wrapping; an
  unterminated fence flushes plain. Toggle: `ui.markdown` (default on;
  redirected/NO_COLOR/ASCII/dumb output is always plain regardless).
- [T776] Streamed answers now word-wrap at the live pane width in fully
  capable interactive terminals (color + Unicode + non-dumb), fixing the
  rail shear where long model lines overflowed and broke the answer-pane
  border. The new `StreamingAnswerShaper` replicates the block renderer's
  wrap byte-for-byte under arbitrary chunk boundaries (parity-tested
  against `renderBlock` as the oracle under 1-char, word-sized, and
  seeded-random chunkings at widths 60/80/96/120), emitting each row as
  soon as it fills — latency is bounded by one row plus one in-flight
  word. Degraded modes (redirected, scripted, NO_COLOR, ASCII, dumb)
  keep the historical pass-through bytes, pinned by goldens.
- [T775] The true-PTY manual-audit validator's prose-phrase checks (the
  protected-read denial, private-document handoff, and withheld-content
  phrases) now also match a wrap-tolerant view of the transcript: rail
  prefixes are stripped and consecutive pane lines rejoined, so a
  required phrase split by width-reactive soft wrapping (T772/T776)
  still validates. Paragraph breaks deliberately do not rejoin, and
  chrome checks — the byte-frozen approval prompt, isolation markers,
  command echoes, and the approvals counter — keep strict raw matching.
  Landed before the streaming wrap change so the evidence chain cannot
  be broken by a wrap boundary landing inside a required phrase.
- [T774] Interactive sessions now write through a single authoritative
  terminal-backed stream (`cli.ui.TerminalOutput.printStreamFor`): the
  banner, render engine, approval window, spinner, startup notices, and
  streamed answer chunks all flow through the JLine terminal's writer,
  replacing the previous split where streamed chunks used
  `terminal.writer()` while everything else printed to raw `System.out`.
  JLine's cursor/column model now sees every character that reaches the
  terminal, closing the documented Apr 2026 display-corruption class
  where a prompt redraw spliced scrollback into the input line.
  Scripted/redirected runs keep raw `System.out` — verified
  byte-identical against a pre-change transcript.
- [T773] The approval window and the `/status` dashboard resolve their
  width from the live terminal (clamped 60–120) instead of a hardcoded
  80. The approval prompt strings themselves are width-independent and
  stay byte-frozen via `ApprovalPromptText`/the T766 contract test;
  terminal-less paths (scripted approval, `talos status` outside the
  REPL, redirected output) keep the fixed 80 and do not consult
  `COLUMNS`, so their bytes are unchanged by construction.
- [T772] The answer pane resolves its width from the live terminal
  (clamped 60–120) instead of a hardcoded 96; the width is captured at
  stream open, so one streamed answer stays internally consistent and a
  terminal resize takes effect on the next answer. Paths without a
  terminal (redirected, scripted, e2e) keep the historical fixed 96 and
  never consult `COLUMNS`, so their bytes are unchanged by construction.
- [T771] Width resolution is now owned by a single rule
  (`cli.ui.TerminalWidths`): live JLine `Terminal.getWidth()` clamped to
  60–120, then the `COLUMNS` environment variable (same clamp), then the
  caller's surface default passed through unclamped so redirected and
  scripted output stays byte-identical. The startup banner is the first
  consumer — it now renders at the real terminal width in interactive
  runs instead of assuming 80 (`COLUMNS` is never set by default on
  Windows, so the env fallback effectively never fired there).
  Deliberate rule change: `COLUMNS` values of 40–59 previously rendered
  at face value; they now clamp to 60.
- [T770] `TerminalCapabilities.detectDefault()` (the input behind
  color/unicode/glyph selection) now takes its interactivity signal from
  the `isatty` probe instead of `System.console() != null` — the same
  JDK-22 hazard as T769, where redirected output would have been treated
  as interactive and received ANSI color and Unicode glyphs instead of
  byte-identical plain ASCII. The capability decision matrix itself is
  unchanged and remains pinned by `TerminalCapabilitiesTest`.
- [T769] Interactive-terminal detection now uses the OS-level `isatty`
  probe everywhere (new `cli.ui.InteractiveTty`, lifted from RunCmd's
  terminal selection) instead of `System.console() != null`, which on
  JDK 22+ reports a console even for piped/redirected output and would
  have flooded redirected transcripts with spinner carriage returns. The
  fallback for hosts where the JLine natives cannot load honors JDK 22's
  `Console.isTerminal()` via reflection (the build targets JDK 21).
  `RenderEngine` additionally takes its interactivity from the
  bootstrap's terminal selection (`lineReader` presence) rather than
  re-detecting, so scripted and test paths stay plain by construction.
- [T767] The history-chrome line prefixes that
  `MemoryUpdateListener.stripUiChromeForHistory` removes before assistant
  text reaches conversation history (`[Used N tool(s)...]`,
  `[Tool-call limit reached...]`, `[turn aborted...]`, `[Engine error...]`,
  `[Model '...' not found...]`, `✓ Edited/Created ...`,
  `Suggestion: edit_file has failed...`) are now shared constants
  (`core.util.UiChrome`) composed by both the emitters (LlmCallBudget,
  ToolLoopResultSummaryFormatter, ToolLoopFinalAnswerFinalizer,
  ToolMutationStateAccounting, EditFailureRepairStateAccounting,
  ToolReprompt* executors, AssistantTurnExecutor) and the stripper
  (MemoryUpdateListener, JsonTurnLogAppender), so an emitter rewording can
  no longer silently break stripping and reopen the BUG #1
  confidence-trick surface. A round-trip contract test
  (`runtime.UiChromeContractTest`) pins every emitter shape through the
  stripper, including the known gap that `✓ Updated ...` overwrite
  summaries are NOT yet stripped (fixed in T768). No output bytes changed.
- [T766] A cross-surface byte-identity contract test
  (`harness.ApprovalPromptContractTest`) now holds every approval-prompt
  evidence surface to the same bytes: the production gate's line forms,
  the scripted harness's published audit-event prompts, the true-PTY
  manual-audit validator's required transcript substring (exercised
  through a new string-level `auditTranscriptFindings` seam), the
  talosbench forbidden-substring bank (parsed from
  `tools/manual-eval/talosbench-cases.json`), and the process-driver REPL
  prompt. `ScriptedApprovalGate`, the PTY validator, and the approval
  smoke harness now reference `ApprovalPromptText` instead of retyped
  literals, so harness/production prompt drift is now structurally
  impossible rather than merely untested. No output bytes changed.
- [T765] The approval-prompt chrome strings (`Allow? [y=yes, a=yes for
  session, N=no]`, `Allow? [y=yes, N=no]`, the `Allow? [y=yes` prefix, and
  the `approval required` window title) are now owned by a single
  byte-frozen constants class (`cli.ui.ApprovalPromptText`) instead of
  being retyped at each call site; `CliApprovalGate` and
  `ApprovalPromptRenderer` render from the constants, and characterization
  tests pin the exact bytes against typed literals. These strings are
  load-bearing evidence-chain contracts (PTY manual-audit validator,
  talosbench forbidden-substring banks, scripted harness artifacts), so
  Wave 3 rendering work cannot drift them silently. No output bytes
  changed.
- [T764] The synchronized-approval workspace-operation scenarios
  (mkdir/copy/move/rename/delete/batch-apply, scripted and live) now claim
  the rendered outcome in addition to tool usage and file state: an
  approved-and-executed turn that fail-closes as BLOCKED (e.g.
  `OUTCOME_RENDERED {status=BLOCKED, classification=BLOCKED_BY_POLICY}`) now
  fails the harness instead of passing silently — the claim gap that masked
  T763's phantom expected-target block across the 0.10.2/0.10.3 packet
  lanes. PARTIAL outcomes still pass, so legitimate runtime-repair lanes are
  not overclaimed.

### Fixed
- [T768] `✓ Updated <path> (...)` mutation summaries (emitted when
  `talos.write_file` overwrites an existing file) are now stripped from
  conversation history like their `✓ Edited`/`✓ Created` siblings; they
  previously leaked into the model's context, exposing the same
  confidence-trick imitation surface as the documented BUG #1. The dead
  `✓ Wrote ` stripper entry is kept as a documented defensive rule (a
  line with that shape can only be chrome or a model imitating chrome).
- [T763] Task-contract target extraction no longer treats bare English
  function words ("by", "to", "with", "into", "using", ...) as path-like
  expected mutation targets; names with a file extension or path separator
  still extract. This removes the phantom remaining target "by" that the
  workspace-operation retry frame's "mkdir by writing/editing file content"
  wording injected into expected-target progress accounting, which
  fail-closed every approved copy/move/rename/delete retry turn as BLOCKED
  after the operation had already been approved, executed, and checkpointed
  (seen in the 0.10.2/0.10.3 packet sync-approval lanes).

## [0.10.3] - 2026-06-11

### Changed
- [T762] Read-only proposal grounding now derives ungrounded-file detection
  from observed tool evidence (result text plus the paths tools actually
  touched) instead of a hardcoded audit-fixture filename list — claims about
  ANY unread file now trigger the grounding warning, not just the seven
  fixture names. The policy moved from AssistantTurnExecutor into
  `runtime.outcome.ReadOnlyProposalGroundingGuard` per the policy-ownership
  doctrine.
- [T761] The advertised default tool surface is now derived from
  `ToolSurfacePlanner.plan()` over a canonical descriptor catalog instead of
  a hand-maintained duplicate branch tree; read-only turns with expected
  targets now advertise only `talos.read_file` (matching what the runtime
  always enforced — the model can no longer be advertised tools the runtime
  denies). Parity tests pin the catalog against the bootstrap registry.
- [T760] The protected-read answer postcondition now distinguishes blank
  model answers from refusals (truthful trace reasons) and scopes refusal-
  marker detection to the first 240 characters of the answer — long grounded
  answers with tail caveats like "the raw value cannot be shared" are no
  longer destroyed and replaced.
- [T759] Protected-path classification consolidated into a single canonical
  classifier (`ProtectedPathTokens`) with equals-or-suffix word-run matching;
  five divergent local copies (four repair planners + the protected-read
  answer guard) now delegate to it. Fixes `tokenizer.java`-class false
  positives while keeping `mysecrets.txt`/`api_token.txt`-class names
  protected; protected-content policy version bumped to v3 so RAG indexes
  rebuild their privacy partition.
- [T758] Tool failure classification is now driven by typed
  `ToolFailureReason` codes carried from producers through `ToolError` and
  `ToolOutcome`; all six message-sniffing classifier sites are migrated and
  the sniffing deleted, so error-message prose is free to change without
  silently disabling repair or outcome-truth policy. Redaction
  (`sanitizeToolResult`) now preserves the typed reason while rewriting
  prose.
- [T757] Mutation-intent blocking, pre-approval validators, and checkpoint
  capture now read `ToolOperationMetadata` from the registry-resolved tool
  instead of hand-maintained name lists (which failed open for tools missing
  from them); new `ToolMutationGate` treats unresolvable tools as mutating
  and checkpoint-required; `ToolCallSupport`'s duplicate name sets are
  deleted (static classification delegates to `ToolAliasPolicy`, pinned
  against metadata by a parity test).
- [T756] The approval window now shows a colored unified diff for write and
  edit mutations (java-diff-utils; capped at 60 lines; redacted; fail-closed
  skips for protected/oversized/binary targets; plain ASCII under
  NO_COLOR/ASCII terminals). The legacy approval detail stays byte-identical
  with the diff appended after it; a `APPROVAL_DIFF_PREVIEW` trace event
  records hash and line counts; risk inference ignores diff bodies so quoted
  "remove"/"delete" code cannot escalate the risk label.
- [T755] Markdown-commentary sanitization of write/edit content now runs
  once, pre-approval, in the runtime's call normalization — the approval
  preview, trace hashes, checkpoint, and written file all see identical
  bytes (approved bytes == written bytes). Tools write received bytes
  verbatim; sanitization is trace-recorded as a `TOOL_CONTENT_SANITIZED`
  event with redacted summaries.
- [T754] Hardened the bare tool-JSON detection regex (runtime parser and
  protocol stripper, which run on every model response) against catastrophic
  backtracking via possessive quantifiers; the pattern now has a single owner
  in `ToolProtocolText` and adversarial timeout regressions pin linear-time
  failure.

## [0.10.2] - 2026-06-11

### Changed
- [T753] Refreshed local Qodana evidence on the current head via the native
  fallback (Docker mode failed with the documented Windows Gradle-import I/O
  error): provenance now `qodana-results-match-current-candidate`, the three
  T752 findings are gone, zero critical issues, and the two triaged noise
  families are baselined in qodana.yaml with rationale.
- [T752] Behavior-preserving clarity refactors for the stale-scan Qodana
  findings: explicit null-flow in `ContextItem.fromToolResult` and
  `MutationTargetReadbackVerifier`, and try-with-resources around the command
  runner's executor (shutdownNow timeout semantics preserved), each pinned by
  tests.
- [T750] Hardened the coverage gate: INSTRUCTION floor 0.65→0.82, new BRANCH
  floor 0.62, and per-package floors for `runtime.policy`, `safety`, and
  `core.secret` ratcheted to measured actuals; CI workflow triggers repointed
  from the defunct-only `v0.9.0-beta-dev` filter to main + beta-dev +
  codex/feature branches.
- [T749] Added the release gate ledger: schema v1
  (`work-cycle-docs/release-gate-ledger.md`), a retrofitted GATES.json for
  the 0.10.1 packet, and `GatesLedgerTest` validating every ledger — release
  verdicts become machine-checkable artifacts with tooling-sourced SHAs.
- [T748] Added `TicketHygieneTest` (directory/status-token consistency and
  ID uniqueness repo-wide; strict template rules ratcheted for tickets
  T739+) and `scripts/ticket-aging.ps1` for open-queue age visibility.
- [T747] Added `scripts/cut-candidate.ps1`: hermetic scripted candidate cut
  (clean-tree guard, bump, commit, build-from-committed-tree, launcher-vs-HEAD
  cross-check, mandatory post-bump check, summary regeneration with version
  verification, and a tooling-sourced `candidate-manifest.json`), removing the
  provenance-defect class found on the 0.10.1 cut.
- [T751] Codified work-cycle doctrine: AGENTS.md candidate loop now orders
  changelog-before-bump (matching `bump-patch.ps1`), records the dirty-tree
  evidence-downgrade rule and the tooling-only SHA rule, and points at the
  scripted cut; the operator prompt branch reference is packet-anchored; the
  ticket template now requires per-ticket Unreleased CHANGELOG entries.
- [T746] Wave-1 stabilization evidence: first-ever complete Qwen 31-scenario
  synchronized live bank (artifact scan PASS) plus a zero-rescue GPT-OSS
  bank, proving the T739-T744 constraint stack on both audited models; the
  bank-position hypothesis was falsified via byte-identical seeded provider
  bodies.
- [T745] `proposal-only-does-not-mutate` is now runnable as a focused
  scripted/live synchronized-approval scenario (the only scenario exercising
  `talos.retrieve`), enabling clean focused retrieve evidence.
- [T744] Native tool-call arguments now survive the wire losslessly: container
  values (arrays/objects) are preserved as JSON in both argument converters
  (previously silently destroyed or rendered as Java toString), and
  `talos.apply_workspace_batch` advertises a native `operations` array as its
  grammar-constrained shape while still accepting the legacy double-encoded
  `operations_json` string.
- [T743] Escalating mutation repair ladder: malformed tool-protocol debris on
  mutation/workspace-obligation turns now gets one bounded MissingMutationRetry
  pass with escalated constraints (temperature pinned to zero) before the
  no-action notice; genuinely invalid mutating parameters get one corrected
  retry with the tool error echoed. Pre-approval policy rejections (sandbox
  escape, source-evidence blocks) keep fail-fast behavior.
- [T742] The workspace-operation capability frame now includes a literal
  `operations_json` example so 14B-class models see the exact wire format for
  batch operations instead of prose-only key descriptions.
- [T741] Source-evidence repair re-prompts now pin the known required tool via
  named tool choice (read-before-write repair pins `talos.read_file`,
  post-read write repair pins `talos.write_file`) and run near-greedy,
  eliminating the wrong-tool substitution observed in the t325 bank failure.
- [T740] Added provider sampling governance: new `SamplingControls`
  (temperature/top_p/top_k/seed) on `ChatRequestControls`, near-greedy
  defaults on tool-obligation turns, optional `llm.sampling.*` config
  overrides (incl. fixed seed for reproducible audit banks), emitted on the
  llama.cpp wire and rendered in prompt-debug.
- [T739] Wired `WORKSPACE_OPERATION_REQUIRED` turns to provider tool-choice
  enforcement (`required`, or named single-tool pinning when the surface
  exposes exactly one workspace tool), closing the constraint-coverage gap
  behind the Qwen full-bank workspace-batch failures; added
  `LlmClient.supportsNamedToolChoice()`.

## [0.10.1] - 2026-06-10

### Changed
- [T735-done-high] Added a runtime-owned private-document denial notice so the
  user-visible final answer deterministically says private document content was
  withheld from model context instead of relying on model paraphrase.
- [T736-done-high] Made the PTY manual-audit packet self-contained by running
  Talos under a packet-local isolated home, generating a launcher script, and
  requiring transcript evidence of both packet isolation and the ordinary `.env`
  approval prompt.
- [T737-done-high] Repaired approved private-document containment answers so an
  approved handoff can answer narrow yes/no containment questions without
  printing the protected value and without leaking redacted history wording into
  the visible answer.
- [T738-done-medium] Updated PTY validator trace acceptance so current
  approval-count `/last trace` evidence can satisfy the private-document
  approval-trace requirement without relaxing the denial-wording gate.
- Reset candidate provenance for the next release packet: the PTY/manual,
  synchronized-approval, and capability lanes will be rerun from a single clean
  committed candidate instead of mixing evidence across dirty-tree builds.

## [0.10.0] - 2026-06-07

### Added
- Added ArchUnit (`com.tngtech.archunit:archunit-junit5`) bytecode-level
  architecture guards in `dev.talos.architecture.LayeredArchitectureTest`,
  mirroring the six package-direction invariants enforced by the regex-based
  `validateArchitectureBoundaries` ratchet. ArchUnit additionally catches
  dependencies expressed through types, generics, annotations, and exceptions
  that the source scanner cannot see.
- Added a report-only architecture discovery pass
  (`dev.talos.architecture.ArchitectureDiscoveryReportTest`) that uses the
  ArchUnit Core API to write a deterministic Markdown report to
  `build/reports/talos/architecture/architecture-discovery-report.md` (package
  counts, dependency hotspots/fan-in/fan-out, package dependency map,
  runtime-control spine, layer-boundary candidates, and top-level package
  cycles). It never fails the build on findings; it is evidence for manual
  review before any rule is promoted to a hard guard.
- Added a report-only architecture cycle analysis pass
  (`dev.talos.architecture.ArchitectureCycleReportTest`) that slices the
  imported `dev.talos` bytecode at four levels (top-level packages, runtime
  subpackages, cli subpackages, core subpackages) and writes a deterministic
  Markdown report to
  `build/reports/talos/architecture/architecture-cycle-report.md`. Cycles are
  detected by a Tarjan strongly-connected-component pass and cross-checked with
  ArchUnit's caught `beFreeOfCycles` rule; severity is classified per level. It
  never fails the build on detected cycles.
- Added a report-only execution-harness spine access report
  (`dev.talos.architecture.ArchitectureSpineAccessReportTest`) that, for a fixed
  set of runtime-control "spine" classes (e.g. `AssistantTurnExecutor`,
  `ToolCallLoop`, `TaskContractResolver`, the policy/verifier classes,
  `CurrentTurnPlan`, `ExecutionOutcome`, `ConversationManager`), reports
  class-level fan-in/fan-out, top callers/callees, and ArchUnit-resolved
  method/constructor call counts to
  `build/reports/talos/architecture/harness-spine-access-report.md`. Deterministic,
  capped to top-N, and never fails the build on high fan-in/fan-out.
- Added a second generation of hard ArchUnit guards in
  `dev.talos.architecture.LayeredArchitectureTest`, promoted only after the
  report-only passes showed zero edges: `runtime.policy`, `runtime.verification`
  ↛ `cli`; `runtime.toolcall` ↛ `cli.repl`; `tools` ↛ `cli`; and `spi` ↛ `app`.
  Documented hard guards, report-only findings, accepted exceptions, and
  candidate future guards in `docs/architecture/11-architecture-guardrails.md`.
- [T719-done-high] Added a redacted audit snapshot utility and Gradle task for
  canary-clean milestone/manual audit packets, so release-clean scans can use
  sanitized final workspace evidence instead of raw fixture snapshots.

### Changed
- [T334-done-high] Added release-ledger discipline for beta candidates:
  `CHANGELOG.md` now keeps an `Unreleased` section, the patch bump script moves
  those notes into the next numeric candidate version, and `check` validates
  that the top released changelog entry matches `talosVersion`.
- [T335-done-high] Added an architecture hygiene baseline for the next refactor
  sequence, covering package-boundary debt, policy ownership, verifier/repair
  structure, CLI composition, release-evidence gates, and the recommended T336
  boundary-ratchet implementation.
- [T336-done-high] Added a ratcheted architecture-boundary import scanner wired
  into `check`, with an initial baseline of 62 forbidden import
  edges and focused TestKit coverage for new and stale boundary drift.
- [T337-done-medium] Moved tool alias metadata ownership from
  `runtime.toolcall` to `tools`, reducing the architecture-boundary baseline
  from 62 to 61 forbidden import edges without changing alias behavior.
- [T338-done-medium] Moved `WorkspaceSymbolChecker` ownership from CLI modes
  into core indexing, reducing the architecture-boundary baseline from 61 to 60
  forbidden import edges without changing prompt-routing behavior.
- [T339-done-high] Hardened `validateArchitectureBoundaries` so the ratchet
  catches fully-qualified forbidden `dev.talos...` type references as well as
  imports, while ignoring comments and string/char literals.
- [T340-done-medium] Removed the runtime-policy logging dependency from
  `IndexedWorkspaceSymbolChecker`, reducing the architecture-boundary baseline
  from 60 to 59 forbidden references without changing symbol lookup behavior.
- Documented monotonic pre-1.0 beta versioning: do not downsize or reuse
  candidate versions after artifacts, commits, tags, or audit evidence refer to
  them; use `0.9.10+` for narrow candidates, consider `0.10.0` for a broad beta
  milestone, and reserve `1.0.0` for stable beta exit.
- Backfilled the post-0.9.9 beta stabilization ledger with the audit-evidence,
  protected-document, terminal approval, prompt-surface, static-web, office
  document, Python-claim, site, and artifact-canary hardening work landed after
  the 2026-05-15 candidate declaration.
- Strengthened candidate provenance by making placeholder changelog text a hard
  local validation failure instead of a manual review hazard.
- [T720-done-medium] Reworded conditional static-web no-change answers as
  diagnostic inspection, keeping `Verification: NOT_RUN` truthful for
  inspection-only turns.

## [0.9.9] - 2026-05-15

### Changed
- Consolidated post-0.9.8 beta hardening into a named candidate, including the
  runtime control-plane, active-context, evidence-obligation, outcome-dominance,
  protected-read, static-web verification, workspace-operation, command-policy,
  and TalosBench work already landed on `v0.9.0-beta-dev`.
- [T251-done-high] Added managed llama.cpp model setup and config diagnostics,
  including audited `qwen2.5-coder-14b` and `gpt-oss-20b` setup profiles,
  YAML-safe Windows config generation, Talos-owned Hugging Face cache support,
  and verbose malformed-config reporting.
- [T252-done-high], [T255-done-high], and [T257-done-medium] improved natural
  intent routing for directory creation, batch workspace operations, and
  bounded command requests without exposing arbitrary shell execution.
- [T253-done-high], [T254-done-high], [T259-done-high], and [T262-done-high]
  hardened source-derived artifact work so source files are read as evidence,
  output files are tracked as mutation targets, privacy negations stay scoped,
  and derived writes before source reads are blocked before approval.
- [T256-done-high], [T258-done-medium], and [T261-done-medium] corrected
  prior-outcome and session-evidence answers so status and uncertainty
  responses are scoped to the asked artifact or workspace operation instead of
  the latest unrelated turn.
- [T260-done-high] and [T264-done-medium] kept natural list-style prompts on
  filename-only evidence paths, including casual `what is in here?` phrasing,
  without reading file contents.
- [T263-done-medium] and [T265-done-medium] refreshed TalosBench expectations
  and assertion scope so the benchmark checks the current product contract and
  final natural turn where appropriate.
- Added and polished the Talos beta landing page under `site/`, with honest
  placeholder beta calls to action, no fake release artifact URL, static tests,
  and Playwright e2e coverage.
- [T266-done-high] Declared the 0.9.9 beta candidate and produced the candidate
  build/test/site/static-analysis summary evidence packet for release review.

## [0.9.8] - 2026-04-29

### Changed
- [T43-done-medium] Protected reads now display as sensitive/protected reads,
  and denied protected reads are classified as blocked by approval instead of
  completed read-only answers.
- [T44-done-medium] Bounded small-web repair now requires complete
  `write_file` replacements for structural HTML/CSS/JS repair targets, rejects
  brittle `edit_file` attempts for those targets before approval, and continues
  through planned full-write repair targets.
- [T45-done-medium] Simple folder-listing prompts now use `list_dir` only,
  suppress content tools and generic workspace context, and shape filename
  answers from actual directory listing results.
- [T46-done-medium] `/last` and `/last trace` now redact secret-like
  `KEY=value` values from the human-readable user request preview while
  preserving path, tool, and policy metadata.
- [T48-done-high] Added current-turn capability frames and action-obligation
  enforcement so mutation-capable turns cannot final-answer with false
  no-filesystem or no-modification denials.
- [T49-done-high] Added the TalosBench live prompt matrix and failure
  taxonomy.
- [T50-done-high] Added the TalosBench live prompt runner and starter prompt
  cases.
- [T51-done-high] Added TalosBench `/last trace` assertion support.
- [T52-done-high] Documented Terminal-Bench 2 compatibility and task
  classification for Talos.
- [T53-done-high] Added the evaluation failure intake workflow and reusable
  evaluation-derived ticket template.

## [0.9.7] - 2026-04-29

### Changed
- [T29-done-medium] Cleaned current native Qodana high findings and restored
  fresh local Qodana evidence to 0 high and 0 critical applied-profile issues.
- [T30-done-high] Added the post-0.9.6 execution-discipline and local-trust
  architecture spine.
- [T31-done-high] Mapped runtime policy ownership before policy extraction so
  future refactors have a tested responsibility map.
- [T32-done-high] Designed local turn trace model v1, including redaction,
  event shape, storage direction, and T33 implementation criteria.
- [T33-done-high] Implemented local turn trace v1 for task contracts, tool
  surfaces, approvals, blocks, checkpoints, verification, and outcomes.
- [T34-done-high] Designed declarative allow/ask/deny permissions with
  deny-first precedence and protected path defaults.
- [T35-done-high] Implemented declarative local permissions for tools, paths,
  protected resources, approvals, and trace-visible decisions.
- [T36-done-high] Designed local checkpoint/restore as the trust layer before
  approved mutations.
- [T37-done-high] Implemented local checkpoint creation before approved
  mutations and restore support.
- [T38-done-high] Designed bounded repair controller behavior for
  post-verification failures and invalid edit loops.
- [T39-done-high] Implemented bounded repair planning using static verifier
  findings without weakening approval, permission, or stop policies.
- [T40-done-high] Fixed formatting-negation prompts so `do not use angle
  brackets/placeholders` no longer cancels explicit mutation intent.
- [T41-done-high] Ran the installed Talos manual prompt evaluation before the
  0.9.7 candidate and recorded blockers/follow-ups.
- [T42-done-high] Added deterministic exact full-file content expectations so
  literal overwrite requests verify the final file content instead of relying
  on write/readback alone.
## [0.9.6] - 2026-04-28

### Changed
- [T11-done-high] Status questions such as `did you make the changes?`
  now resolve as verify-only/read-only turns instead of mutation turns.
- [T12-done-high] Mutating tool calls missing required arguments are rejected
  before approval, so users are not asked to approve invalid writes or edits.
- [T13-done-high] Tool-call JSON protocol text is kept out of final visible
  answers when the protocol path handles or rejects it.
- [T14-done-high] Repair follow-ups now use one shared task contract for trace,
  prompt read-only mode, native tool selection, and execution policy.
- [T15-done-high] Verification wording now distinguishes file write/readback
  checks from task-specific completion verification.
- [T16-done-high] Added static web-app verification for linked assets,
  placeholders, duplicate asset references, expected DOM elements, and
  JavaScript selector coherence.
- [T17-done-medium] Expected target matching now normalizes paths for Windows
  casing and separator behavior.
- [T18-done-medium] Added idempotent web asset checks so repeated stylesheet or
  script insertions do not look verified.
- [T19-done-high] Prior-change status follow-ups now preserve the latest
  verified outcome instead of overclaiming completion.
- [T20-done-high] Scoped mutation limiters such as `fix only styles.css` now
  allow the intended target while blocking forbidden targets.
- [T21-done-high] Post-denial retry turns reissue the previously denied action
  through approval instead of drifting into no-op answers.
- [T22-done-high] Overwrite, rewrite, replace, repair, and natural
  non-technical artifact requests now classify as mutation-capable when they
  ask Talos to modify local files.
- [T23-done-high] Repair retries after static verification failure now include
  verifier findings and steer small web-file repair toward bounded full-file
  replacement when edit anchors are brittle.
- [T24-done-high] Mutating tool protocol blocked by read-only policy is now
  sanitized with truthful no-action wording instead of leaking raw JSON or fake
  approval prose.
- [T25-done-high] Chat-mode small talk, capability prompts, and explicit
  privacy-negated prompts no longer expose or call workspace tools.
- [T26-done-medium] Repeated status follow-ups now return direct,
  deduplicated verified-outcome summaries.
- [T27-done-high] Malformed Talos tool-call-like output is sanitized and
  reported without leaking protocol text or stalling the turn.
- [T28-done-high] Functional web verification now fails when a scripted web
  task has no JavaScript behavior, even if HTML and CSS were written.
## [0.9.5] - 2026-04-27

### Changed
- [T02-done-high] Required read-only workspace evidence for `VERIFY_ONLY`
  confirmation turns and grounded web completion checks with static diagnostics
  before accepting final answers.
- [T03-done-high] Buffered natural workspace-explain turns and retried no-tool
  or list-only underinspection with read-only inspection from the current
  workspace.
- [T07-done-high] Added JSON-backed multi-turn coverage so follow-up change
  summaries preserve partial/static verification truth.
- [T08-done-high] Filtered `/last` output to active-process turns so unloaded
  saved session history is not presented as the current trace.
- [T04-done-medium] Added read-only deictic follow-up intent inheritance without
  carrying mutation permission.
- [T05-done-medium] Answered capability/onboarding small talk as Talos instead
  of generic base-model boilerplate.
- [T06-done-medium] Improved `/help all` discoverability and made `edit_file`
  user-visible text ASCII-safe for transcript capture.
- [T09-done-medium] Fixed dev-mode natural root listing prompts such as
  `list the files here`.
- [T10-done-medium] Expanded the manual QA constitution with stable case IDs,
  coverage tags, severity taxonomy, and finding-to-ticket intake rules.

## [0.9.4] - 2026-04-26

### Changed
- [T01-done-high] Blocked no-tool answers that deny Talos can access local
  workspace files when read tools are available; such turns now finalize as an
  advisory capability correction, and streaming sessions visibly emit the
  correction after the raw model output.

## [0.9.3] - 2026-04-26

### Changed
- Added tool-backed retry for explicit mutation turns where the model first answers without calling file tools, including compatibility for `create_file` / `function_name` tool-call aliases.
- Improved natural conversational flow: identity small talk answers as Talos, natural read-only site diagnostics are grounded in static workspace facts, and follow-up change summaries reuse prior verified outcomes.
- Improved manual QA/debug ergonomics: `/last --verbose` maps to trace output, stale turn selection prefers latest timestamps, and slash `/grep` searches CSS-family files by default.

## [0.9.2] - 2026-04-26

### Changed
- Made saved workspace sessions explicit by default: Talos now reports saved history without injecting it into prompt context unless `session.auto_load=true` or `/session load` is used.
- Honored `session.persistence=false` in CLI bootstrap so ephemeral runs skip persistent session reads and writes.
- Preserved explicit session restore, including JSONL crash-recovery fallback, and improved cleanup of turn-log-only sessions.

## [0.9.1] - 2026-04-25

### Changed
- Added a narrow post-apply static task verifier for mutation targets and small HTML/CSS/JS selector coherence.
- Wired verifier status into central execution outcomes so Talos can distinguish applied, verified, failed, and incomplete static checks.
- Added deterministic verifier scenarios for failed selector repair, successful CTA repair, and partial mutation non-completion.

All notable Talos distribution changes should be recorded in this file.

The format is intentionally simple:
- one section per released public version
- public versions are numeric only: `major.minor.patch`
- patch increments (`0.9.1`, `0.9.2`, ...) mark intentional distribution builds

## [0.9.0] - 2026-04-22

Initial numeric-version baseline for the current public line.

### Changed
- moved the canonical Talos public version source of truth into Gradle properties
- removed hardcoded public version values from build and CLI fallback paths
- aligned CLI version output with runtime build metadata resolution
- added this root changelog and a patch bump script for future release discipline

