# T334 - Changelog And Beta Versioning Discipline

Status: done - release-ledger validation and beta versioning discipline added
Severity: high / release-evidence integrity
Release gate: yes for candidate packets and beta release claims
Branch: v0.9.0-beta-dev
Created/updated: 2026-05-21
Owner: unassigned

## Problem

`CHANGELOG.md` is no longer a reliable summary of the current beta candidate
line.

Current repository evidence:

- `gradle.properties` declares `talosVersion=0.9.9`.
- `CHANGELOG.md` starts with `## [0.9.9] - 2026-05-15`.
- Many beta stabilization, audit-evidence, verification, privacy, static-web,
  office-document, prompt-surface, and terminal/UI commits have landed after
  that changelog entry, through `c32957e9` on 2026-05-21.
- `scripts/bump-patch.ps1` only supports numeric `major.minor.patch` versions
  and inserts a `pending release notes` stub.
- The work-test runbooks require version and changelog declaration before
  candidate evidence is collected.

This creates a release-evidence problem: a future audit packet can claim one
candidate version while the changelog omits material changes that are already
part of that version line.

## Best-Practice Decision

Do not downsize, reset, or reuse already-published candidate versions.

Talos should keep monotonically increasing version identity for every candidate
or distributed artifact. Once a version has been built, pushed, tagged,
published, or referenced by audit evidence, the project should not make a lower
or reused number represent a newer state.

For the current beta line, either of these is acceptable:

- Continue numeric pre-1.0 patch candidates, for example `0.9.10`,
  `0.9.11`, and so on.
- Move to the next pre-1.0 beta milestone, for example `0.10.0`, when the
  next batch is broad enough to deserve a milestone boundary.

The stronger recommendation is:

- Use `0.9.10` for the next narrow candidate after `0.9.9`.
- Use `0.10.0` if the next candidate is the planned hygiene/architecture
  milestone rather than a small stabilization patch.
- Reserve `1.0.0` for the first stable release where the public product
  contract, CLI behavior, audit discipline, release packet, and user-facing
  claims are intentionally declared stable.

Patch numbers above 9 are normal. `0.9.10` is greater than `0.9.9`; it is not a
format problem.

## External References

- Semantic Versioning 2.0.0 requires normal versions to be `X.Y.Z`, with
  numeric components increasing numerically, and says released version contents
  must not be modified after release. It also defines `0.y.z` as initial
  development where the public API should not be considered stable:
  https://semver.org/
- Keep a Changelog recommends one entry for every version, latest first,
  release dates, grouped change types, and an `Unreleased` section at the top
  that is moved into a version section at release time:
  https://keepachangelog.com/en/1.1.0/
- GitHub releases support release notes, draft releases, attached artifacts,
  prerelease marking for unstable builds, and semantic-version-based latest
  release selection:
  https://docs.github.com/en/repositories/releasing-projects-on-github/managing-releases-in-a-repository
- Calendar Versioning is a separate valid scheme when calendar/date identity is
  the intended release signal, but Talos already has SemVer-shaped candidate
  tooling and evidence. Switching to CalVer is out of scope for this ticket:
  https://calver.org/

## Required Behavior

- `CHANGELOG.md` has a top `## [Unreleased]` section for changes since the
  last declared candidate.
- Candidate closeout moves the relevant `Unreleased` notes into a dated version
  section or otherwise proves the dated version entry was updated with all
  material changes.
- No candidate packet may contain `pending release notes`.
- The top released changelog version must match `talosVersion` for a declared
  candidate.
- Version numbers are monotonically increasing. Do not downsize from `0.9.9`
  to a lower or reused beta version.
- Stable release tags may use `v1.0.0`, but the SemVer version value is
  `1.0.0`.
- Pre-release strings such as `1.0.0-beta.1` are not introduced in this ticket
  unless the Gradle/script/report tooling is intentionally updated to support
  non-numeric versions.

## Proposed Implementation

1. Add `## [Unreleased]` above the latest released section in `CHANGELOG.md`.
2. Backfill concise, user-relevant and release-evidence-relevant notes for
   post-`0.9.9` work since 2026-05-15. Group by impact, not by every commit.
3. Update `scripts/bump-patch.ps1` so candidate declaration either:
   - moves the current `Unreleased` section into the new version section, then
     creates a fresh empty `Unreleased` section; or
   - fails if `Unreleased` contains material notes that were not incorporated.
4. Add a guard that fails if the generated changelog still contains
   `pending release notes` when candidate evidence tasks are run.
