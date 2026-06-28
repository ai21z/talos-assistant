# T762 - Evidence-Derived Grounding Guard Extracted From The Executor

Status: done - completed in wave 2; see completion evidence section
Severity: medium
Release gate: no (grounding-truth coverage + policy-ownership doctrine)
Branch: feature/wave2-trust-surface
Created/updated: 2026-06-11
Owner: external assistant

## Problem

The read-only proposal grounding policy lived inline in
AssistantTurnExecutor, and its workspace-file detection was a hardcoded
list that is literally the AGENTS.md audit fixture's filenames (.env,
config.json, index.html, notes.md, report.docx, script.js, styles.css) -
teaching-to-the-test: audit answers were checked, real-workspace answers
(main.py, data.csv, anything not in the fixture) were not. It also
violated the AGENTS.md policy-ownership doctrine ("AssistantTurnExecutor
should be an orchestrator, not a warehouse for every policy marker").
2026-06-10 evaluation, roadmap item W2.8.

## Design

- New `runtime.outcome.ReadOnlyProposalGroundingGuard` (joins the
  *AnswerGuard family) owns: GROUNDED_PROPOSAL_WARNING (literal
  unchanged - pinned by tests), the proposal-turn shape check, the generic
  command/dependency and internal-content marker sets (kept - they are not
  fixture-specific), the conditional-context exemption, the .env-exclusion
  line stripping, and the NEW evidence-derived file detection. ATE's
  `groundedReadOnlyProposalAnswerIfNeeded` is a thin delegation; the
  marker sets and seven helper methods are deleted from ATE. Scope stops
  there - no broader ATE refactor (Wave 5 dissolves it).
- File detection: filename-shaped mentions in the answer (word-ish stem +
  alphabetic 2-8 char extension, plus a generic leading-dot config-file
  shape class) are checked against (a) observed tool-result text and
  (b) the paths the tools actually touched (readPaths + outcome path
  hints). The path ledger matters: tool-result text does not echo target
  paths (read output is numbered lines), so the reviewed file's own name
  must count as evidenced even when its content never mentions it -
  without this, every review answer naming its own subject would warn.
- False-positive bounds: domain-style extensions (com/org/net/io/dev)
  skipped; versions (1.2.3) and abbreviations (e.g.) don't match the
  shape; mentions that ARE ecosystem command markers (node.js) keep the
  command check's conditional-context semantics.

## Behavioral delta (intended)

Any unevidenced filename claim in a read-only proposal answer now warns -
previously only the seven fixture names did. Audit compatibility holds:
every fixture file matches the generic shapes, so the AGENTS.md prompt
bank's expected warnings still fire (the five pre-existing executor tests
pass unchanged).

## Architecture Metadata

Capability: read-only proposal answer grounding
Operation(s): none (answer postcondition)
Owning package/class: `dev.talos.runtime.outcome.ReadOnlyProposalGroundingGuard`
New or changed tools: none
Risk, approval, and protected paths: n/a (soft warning prefix, never a block)
Outcome and trace: warning text unchanged
Refactor scope: the guard extraction + ATE delegation only

## Tests / Evidence

- `ReadOnlyProposalGroundingGuardTest`: fixture filenames still flag;
  NON-fixture filename flags (fails on the pre-T762 design); evidenced
  mentions clean; reviewed file's own name evidenced via read paths;
  prose false-positive suite (v1.2.3, e.g., example.com, conditional
  gradle); command markers; .env exclusion stripping; non-proposal
  pass-through; blank/null no-ops.
- `AssistantTurnExecutorTest`: the five pre-existing grounding pins pass
  unchanged (audit compatibility); new executor-level pin for the
  non-fixture filename capability (main.py/data.csv flagged end-to-end).

## 2026-06-11 completion evidence

- `gradlew test e2eTest` green.
- ATE sheds ~180 lines of policy markers and helpers.
