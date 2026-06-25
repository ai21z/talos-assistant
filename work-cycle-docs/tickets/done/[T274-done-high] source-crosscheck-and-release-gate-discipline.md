# T274 - Source-Crosscheck and Release-Gate Discipline

Status: done
Severity: high
Release gate: yes for security/privacy/harness changes
Branch: v0.9.0-beta-dev
Created/updated: 2026-05-15
Owner: unassigned

## Problem

Talos needs release-gate discipline: sensitive harness decisions must be source-grounded, evidence-backed, tested, and ticketed. Narrative audits are not enough.

## Evidence from current code

The current T267 work uncovered runtime/tool/artifact gaps that were visible only by combining static code review with live transcript/provider-body evidence.

## Evidence from external/source crosscheck

OpenAI Codex and Gemini both document explicit security modes, approval/policy layers, and tool execution flows. Agent-design sources show tool results return to the model, making traces/artifacts important but sensitive.

## User impact

Without disciplined gates, users receive overconfident claims instead of tested trust boundaries.

## Product risk

Talos could ship as "local-first private assistant" while indirect tools, unsupported formats, or artifacts still fail core trust requirements.

## Runtime boundary affected

Release process, audit artifacts, deterministic tests, ticket discipline.

## Non-goals

- Blindly copying Codex/Gemini/external assistant designs.
- Prompt-only fixes.

## Required behavior

- Source crosscheck before sensitive runtime/security implementation.
- Comparison matrix before release-gate decisions.
- Every finding becomes a deterministic test or ticket.
- Release-gate report states what is not ready.

## Proposed implementation

Keep `t267-source-crosscheck.md`, create `source-comparison-matrix.md`, update T267-T274 tickets, and require release-gate reports for similar work.

## Tests

Process/document review plus existence checks for required reports/tickets.

## Acceptance criteria

- Source crosscheck exists.
- Comparison matrix exists.
- Release-gate report exists.
- T267-T274 tickets exist.

## Rollback / migration notes

None.

## Open questions

- Should CI validate the presence of release-gate reports for tickets tagged release gate?

## Related files

- `work-cycle-docs/reports/t267-source-crosscheck.md`
- `work-cycle-docs/reports/source-comparison-matrix.md`
- `work-cycle-docs/reports/t267-and-file-format-release-gate.md`

## 2026-05-15 hardening update

Completed:

- Re-checked official OpenAI Codex approval/sandbox/config sources.
- Re-checked official Gemini CLI sandbox, policy-engine, and tool docs.
- Searched the repo for `alex000kim-article.txt`, `local coding assistant Source Leak`, `KAIROS`, `bashSecurity`, and `promptCacheBreakDetection`.
- Confirmed `alex000kim-article.txt` is absent from this workspace and must not be claimed as inspected.

Still open:

- If project policy requires that article, add it explicitly to project sources or remove it from required-source lists.
- Consider CI/report existence checks for future release-gate tickets.

## Closeout - 2026-06-25 (main-merge backlog triage)

Closed as process discipline now codified in the work-cycle docs, ticket hygiene, and the candidate-cut process.

Closed by independent review as part of the v0.9.0-beta-dev -> main merge preparation (owner + Codex triage: close open tickets not on the current main-merge line). No deferred implementation is claimed; remaining work, if pursued, is re-opened as a new ticket for the relevant milestone.
