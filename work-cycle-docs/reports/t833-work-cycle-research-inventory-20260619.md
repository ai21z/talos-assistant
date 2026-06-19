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
- `git check-ignore -v work-cycle-docs/research/external-review-wave6-deep-research-review-20260618.md work-cycle-docs/research/talos-trust-overclaim-audit-and-sources-20260616.md docs/README.md`
- pattern scan for host-local absolute paths, common token-shaped examples,
  fake/canary markers, and runtime-evidence terms.

Current counts:

- Research files present: 22.
- Tracked research files: 1.
- Ignored/untracked research files: 21.
- Ignored/untracked research bytes: 843,652.
- Locally ignored untracked `docs/` files: 23.

## Local Ignore Trap

The durable evidence trap is local, not repository-wide. `.git/info/exclude`
ignores both `/docs/` and `/work-cycle-docs/research/`. The tracked
`.gitignore` does not carry those rules.

Current impact:

- `work-cycle-docs/research/external-review-wave6-deep-research-review-20260618.md` is
  ignored and untracked, but `TrustClaimsHonestyTest` reads it directly.
- `work-cycle-docs/research/talos-trust-overclaim-audit-and-sources-20260616.md`
  is ignored and untracked, but the T833 report and T834-T838 tickets cite it as
  authoritative source context.
- Normal `git status` hides the ignored research files. `git clean -ndX` would
  remove all 21 ignored research files.
- The recursive public-pitch honesty test walks `docs/`; because local
  `.git/info/exclude` ignores `/docs/`, untracked local Markdown files can make
  that test environment-dependent even when committed docs are stable.

Do not edit `.git/info/exclude` in this pass. Owner decision needed: either
narrow the local ignore rules or force-add selected durable evidence files when
they become test or ticket dependencies.

## Inventory

