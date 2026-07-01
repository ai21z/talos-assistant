# T808 - Living Evidence Wiki Discipline

Status: done
Severity: high
Release gate: no - pre-Wave-5 shared-knowledge discipline
Branch: v0.9.0-beta-dev
Created/updated: 2026-06-13
Owner: unassigned

## Problem

Talos now has stronger generated architecture intelligence reports, but shared
engineering memory is still split across tickets, runbooks, release packets,
generated reports, and chat context. That makes future Wave 5 work vulnerable
to stale assumptions and repeated rediscovery.

Talos needs a small committed wiki spine that synthesizes source-backed
conclusions for humans and LLM collaborators without replacing policy,
runbooks, release ledgers, or generated machine evidence.

## Required Behavior

1. Add `work-cycle-docs/wiki/` with a minimal committed spine:
   - `INDEX.md`
   - `CURRENT-STATE.md`
   - `WIKI-SCHEMA.md`
   - `LOG.md`
   - `concepts/living-evidence-wiki.md`
2. Use `INDEX.md` and `CURRENT-STATE.md` as the predictable LLM-readable entry
   points.
3. Add deterministic structural lint:
   `.\gradlew.bat wikiLintStructural --no-daemon`.
4. Require explicit frontmatter metadata for wiki pages, including evidence
   inputs and confidence labels.
5. Keep wiki updates reviewed with the ticket that caused them.

## Non-Goals

- No Wave 5 refactor.
- No Talos runtime, CLI, product API, or project-memory behavior changes.
- No automatic loading of the wiki into Talos memory.
- No `llms.txt` file in T808.
- No Obsidian-specific `[[wikilink]]` convention.
- No evidence-liveness lint that checks cited generated report values.
- No CodeQL, JFR, Error Prone, NullAway, JSpecify, or Qodana work.
- No autonomous background wiki mutation.

## Architecture Metadata

- Capability: living evidence wiki discipline for shared Talos engineering
  memory.
- Operation(s): committed Markdown synthesis plus repo-local structural lint.
- Owning package/class: `src/test/java/dev/talos/wiki`.
- New or changed tools: new Gradle verification task `wikiLintStructural`.
- Risk, approval, and protected paths: no runtime workspace mutation; the wiki
  records evidence links and confidence but does not execute tooling.
- Evidence boundary: generated `build/` reports remain machine evidence; wiki
  pages are source-backed synthesis.
- Refactor scope: documentation spine, structural lint test, Gradle task, and
  this ticket only.

## Acceptance Criteria

- `.\gradlew.bat wikiLintStructural --no-daemon` passes.
- Required wiki files exist under `work-cycle-docs/wiki/`.
- Every non-log wiki page has frontmatter with schema
  `talos.wikiPage.v1`.
- `CURRENT-STATE.md` and concept pages have non-empty evidence inputs.
- `min_confidence` uses only:
  `UNKNOWN`, `INFERRED_REVIEW`, `DETERMINISTIC_STATIC`,
  `DETERMINISTIC_GENERATED`, `OBSERVED_RUNTIME`, or `GATED`.
- Local Markdown links resolve.
- Every non-index wiki page is listed in `INDEX.md`.
- `CURRENT-STATE.md` records branch, commit, version, active tickets, active
  wave context, known caveats, and next move.
- `LOG.md` is maintained append-only by convention.

## Verification

```powershell
.\gradlew.bat wikiLintStructural --no-daemon
git diff --check
git status --short
```

## Deferred Work

- `wikiLintWithEvidence` and exact claim-selector liveness checks.
- Structured machine-checkable claim blocks.
- Search/query tooling such as `qmd`, embeddings, or MCP.
- Obsidian graph conventions.
- Runtime loading of wiki pages into Talos project memory.
- `llms.txt` as a possible future entry-point experiment.

## Completion State

Completed after owner review. The committed wiki spine exists under
`work-cycle-docs/wiki/`, uses `INDEX.md` and `CURRENT-STATE.md` as entry
points, and is covered by deterministic structural lint.

Completion evidence:

- `.\gradlew.bat wikiLintStructural --no-daemon` is covered by
  `.\gradlew.bat wikiEvidenceCloseGate --no-daemon`.
- `.\gradlew.bat check --no-daemon` passed after T807-T810 merge.
- Required wiki pages exist and are indexed.
