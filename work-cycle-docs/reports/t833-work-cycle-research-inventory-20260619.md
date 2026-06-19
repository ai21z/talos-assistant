# T833 Work-Cycle Research Inventory (2026-06-19)

Status: inventory for owner decision
Branch: `v0.9.0-beta-dev`
Talos version: `0.10.5`

## Purpose

This inventory records the current `work-cycle-docs/research/` evidence state
after the Wave 6 trust-surface disclosure pass. It fixes the immediate
evidence-management question without bulk-promoting locally ignored research.

This report intentionally does not reproduce secret-shaped strings, full local
host paths, raw provider bodies, or transcript payloads. The scan results below
are classification signals, not a substitute for a dedicated secret-forensics
review.

## Method

Commands and checks used:

- `git ls-files work-cycle-docs/research`
- `git status --ignored --short -- work-cycle-docs/research`
- `git clean -ndX work-cycle-docs/research`
- `git check-ignore -v work-cycle-docs/research/opus-wave6-deep-research-review-20260618.md work-cycle-docs/research/talos-trust-overclaim-audit-and-sources-20260616.md docs/README.md`
- pattern scan for host-local absolute paths, common token-shaped examples,
  fake/canary markers, and runtime-evidence terms.

Current counts:

- Research files present: 22.
- Tracked research files: 2.
- Ignored/untracked research files: 20.
- Ignored/untracked research bytes: 839,542.
- Locally ignored untracked `docs/` files: 23.

## Local Ignore Trap

The durable evidence trap is local, not repository-wide. `.git/info/exclude`
ignores both `/docs/` and `/work-cycle-docs/research/`. The tracked
`.gitignore` does not carry those rules.

Current impact:

- `work-cycle-docs/research/opus-wave6-deep-research-review-20260618.md` is
  tracked despite the local ignore rule because `TrustClaimsHonestyTest` reads it
  directly.
- `work-cycle-docs/research/talos-trust-overclaim-audit-and-sources-20260616.md`
  is ignored and untracked. Active T833-T838 records now cite the tracked
  sanitized derivative instead:
  `work-cycle-docs/reports/wave6-trust-overclaim-sanitized-evidence-20260619.md`.
- Normal `git status` hides the ignored research files. `git clean -ndX` would
  remove all 20 ignored research files.
- The recursive public-pitch honesty test walks `docs/`; because local
  `.git/info/exclude` ignores `/docs/`, untracked local Markdown files can make
  that test environment-dependent even when committed docs are stable.

Do not edit `.git/info/exclude` in this pass. Owner decision needed: either
narrow the local ignore rules or force-add selected durable evidence files when
they become test or ticket dependencies.

## Inventory