| File | Bytes | Tracked / ignored | Referenced by test or tracked/current doc | Absolute local paths | Real secrets detected | Private runtime content | Recommendation |
| --- | ---: | --- | --- | --- | --- | --- | --- |
| `external assistant-independent review-5-talos-beta-readiness-review.md` | 32,124 | ignored/untracked | No | No | No detected | Possible live-audit/provenance terms | Leave local unless sanitized and explicitly promoted. |
| `external assistant-independent review-5-talos-onboarding-state-review.md` | 37,633 | ignored/untracked | No | Yes | No detected | Possible live-audit/provenance terms | Sanitize host paths before any promotion. |
| `codex-0.10.0-next-release-gates-audit.md` | 17,645 | ignored/untracked | No | Yes | No detected | Possible audit/transcript references | Sanitize before promotion; not needed for current test fix. |
| `context-retrieval-memory-best-techniques-from-reference-systems.md` | 21,239 | tracked | Yes | No | No detected | Minimal | Already tracked; no action. |
| `current-code-status-deep-audit-20260607.md` | 31,309 | ignored/untracked | No | No | No detected | Low provenance signal | Leave local unless owner wants historical audit context. |
| `independent review-0.10.0-beta-release-implementation-plan-for-codex.md` | 29,069 | ignored/untracked | Yes | No | No detected | Possible audit/runbook references | Owner-approved promotion only; not required now. |
| `independent review-0.10.0-candidate-status-and-next-moves-prompt.md` | 16,446 | ignored/untracked | No | Yes | No detected | Low provenance signal | Leave local or sanitize before promotion. |
| `independent review-0.10.0-candidate-status-and-next-moves-review.md` | 32,339 | ignored/untracked | No | No | No detected | Low provenance signal | Leave local unless owner wants historical candidate context. |
| `independent review-0.10.0-next-release-gates-review.md` | 20,598 | ignored/untracked | No | No | No detected | Possible gate/provenance terms | Leave local unless owner wants release-gate history. |
| `independent review-0.10.0-pty-manual-audit-findings.md` | 13,939 | ignored/untracked | Yes | No | No detected | Possible PTY/live-audit evidence | Sanitize/review before promotion. |
| `external-review-wave6-deep-research-review-20260618.md` | 4,110 | ignored/untracked | Yes; direct test dependency | No | No detected | No detected | Track as-is now with `git add -f`; this is the active clean-checkout break. |
| `t707-t709-t708-t710-batch-plan-review.md` | 14,337 | ignored/untracked | No | No | No detected | Minimal | Leave local. |
| `t708-hierarchical-project-memory-deep-analysis.md` | 34,097 | ignored/untracked | Yes | No | No detected | Possible provenance terms | Owner-approved promotion only. |
| `t708-t712-independent review-review-prompt.md` | 17,772 | ignored/untracked | No | No | No detected | Possible prompt/review content | Leave local. |
| `t708-t712-independent review-review.md` | 32,019 | ignored/untracked | Yes | No | No detected | Possible review evidence | Owner-approved promotion only. |
| `t708-t715-independent review-review-prompt.md` | 28,103 | ignored/untracked | No | No | No detected | Possible prompt/review content | Leave local. |
| `t708-t715-independent review-review.md` | 28,318 | ignored/untracked | Yes | No | No detected | Possible review evidence | Owner-approved promotion only. |
| `t709b-compaction-integrity-review.md` | 13,801 | ignored/untracked | No | No | No detected | Low provenance signal | Leave local. |
| `t711-corrections-and-followup-review.md` | 9,173 | ignored/untracked | No | No | No detected | Minimal | Leave local. |
| `talos-0.10.0-next-release-gates-dual-ai-prompt.md` | 22,030 | ignored/untracked | No | Yes | No detected | Possible live-audit/provenance terms | Sanitize before promotion. |
| `talos-top-tier-evaluation-and-roadmap-20260610.md` | 17,192 | ignored/untracked | Yes | Yes | No detected | Possible roadmap/provenance terms | Sanitize host path before owner-approved promotion. |
| `talos-trust-overclaim-audit-and-sources-20260616.md` | 391,598 | ignored/untracked | Yes | No | No real secret detected; contains fake/token-shaped audit examples | Possible trust-audit evidence; no raw provider body detected by scan | Leading promotion candidate after targeted sanitization/review. |

## Summary By Type

- Immediate tracked dependency needed for clean checkout: 1 file
  (`external-review-wave6-deep-research-review-20260618.md`).
- Source-of-truth research cited by active Wave 6 trust tickets: 1 large file
  (`talos-trust-overclaim-audit-and-sources-20260616.md`).
- Ignored files with host-local absolute paths: 6.
- Ignored files with token-like examples detected by this scan: 1, the trust
  overclaim audit. The matches are audit examples/fixture-shaped text, not
  detected live credentials.
- Ignored files with possible live-audit, transcript, prompt-debug, provider, or
  operator-provenance terms: most historical release/audit review files.

## Recommended Promotion Set

Promote now:

- `work-cycle-docs/research/external-review-wave6-deep-research-review-20260618.md`
  because a committed test reads it directly and the scan found no host paths,
  token-shaped examples, or private runtime-content signals.

Promote later after owner review:

- `work-cycle-docs/research/talos-trust-overclaim-audit-and-sources-20260616.md`
  because it is the Wave 6 trust source-of-truth cited by T833-T838. It is large
  and contains deliberate fake/token-shaped audit examples, so it should receive
  a targeted sanitization/review pass before remote publication.
- `work-cycle-docs/research/talos-top-tier-evaluation-and-roadmap-20260610.md`
  if the owner wants the Wave 5/6 strategic roadmap durable in remote; sanitize
  host-local path evidence first.
- Historical T708-T715 review files only if the owner wants old architecture
  review provenance remote-visible. They are not needed for the active T833
  clean-checkout fix.

Do not bulk-add the ignored research directory. The local ignore policy is
currently too broad for durable evidence, but some ignored files are still
scratchpad/provenance artifacts that need owner review before remote.
