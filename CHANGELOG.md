# Changelog

## [Unreleased]

### Changed
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