5. Update the work-test runbooks with the beta versioning rule:
   - no downsizing;
   - numeric `0.x.y` beta versions remain valid;
   - move to `0.10.0` for a broad beta milestone;
   - reserve `1.0.0` for stable beta exit.
6. Add focused script tests or a documented PowerShell self-test for changelog
   section movement and stale-stub rejection.

## Acceptance Criteria

- `CHANGELOG.md` has an `Unreleased` section at the top.
- The current post-`0.9.9` stabilization work is represented in
  `Unreleased` or in a newly declared candidate version entry.
- No active candidate evidence path accepts `pending release notes`.
- `scripts/bump-patch.ps1` preserves monotonic numeric versioning and handles
  the `Unreleased` workflow deterministically.
- Work-test docs explicitly reject downsizing/reusing candidate versions after
  evidence exists.
- Candidate packet review checks record:
  - branch;
  - commit SHA;
  - candidate version from `gradle.properties`;
  - top released changelog version;
  - whether the changelog contains unresolved placeholder text.

## Non-Goals

- Do not rewrite historical released changelog entries except to correct
  factual errors with explicit provenance.
- Do not rename the branch.
- Do not bump the version as part of this ticket unless this ticket becomes the
  candidate closeout ticket.
- Do not switch Talos to CalVer in this ticket.
- Do not introduce SemVer prerelease strings until the Gradle, script, summary,
  and release packet tooling accept them deliberately.

## Regression Tests

Suggested tests:

- A script-level test with a changelog containing `Unreleased` notes verifies
  that a bump creates the next numeric version section and preserves a fresh
  empty `Unreleased` section.
- A script-level test verifies that `0.9.9` bumps to `0.9.10`, not `0.10.0`,
  unless an explicit milestone bump mode is added later.
- A release-packet validation test fails when `CHANGELOG.md` contains
  `pending release notes`.
- A release-packet validation test fails when the top released changelog
  version does not match `talosVersion`.

## Implementation Notes

Implemented:

- Added a top `Unreleased` section to `CHANGELOG.md` and backfilled the
  post-`0.9.9` beta stabilization ledger.
- Updated `scripts/bump-patch.ps1` so it fails closed unless `CHANGELOG.md`
  has material `Unreleased` notes, moves those notes into the next numeric
  patch version, creates a fresh empty `Unreleased` section, and never emits
  `pending release notes`.
- Added `validateReleaseLedger` to `build.gradle.kts` and wired it into
  `check`.
- Added script regression tests for the numeric `0.9.9` to `0.9.10` bump,
  missing `Unreleased`, and empty `Unreleased` cases.
- Added Gradle validation tests for matching top released version,
  placeholder rejection, stale top released version rejection, and missing
  `Unreleased` rejection.
- Updated the work-test runbooks with the no-downsize, numeric beta, and
  `Unreleased`-before-bump workflow.
- Reconciled the site public-install copy with both install contracts: exact
  `winget install --id TalosProject.TalosCLI -e` command and `talos-cli`
  searchable moniker copy remain visible.

## Verification Log

TDD red run:

```powershell
.\gradlew.bat test --tests "dev.talos.scripts.BumpPatchScriptTest" --tests "dev.talos.build.ReleaseLedgerValidationTaskTest" --no-daemon
```

Result: failed before implementation, as expected. The existing bump script
still generated `pending release notes`, did not require `Unreleased`, and
there was no `validateReleaseLedger` task.

Focused green runs:

```powershell
.\gradlew.bat validateReleaseLedger --no-daemon
.\gradlew.bat test --tests "dev.talos.scripts.BumpPatchScriptTest" --tests "dev.talos.build.ReleaseLedgerValidationTaskTest" --no-daemon
```

Result: passed.

Full hard gate:

```powershell
.\gradlew.bat check --no-daemon
```

First result after the core change: failed in
`PublicInstallPackagingContractTest` because `site/index.html` still showed
only the friendly `winget install talos-cli` copy, not the exact winget package
ID command required by the public install contract.

Fix verification:

```powershell
.\gradlew.bat test --tests "dev.talos.release.PublicInstallPackagingContractTest" --no-daemon
npm test --prefix site
npm run build --prefix site
npm run test:e2e --prefix site
.\gradlew.bat check --no-daemon
```

Result: passed. The final `check` run included `validateReleaseLedger`,
unit tests, deterministic E2E, JaCoCo coverage verification, and generated
artifact canaries.

## Release Gate Impact

This is not a runtime safety bug, but it is a beta release gate issue. A
candidate with stale or placeholder changelog notes has weak provenance and
should not be called a clean release-evidence packet.
