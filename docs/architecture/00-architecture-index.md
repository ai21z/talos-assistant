# Talos Architecture Index

Status: active architecture index

Last refreshed: 2026-05-30

Branch reviewed: `feature/archunit-architecture-guards`

## Purpose

`docs/architecture` is the single architecture documentation directory.

The former `docs/new-architecture` directory mixed current design material,
historical harness plans, cleanup backlogs, and audit notes. That split made the
repository look like it had two competing architecture sources. The content has
been folded into this directory, and references should use `docs/architecture`.

## Read First

These are the highest-signal architecture findings on this branch:

| File | Status | Why it matters |
| --- | --- | --- |
| `14-current-architecture-design-review.md` | Current branch review | Deep current-state architecture review: package map, hotspots, target architecture, roadmap, guardrail recommendations. |
| `15-technology-modernization-and-dependency-strategy.md` | Current branch review | Technology and dependency decisions tied back to review 14. |
| `11-architecture-guardrails.md` | Active guardrail doc | Explains the ArchUnit and architecture-boundary guard posture for this branch. |
| `12-current-architecture-risk-report.md` | Current risk report | Shorter evidence-backed risk view for the architecture branch. |
| `13-external-architecture-visualization-plan.md` | Supporting review plan | Human-run visualization plan for package and dependency inspection. |

## Foundational Design Docs

These are still relevant as design context, but some details may be superseded by
the current reviews above:

| File | Subject |
| --- | --- |
| `01-execution-discipline-and-local-trust.md` | Execution discipline and local trust doctrine. |
| `02-runtime-policy-ownership-map.md` | Runtime policy ownership map. |
| `03-local-turn-trace-model-v1.md` | Local turn trace model. |
| `04-declarative-allow-ask-deny-permissions.md` | Permission model design. |
| `05-local-checkpoint-restore.md` | Local checkpoint/restore design. |
| `06-bounded-repair-controller.md` | Bounded repair controller design. |
| `07-domain-specificity-and-extensibility-audit.md` | Domain specificity and extensibility audit. |
| `08-capability-growth-guardrails.md` | Capability growth guardrails. |
| `09-java-25-migration-readiness.md` | Java migration readiness spike. |
| `10-command-execution-architecture-design.md` | Command execution architecture design. |

## Folded-In Architecture Docs

These files were previously under `docs/new-architecture`. They now live here to
avoid split-brain architecture ownership.

| File | Current reading |
| --- | --- |
| `talos-harness-main-plan.md` | Most current harness roadmap among the harness-plan documents; keep as the primary harness plan snapshot. |
| `talos-harness-plan.md` | Older rollout plan; useful historical source, not the first current roadmap. |
| `talos-harness-source-of-truth.md` | Older independent review/source-pack framing; useful context, not a current branch truth packet. |
| `23-embedding-provider-architecture.md` | Current bounded embedding-provider and RAG-vector reference. |
| `25-xml-retirement-review.md` | XML tool-call retirement review and migration analysis. |
| `26-pre-harness-prerequisites.md` | Historical pre-harness prerequisite checklist; verify against current code before treating any open item as still open. |
| `27-codebase-cleanup-and-refactor-overview.md` | Cleanup/refactor overview from the v0.9.0 beta cleanup stream. |
| `28-codebase-cleanup-ticket-backlog.md` | Cleanup ticket ledger and follow-up backlog. |
| `29-v1-scenario-pack.md` | Scenario pack design. |
| `30-cli-ui-output-architecture-audit.md` | CLI UI output architecture audit. |
| `31-inline-tui-strategy-and-fullscreen-rejection.md` | ADR: inline JLine rendering accepted, full-screen TUI rejected (Wave 3); locks in the byte-frozen chrome, degradation, and single-writer rules. |

## Current Cleanup Decision

- Keep one directory: `docs/architecture`.
- Removed `docs/new-architecture` after moving its retained files.
- Preserve historical docs when they still explain why earlier cleanup and harness
  decisions happened.
- Treat `14-current-architecture-design-review.md` and
  `15-technology-modernization-and-dependency-strategy.md` as the latest broad
  architecture findings for this branch.
- Do not treat old branch labels inside historical files as current evidence
  without re-checking the code and git state.
