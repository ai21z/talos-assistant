# 31. Inline TUI Strategy and Full-Screen Rejection (ADR)

Status: accepted (Wave 3, 2026-06-11)

Decision owner: project owner; recorded during the Wave 3 TUI implementation
(T765-T781, branch `feature/wave3-tui`).

## Context

The 2026-06-10 top-tier evaluation
(`work-cycle-docs/research/talos-top-tier-evaluation-and-roadmap-20260610.md`)
graded the Talos TUI a generation behind the reference CLIs (Claude Code,
gemini-cli, Codex CLI, aider): no markdown, no syntax highlighting,
width-blind rendering, a raw `\r` spinner. The obvious-sounding remedy - a
full-screen TUI - was evaluated and **rejected**. This ADR records that
decision and its evidence so the question is not relitigated each wave.

The deciding constraint is the evidence chain. Talos's differentiator is
persisted, string-matchable evidence: the true-PTY manual-audit validator
(`SynchronizedCliPtyManualAuditValidator`) and the talosbench live prompt
matrix substring-match plain-scrollback transcripts (approval prompts, route
lines, denial phrases, approval counters). Recorded audit packets reference
those bytes; AGENTS.md makes terminal-UI evidence a release gate.

## Decision

Talos stays on **incremental inline rendering on the existing JLine stack**.
No alternate screen, ever; scrollback is the evidence medium and is
preserved structurally.

The "premium feel" gap is closed by five inline elements (all shipped in
Waves 2-3): colored unified diffs at the approval window (T756), streaming
markdown with visible markers (T777), nanorc-highlighted code fences
(T778), a JLine `Status` bottom row for spinner/elapsed/route/model/turn
(T779/T780), and width-reactive layout clamped 60-120 (T771-T773).

## Rejected alternatives

| Alternative | Why rejected |
| --- | --- |
| Lanterna full-screen TUI | Windows backend is experimental on the platform Talos's owner actually ships on; full-screen buffer destroys plain scrollback. |
| Jexer | Falls back to a Swing window outside supported terminals - not a terminal CLI anymore. |
| Alternate-screen rendering (any framework) | The alternate screen erases the scrollback transcript the PTY evidence chain string-matches; every recorded packet and the manual-audit validator would be invalidated. |
| JLine full-screen `Display` management | Same scrollback destruction with extra cursor-state risk; JLine's own corruption incident (Apr 2026, fixed structurally in T774) showed how fragile competing writers are. |

Market practice agrees: Codex CLI deliberately uses a ratatui *inline*
viewport to preserve scrollback, and gemini-cli renders through Ink's
append-only `Static`. The premium CLIs are inline CLIs.

## Standing rules this ADR locks in

1. **Byte-frozen chrome contracts.** Approval-prompt strings live in
   `cli.ui.ApprovalPromptText`; history-chrome prefixes in
   `core.util.UiChrome`. The e2e `ApprovalPromptContractTest` holds
   production, the scripted harness, the PTY validator, and the talosbench
   bank to identical bytes (T765-T767). Changing them is a
   PTY-revalidation event.
2. **Degradation is byte-identical plain.** Redirected, scripted, NO_COLOR,
   ASCII, and dumb-terminal output keeps the historical bytes - markdown,
   highlighting, wrapping, and the status row activate only in fully
   capable interactive terminals (`RenderEngine.styledStreamingEnabled`).
3. **Markers stay visible.** Markdown styling colors the original
   characters and never removes them, so stripping ANSI from a styled
   stream recovers the plain wrapped text byte-for-byte and transcript
   string-matching cannot regress (owner decision, T777).
4. **Nanorc definitions are Talos-authored.** GNU nano's GPLv3 syntax files
   are not vendored; minimal project-licensed definitions live under
   `src/main/resources/nanorc/` (owner decision, T778).
5. **One authoritative writer.** Every interactive byte flows through the
   terminal-backed stream (`cli.ui.TerminalOutput`, T774); the status row
   draws only through JLine `Status` (T779). Nothing writes raw `\r` or
   bypasses JLine's cursor model.
6. **The status row is additive.** Route hints, turn stats, and approval
   lines remain printed scrollback lines - the row mirrors them but never
   replaces them, because the validator matches the printed lines (T780).
7. **JLine upgrades are isolated PTY-revalidation events.** Terminal
   provider internals shift bytes (T781 absorbed an output-encoding
   behavior change); a version bump is always its own commit gated on a
   fresh true-PTY cycle.

## Revisit criteria

Reopen this decision only if (a) the evidence chain moves off
string-matched scrollback transcripts to structured machine-readable
transcripts, or (b) Talos drops Windows-first support. Either change
removes the constraint that makes full-screen rendering destructive.

## References

- Evaluation and roadmap: `work-cycle-docs/research/talos-top-tier-evaluation-and-roadmap-20260610.md` (TUI verdict)
- CLI UI output architecture audit: `30-cli-ui-output-architecture-audit.md`
- Wave 3 tickets: T765-T781 in `CHANGELOG.md` (Unreleased as of this ADR)
- AGENTS.md: terminal UI evidence priorities and clean-audit environment rules
