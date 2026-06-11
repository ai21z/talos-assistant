# 0.10.4 Release Packet — Wave 3 TUI — 2026-06-12

Packet: `current-0.10.4-release-packet-20260612-005957`
Branch: `feature/wave3-tui`
Cut commit: `21a868dbfebcfd3859ed0decde20d48edc606af3` (scripted hermetic cut;
SHA tooling-sourced; see `build/reports/talos/candidate-manifest.json`).
Machine-checkable verdicts: sibling `-GATES.json` (schema
`talos.releaseGates.v1`).

## What this candidate contains

Wave 3 (TUI) of the top-tier roadmap
(`work-cycle-docs/research/talos-top-tier-evaluation-and-roadmap-20260610.md`,
items 1, 2, 4, 5 — item 3, the diff-bearing approval window, shipped as T756
in Wave 2), tickets T765–T782, plus two trust-surface fixes (T763, T764)
that landed after the 0.10.3 cut and ride in this candidate.

Phase 0a — byte-frozen chrome contracts (landed before any rendering change):

1. **T765** — approval-prompt chrome strings extracted to byte-frozen
   constants (`cli.ui.ApprovalPromptText`); production gate + window
   renderer migrated; bytes pinned against typed literals.
2. **T766** — cross-surface byte-identity contract test
   (`harness.ApprovalPromptContractTest`): production line forms, scripted
   harness audit-event prompts, PTY-validator required substring, talosbench
   forbidden-substring bank, and the REPL prompt all held to the same bytes.
3. **T767** — history-chrome prefixes shared between every emitter and the
   BUG #1 stripper (`core.util.UiChrome`); round-trip contract test pins
   every emitter shape through `stripUiChromeForHistory`.
4. **T768** — stripper gap fixed: `✓ Updated …` overwrite summaries now
   stripped from history; dead `✓ Wrote ` entry kept as documented defense.

Phase 0b — width / writer / detection foundation:

5. **T769** — isatty-based interactive detection (`cli.ui.InteractiveTty`),
   JDK-22-proof; scripted/test paths plain by construction.
6. **T770** — `TerminalCapabilities` takes interactivity from isatty (same
   JDK-22 hazard closed for color/unicode selection).
7. **T771** — single width-resolution rule (`cli.ui.TerminalWidths`):
   live terminal width clamped 60–120, COLUMNS fallback, surface default
   unclamped; banner is the first consumer.
8. **T772** — answer pane width from the live terminal (was hardcoded 96);
   captured at stream open; terminal-less paths byte-unchanged.
9. **T773** — approval window + `/status` width from the live terminal
   (was hardcoded 80); prompt strings stay byte-frozen.
10. **T774** — one authoritative terminal writer
    (`cli.ui.TerminalOutput.printStreamFor`) for banner, render engine,
    approval window, spinner, notices, and streamed chunks; closes the
    Apr 2026 display-corruption class; redirected runs keep raw
    `System.out`, verified byte-identical.

Phase 1 — wrap + trusted streaming markdown:

11. **T775** — PTY validator prose-phrase checks made wrap-tolerant BEFORE
    the wrap landed; chrome checks keep strict raw matching.
12. **T776** — streaming wrap parity (`cli.ui.StreamingAnswerShaper`): rail
    shear fixed; byte-parity with `renderBlock` proven under 1-char,
    word-sized, and seeded-random chunkings at widths 60/80/96/120.
13. **T777** — trusted streaming markdown state machine (`cli.ui.md`):
    headings, bullets, inline bold/italic/code, fences; markers visible,
    colorize-only (owner decision); ANSI-stripped output byte-equals the
    plain wrapped text (pinned); `ui.markdown` toggle, default on.
14. **T778** — nanorc-highlighted code fences with Talos-authored minimal
    definitions under `/nanorc/` (no GPLv3 vendoring; owner decision);
    ANSI-aware width cut; unknown languages degrade to plain.

Phase 2 — status row:

15. **T779** — JLine `Status` bottom row replaces the raw `\r` spinner
    thread on capable terminals (`cli.ui.StatusRowPresenter`); strictly
    additive — route hints, turn stats, and approval lines remain printed
    scrollback lines; legacy `\r` fallback for dumb/legacy consoles.
