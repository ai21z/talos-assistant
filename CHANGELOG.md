# Changelog

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
