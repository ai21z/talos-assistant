# Changelog

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
