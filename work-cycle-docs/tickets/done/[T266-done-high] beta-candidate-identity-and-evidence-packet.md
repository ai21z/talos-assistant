# T266 - Beta Candidate Identity And Evidence Packet

Date: 2026-05-15
Status: done
Priority: high

## Why This Ticket Exists

The current branch has post-0.9.8 runtime hardening, TalosBench updates, managed
model setup work, and the new site work, but the reviewable candidate identity
is stale:

- `gradle.properties` still declares `talosVersion=0.9.8`.
- `CHANGELOG.md` does not yet describe the T251-T265 and site changes as one
  named candidate.
- `build/reports/talos/` is absent at ticket start, so there is no current
  machine-readable candidate packet.

Running a full live audit before declaring the candidate would create weak
provenance: useful transcript output, but no fresh named candidate packet to
compare against future runs.

## Goal

Declare the next beta candidate and produce a reviewable evidence packet before
the next full live audit decision.

## Scope

In scope:

- Create the T266 ticket under the normal work-test cycle.
- Run pre-candidate readiness checks before version declaration.
- Bump the patch version with `scripts/bump-patch.ps1`.
- Replace the generated changelog stub with concrete release notes for:
  - T251 managed model setup and config diagnostics.
  - T252-T265 runtime, command, workspace-operation, source-target, and
    TalosBench hardening.
  - The Talos landing page and site verification lane.
- Build the named candidate artifact.
- Run the mandatory post-bump `./gradlew.bat check`.
- Run the site verification lane.
- Run local Qodana evidence through the available local path and generate Talos
  quality summaries.
- Inspect `build/reports/talos/*.json`.

Out of scope:

- Runtime behavior changes.
- Site design changes.
- Full T61-style live audit.
- Merging to `main`.
- Wiring real beta download artifacts.

## Acceptance

- `gradle.properties` has the next numeric patch version.
- `CHANGELOG.md` top entry describes the candidate in concrete ticket-linked
  terms and does not leave `pending release notes`.
- Candidate jar exists for the declared version.
- `./gradlew.bat check` passes after the version/changelog declaration.
- Site build/static/e2e checks pass after the candidate declaration.
- Candidate summaries exist:
  - `build/reports/talos/version-summary.json`
  - `build/reports/talos/coverage-summary.json`
  - `build/reports/talos/e2e-summary.json`
  - `build/reports/talos/qodana-summary.json`
- The qodana summary explicitly records whether the local static-analysis
  evidence matches the current branch and revision.
- The ticket is moved to done only after the above evidence is reviewed.

## Implementation Plan

1. Confirm clean git state and local tool availability.
2. Run pre-candidate `./gradlew.bat check`.
3. Run `./scripts/bump-patch.ps1`.
4. Edit `CHANGELOG.md` with concrete release notes.
5. Build the candidate with `./gradlew.bat jar` and `./gradlew.bat installDist`.
6. Run mandatory post-bump `./gradlew.bat check`.
7. Run site checks from `site/`:
   - `npm ci`
   - `npm run build`
   - `npm test`
   - `npm run test:e2e`
   - verify no `.map` files under `site/dist`.
8. Run Qodana:
   - prefer `./gradlew.bat qodanaLocal` if Docker is available;
   - use `./gradlew.bat qodanaNativeFreshLocal` if Docker is unavailable.
9. Run `./gradlew.bat talosQualitySummaries`.
10. Inspect the generated summary JSON files.
11. Move this ticket to `done/` with final evidence.

## Verification Log

Preflight:

- `java -version`: OpenJDK 21.0.9.
- `./gradlew.bat --version`: Gradle 8.14.
- `docker version`: Docker CLI present, but Docker Desktop daemon unavailable.
- `qodana --version`: Qodana CLI 2025.3.2 available.
- `git status --short --branch`: clean at ticket start.

Candidate declaration:

- `./gradlew.bat check`: passed before version declaration as a pre-candidate
  readiness check; all tasks were up-to-date.
- `./scripts/bump-patch.ps1`: bumped Talos patch version to `0.9.9` and added
  the changelog entry dated 2026-05-15.
- `CHANGELOG.md`: generated stub replaced with concrete release notes for the
  post-0.9.8 beta hardening, T251-T265, site work, and this T266 candidate
  packet.

Candidate artifact and hard local gate:

- `./gradlew.bat jar`: passed.
- `./gradlew.bat installDist`: passed and rebuilt the candidate distribution.
- `./gradlew.bat check`: passed after the version/changelog declaration.
  Gradle executed unit tests, deterministic E2E tests, JaCoCo report, and
  coverage verification for the named 0.9.9 candidate.

Site lane:

- First `npm ci` failed with Windows `EPERM` while unlinking Rollup's native
  `.node` file. Root cause was an existing Vite dev-server process under
  `site/` holding `node_modules` files. The stale site dev-server processes
  were stopped and the lane was rerun.
- `npm ci`: passed after clearing the stale dev-server lock; npm reported 0
  vulnerabilities.
- `npm run build`: passed.
- `npm test`: passed, 11/11 static tests.
- `npm run test:e2e`: passed, 13/13 Playwright tests.
- `Get-ChildItem -Path dist -Recurse -Filter *.map`: returned no files.

Static analysis and summaries:

- `./gradlew.bat qodanaNativeFreshLocal`: passed through the native fallback
  path because Docker was unavailable. Qodana reported 76 high findings and 0
  critical findings.
- `./gradlew.bat talosQualitySummaries`: passed and wrote:
  - `build/reports/talos/version-summary.json`
  - `build/reports/talos/coverage-summary.json`
  - `build/reports/talos/e2e-summary.json`
  - `build/reports/talos/qodana-summary.json`

Summary review:

- `version-summary.json`: version `0.9.9`; `talos.jar` exists; jar task status
  was `up-to-date-in-current-run` during summary generation after the
  candidate distribution build.
- `coverage-summary.json`: 3540 total candidate unit tests, 3538 passed, 0
  failures, 0 errors, 2 skipped; instruction coverage 82.73%, branch coverage
  64.5%.
- `e2e-summary.json`: 100 deterministic E2E tests passed, 0 failures, 0
  errors, 0 skipped.
- `qodana-summary.json`: `qodana-results-match-current-candidate`; branch and
  revision provenance match `v0.9.0-beta-dev` at `70629b7`; 76 high findings,
  0 critical findings, and no baseline for new-issue classification.

Conclusion:

- T266 produced a named 0.9.9 candidate and reviewable evidence packet.
- The candidate is not Qodana-clean. The current packet is suitable for release
  readiness review, not for claiming a clean static-analysis gate.