| File | Bytes | Tracked / ignored | Referenced by test or tracked/current doc | Absolute local paths | Real secrets detected | Private runtime content | Recommendation |
| --- | ---: | --- | --- | --- | --- | --- | --- |
| `claude-fable-5-talos-beta-readiness-review.md` | 32,124 | ignored/untracked | No | No | No detected | Possible live-audit/provenance terms | Leave local unless sanitized and explicitly promoted. |
| `claude-fable-5-talos-onboarding-state-review.md` | 37,633 | ignored/untracked | No | Yes | No detected | Possible live-audit/provenance terms | Sanitize host paths before any promotion. |
| `codex-0.10.0-next-release-gates-audit.md` | 17,645 | ignored/untracked | No | Yes | No detected | Possible audit/transcript references | Sanitize before promotion; not needed for current test fix. |
| `context-retrieval-memory-best-techniques-from-reference-systems.md` | 21,239 | tracked | Yes | No | No detected | Minimal | Already tracked; no action. |
| `current-code-status-deep-audit-20260607.md` | 31,309 | ignored/untracked | No | No | No detected | Low provenance signal | Leave local unless owner wants historical audit context. |
| `opus-0.10.0-beta-release-implementation-plan-for-codex.md` | 29,069 | ignored/untracked | Yes | No | No detected | Possible audit/runbook references | Owner-approved promotion only; not required now. |
| `opus-0.10.0-candidate-status-and-next-moves-prompt.md` | 16,446 | ignored/untracked | No | Yes | No detected | Low provenance signal | Leave local or sanitize before promotion. |
| `opus-0.10.0-candidate-status-and-next-moves-review.md` | 32,339 | ignored/untracked | No | No | No detected | Low provenance signal | Leave local unless owner wants historical candidate context. |
| `opus-0.10.0-next-release-gates-review.md` | 20,598 | ignored/untracked | No | No | No detected | Possible gate/provenance terms | Leave local unless owner wants release-gate history. |
| `opus-0.10.0-pty-manual-audit-findings.md` | 13,939 | ignored/untracked | Yes | No | No detected | Possible PTY/live-audit evidence | Sanitize/review before promotion. |
| `opus-wave6-deep-research-review-20260618.md` | 4,110 | tracked despite local ignore | Yes; direct test dependency | No | No detected | No detected | Already tracked; no action. |
| `t707-t709-t708-t710-batch-plan-review.md` | 14,337 | ignored/untracked | No | No | No detected | Minimal | Leave local. |
| `t708-hierarchical-project-memory-deep-analysis.md` | 34,097 | ignored/untracked | Yes | No | No detected | Possible provenance terms | Owner-approved promotion only. |
| `t708-t712-opus-review-prompt.md` | 17,772 | ignored/untracked | No | No | No detected | Possible prompt/review content | Leave local. |
| `t708-t712-opus-review.md` | 32,019 | ignored/untracked | Yes | No | No detected | Possible review evidence | Owner-approved promotion only. |
| `t708-t715-opus-review-prompt.md` | 28,103 | ignored/untracked | No | No | No detected | Possible prompt/review content | Leave local. |
| `t708-t715-opus-review.md` | 28,318 | ignored/untracked | Yes | No | No detected | Possible review evidence | Owner-approved promotion only. |
| `t709b-compaction-integrity-review.md` | 13,801 | ignored/untracked | No | No | No detected | Low provenance signal | Leave local. |
| `t711-corrections-and-followup-review.md` | 9,173 | ignored/untracked | No | No | No detected | Minimal | Leave local. |
| `talos-0.10.0-next-release-gates-dual-ai-prompt.md` | 22,030 | ignored/untracked | No | Yes | No detected | Possible live-audit/provenance terms | Sanitize before promotion. |
| `talos-top-tier-evaluation-and-roadmap-20260610.md` | 17,192 | ignored/untracked | Yes | Yes | No detected | Possible roadmap/provenance terms | Sanitize host path before owner-approved promotion. |
| `talos-trust-overclaim-audit-and-sources-20260616.md` | 391,598 | ignored/untracked | Yes | No | No real secret detected; contains fake/token-shaped audit examples | Possible trust-audit evidence; no raw provider body detected by scan | Keep local; active tickets should use the sanitized tracked derivative instead. |

## Summary By Type

- Immediate tracked dependency needed for clean checkout: 1 file already promoted
  (`opus-wave6-deep-research-review-20260618.md`).
- Raw research underlying active Wave 6 trust tickets: 1 large local file
  (`talos-trust-overclaim-audit-and-sources-20260616.md`), now represented for
  tracked review by
  `work-cycle-docs/reports/wave6-trust-overclaim-sanitized-evidence-20260619.md`.
- Ignored files with host-local absolute paths: 6.
- Ignored files with token-like examples detected by this scan: 1, the trust
  overclaim audit. The matches are audit examples/fixture-shaped text, not
  detected live credentials.
- Ignored files with possible live-audit, transcript, prompt-debug, provider, or
  operator-provenance terms: most historical release/audit review files.

## Recommended Promotion Set

Already promoted:

- `work-cycle-docs/research/opus-wave6-deep-research-review-20260618.md`
  because a committed test reads it directly and the scan found no host paths,
  token-shaped examples, or private runtime-content signals.

Promote now:

- `work-cycle-docs/reports/wave6-trust-overclaim-sanitized-evidence-20260619.md`
  as the remote-safe Wave 6 trust evidence record. It keeps the IDs, severity,
  ticket mapping, fix direction, and honest disclosure themes needed for durable
  tracking without promoting exploit walkthroughs or local operational
  provenance.

Keep local unless separately rewritten:

- `work-cycle-docs/research/talos-trust-overclaim-audit-and-sources-20260616.md`
  because it is large and contains deliberate fake/token-shaped audit examples
  plus exploit-oriented walkthrough detail. The sanitized derivative is the
  tracked evidence artifact for active Wave 6 work.

Promote later after owner review:

- `work-cycle-docs/research/talos-top-tier-evaluation-and-roadmap-20260610.md`
  if the owner wants the Wave 5/6 strategic roadmap durable in remote; sanitize
  host-local path evidence first.
- Historical T708-T715 review files only if the owner wants old architecture
  review provenance remote-visible. They are not needed for the active T833
  clean-checkout fix.

Do not bulk-add the ignored research directory. The local ignore policy is
currently too broad for durable evidence, but some ignored files are still
scratchpad/provenance artifacts that need owner review before remote.