16. **T780** — live route/model/turn context on the status row, polled per
    tick, ANSI-aware truncation; printed evidence lines byte-unchanged.

Phase 4/5 — upgrade + ADR:

17. **T781** — JLine 3.26.3 → 3.30.13 as an isolated commit; `.provider("jni")`
    pinned; absorbed the 3.30 writer-encoding change in `TerminalOutput`;
    gates on this packet's fresh true-PTY cycle.
18. **T782** — ADR `docs/architecture/31-inline-tui-strategy-and-fullscreen-rejection.md`:
    full-screen TUI rejected with evidence; Wave 3 standing rules locked.

Carried trust-surface fixes:

19. **T763** — phantom expected-target "by" no longer extracted from
    workspace-op retry wording (the 0.10.2/0.10.3 BLOCKED-trace quirk).
20. **T764** — sync workspace-op scenarios now claim the rendered outcome;
    approved-then-BLOCKED turns fail the harness instead of passing.

## Gate status (see GATES.json for the authoritative ledger)

Ledger recorded at packet creation: `DETERMINISTIC` and `STATIC_ANALYSIS`
are green; the live lanes (`SAFE_REDIRECTED_STDIN`, `SYNC_APPROVAL`,
`CAPABILITY_PRIVATE_MODE`) are being executed by this close session and
their verdicts will be recorded in a follow-up commit; `TRUE_PTY_MANUAL`
is owner-run and mandatory this wave (every ticket touches rendering).

| Lane | Status at packet creation |
|---|---|
| Deterministic summaries | PASS — all four summaries 0.10.4; post-bump check green; launcher reverified 0.10.4 after catching a stale post-cut rebuild |
| Qodana static analysis | PASS — fresh native scan at the cut revision (operator-attested; linter 253.31821 emits no provenance metadata — see self-distrust notes); 0 critical, 113 findings (108 warning / 5 note) |
| `SAFE_REDIRECTED_STDIN` (both models) | scheduled this session — byte-identical-plain redirected output is the wave's headline degradation claim |
| `SYNC_APPROVAL` (both models, seed 424242) | scheduled this session — watch the T763/T764 workspace-op delta vs 0.10.3 |
| `CAPABILITY_PRIVATE_MODE` (48 runs) | scheduled this session |
| `TRUE_PTY_MANUAL` | owner-run, REQUIRED — wave-3 visual surface (markdown, fences, status row + resize, dynamic widths, JLine 3.30.13) |
| Canary scans | after the lanes |

## Self-distrust notes

- **Qodana provenance:** the qodana.yaml-pinned 2026.1 linter
  (build 253.31821) no longer writes `metaInformation.json` /
  `result-allProblems.json`, so `qodana-summary.json` reports
  `qodana-provenance-incomplete` and the SARIF has no VCS provenance
  fields. The scan-revision claim therefore rests on operator attestation
  (clean tree at the cut commit, verified by `git status` immediately
  before and after the scan) instead of tool-recorded provenance.
  The finding delta (88 → 113) mixes wave-3 surface growth with linter
  build drift and was not decomposed.
- **Stale launcher incident:** an aborted post-cut session rebuilt
  `installDist` at 22:03Z reporting 0.10.3 (stale configuration state) and
  left a gpt-oss llama-server listening on 18115. Both were caught by the
  packet preflight: launcher rebuilt from the clean cut tree (reverified
  `Talos 0.10.4`), server killed, ports verified free before any lane.
- Markdown/nanorc styling and the status row are interactive-only by
  activation predicate; redirected/NO_COLOR/ASCII/dumb output is asserted
  byte-identical-plain by goldens, the redirected lanes, and the PTY
  degradation spot-checks — not by construction in a single place.
- Deferred, documented in the ADR: CJK/double-width wrap counting
  (plain-char counting keeps renderBlock byte-parity), hidden-marker
  markdown mode, status row staying live during streaming (today it hides
  at first chunk), deeper `/status` width-reactivity.
- The T776 shaper's parity oracle is `renderBlock`; if renderBlock's wrap
  ever changes, the parity tests pin the pair together rather than either
  one absolutely.
