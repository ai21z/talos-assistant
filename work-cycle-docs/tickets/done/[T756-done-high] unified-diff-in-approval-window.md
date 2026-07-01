# T756 - Unified Diff In The Approval Window

Status: done - completed in wave 2; see completion evidence section
Severity: high
Release gate: yes (trust-surface: informed consent at the approval gate)
Branch: feature/wave2-trust-surface
Created/updated: 2026-06-11
Owner: external assistant

## Problem

The flagship trust surface - the approval window - showed only a 60-char
escaped `replace:`/`with:` pair for edits and a 5-line head preview for
writes. AGENTS.md priority 9 demands "clear diffs before mutation where
practical" and every reference CLI ships colored unified diffs at consent
time (2026-06-10 evaluation, roadmap item W2.2).

## Architecture Metadata

Capability: approval-window mutation preview
Operation(s): write, edit
Owning package/class: `dev.talos.runtime.ApprovalDiffPreview` (CLI-neutral
diff builder), `dev.talos.runtime.TurnProcessor` (detail assembly + trace),
`dev.talos.cli.ui.ApprovalPromptRenderer` (color), `dev.talos.cli.approval.
CliApprovalGate` (risk-scope companion fix)
New or changed tools: none
Risk, approval, and protected paths: diff preview fail-closes - protected
path / oversized (>2 MiB) / binary / unreadable / outside-workspace →
skipped with machine-readable reason, never a partial diff; every rendered
line passes ProtectedContentPolicy.sanitizeText
Checkpoint, evidence, verification, and repair: n/a
Outcome and trace: new `APPROVAL_DIFF_PREVIEW` event (diffHash, added,
removed, diffLineCount, truncated, skippedReason - hash and counts only,
never diff text; DEFAULT redaction doctrine)
Refactor scope: the named files; no approval-flow restructuring

## Required Behavior

1. Write to existing file → unified diff (old vs new, 3 context lines);
   new file → counts-only "new file" note; identical content →
   "no changes" note. Edit → diff of the spliced unique old_string match.
2. Caps: 60 diff lines + "(N more diff lines)" marker; line length capped
   at 70 so the 4-space-indented diff stays under the renderer's 74-column
   wrap threshold (width-80 prompt) - wrap() word-wrap collapses
   whitespace and would shear diff indentation.
3. Strictly additive detail: every legacy line byte-identical; the diff
   block is the final detail section. PTY validator pins only the
   "Allow? [...]" prompt string - unchanged.
4. Color renderer-owned: +/green, -/red, @@ and marker/gray via CliTheme;
   ColorPolicy.NEVER yields byte-identical plain ASCII (sgr returns "").
   Colorization scoped to the diff block so "- item" prose lines are never
   colorized; block stays open once entered (context lines are
   indistinguishable from prose after strip, and the block is structurally
   last).
5. Companion fix: `CliApprovalGate.inferRisk` scans only the detail before
   the "diff (+" marker - diff bodies quoting "remove"/"delete" in user
   code must not flip the risk label to "destructive".

## Dependency

`io.github.java-diff-utils:java-diff-utils:4.12` - pure Java, zero
transitive runtime dependencies (verified via Gradle dependency tree),
Apache-2.0. Version pinned in gradle.properties (`javaDiffUtilsVersion`).

## Tests / Evidence

- `ApprovalDiffPreviewTest` (15 tests): diff shape + context, determinism,
  new-file/no-changes notes, CRLF normalization, edit splice +
  not-found/ambiguous/missing skips, line-count cap + marker, line-length
  cap, secret-assignment redaction, protected-path / outside-workspace /
  binary / oversized / blank-path fail-closed skips.
- `TurnProcessorTest`: legacy detail block byte-identical and contiguous
  with diff appended after (write + edit), counts-only new-file note,
  protected-path detail has no diff block, APPROVAL_DIFF_PREVIEW trace
  fields (hash prefix, counts, no raw text).
- `ApprovalPromptRendererTest`: diff lines colorized under
  ColorPolicy.ALWAYS; byte-identical plain under NEVER; pre-marker "- item"
  lines never colorized; cap-length diff lines survive wrap intact;
  existing chrome assertions untouched.
- `CliApprovalGateTest`: risk label stays "write" when the diff body
  contains "remove"/"delete".
- E2E scenario 88 extended: approval detail contains "diff (+".

## Known Follow-Ups

- One manual Windows-Terminal PTY visual cycle at wave closeout (approval
  window grew; chrome strings unchanged - cheap insurance, owner step).
- FULL_DEBUG trace mode could carry the diff text; deliberately not built
  (mode exists but is unused).

## 2026-06-11 completion evidence

- `gradlew test e2eTest` green (4,845 unit tests; scenario pack green
  including the extended scenario 88).
- java-diff-utils:4.12 resolves with zero transitive runtime dependencies.
