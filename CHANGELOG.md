# Changelog

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
