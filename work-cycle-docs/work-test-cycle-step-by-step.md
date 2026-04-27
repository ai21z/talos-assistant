# Talos Work-Test Cycle: Step-By-Step Runbook

This is the practical runbook for running the Talos work-test cycle yourself.
Use a simple rule:

- use the inner loop while you are still coding
- use the candidate loop only when the change is ready for review

The candidate loop is slower because it creates evidence for one named version.

## Step 0: Preflight

Goal: confirm the machine can run the cycle.

What the developer does:

1. Open PowerShell at the repo root.
2. Confirm Java is available.
3. Confirm Gradle wrapper runs.
4. Confirm Docker Desktop is running only if you plan to run Qodana in container mode.

Commands:

```powershell
java -version
./gradlew.bat --version
docker version
```

Expected result:

- Java reports version 21 or newer.
- Gradle starts without wrapper errors.
- `docker version` works if using Qodana container mode.

If Docker is not running, Qodana container mode will fail. You do not need a
Qodana container already running. The `docker run --rm ...` command starts one,
runs analysis, then removes it.

## Step 1: Start Clean

Goal: know what files are already changed before you start.

What the developer does:

1. Check Git status.
2. Decide which existing changes are yours.
3. Do not mix unrelated work into the candidate.

Command:

```powershell
git status --short
```

Expected result:

- You understand every changed file before continuing.
- If there are unrelated changes, leave them alone or move to another branch.

## Step 1A: Name And Track The Ticket

Goal: make every ticket easy to sort, reference, and connect to changelog
entries.

What the developer does:

1. Create or update one ticket file under `work-cycle-docs/tickets/open/`.
2. Prefix the ticket filename with `[code-status-prio]`.
3. Keep the ticket code stable for the life of the ticket.
4. Update the status and priority in the filename when the ticket status or
   priority changes.

Filename format:

```text
[T01-open-high] talos-workspace-negative-capability-no-tool-answer.md
```

Rules:

- `code` is a stable ticket id, for example `T01`, `T02`, `T03`.
- `status` mirrors the ticket body status: `open`, `in-progress`, or `done`.
- `prio` mirrors the ticket body priority: `high`, `medium`, or `low`.
- The descriptive filename after the prefix stays short and kebab-case.
- When a ticket is completed, rename it from `[T01-open-high] ...` or
  `[T01-in-progress-high] ...` to `[T01-done-high] ...` and move it from
  `work-cycle-docs/tickets/open/` to `work-cycle-docs/tickets/done/`.
- Keep open and in-progress tickets in `work-cycle-docs/tickets/open/`.
- Keep completed tickets in `work-cycle-docs/tickets/done/`.
- Keep `work-cycle-docs/tickets/new-work.md` at the ticket root; it is source
  doctrine, not an active ticket.

Expected result:

- Ticket order is visible in file listings.
- Current work is visible in `open/`; completed history is visible in `done/`.
- Status and priority are visible without opening each ticket file.
- Changelog entries can point to the exact ticket prefix.

## Step 2: Inner Development Loop

Goal: move fast while writing code.

What the developer does:

1. Change the smallest useful piece of code.
2. Run focused tests for the affected area.
3. Fix failures.
4. Repeat until the intended change works.

Example commands:

```powershell
./gradlew.bat test --tests "dev.talos.runtime.ToolCallLoopTest"
./gradlew.bat test --tests "dev.talos.tools.impl.FileEditToolTest"
./gradlew.bat e2eTest --tests "dev.talos.harness.Phase0ScenariosTest"
```

Expected result:

- Focused tests pass before you widen the scope.
- You do not bump the version in this loop.
- You do not run Qodana after every small edit.

## Step 3: Run A Pre-Candidate Readiness Check

Goal: catch broad unit-test, deterministic E2E, and coverage problems before declaring a candidate.

What the developer does:

1. Optionally run the normal verification gate before bumping the version.
2. Fix failures before bumping the version.

Command:

```powershell
./gradlew.bat check
```

Expected result:

- Unit tests pass.
- Deterministic E2E tests pass.
- JaCoCo coverage verification passes.

If this fails, stay in the inner loop. Do not create a candidate yet.

Important: this is a pre-candidate readiness check only. It is allowed and
useful, but it is not candidate evidence because it ran before the reviewable
version was declared. A passing pre-bump `./gradlew.bat check` never replaces
the mandatory post-bump candidate `./gradlew.bat check` in Step 6.

## Step 4: Declare A Candidate

Goal: give the reviewable state a version before collecting final evidence.

What the developer does:

1. Run the patch bump script.
2. Edit the generated changelog stub.
3. Replace `pending release notes` with the real change summary.
4. For each completed ticket, include its ticket prefix and a short description
   of the user-visible or architecture-visible change.

Command:

```powershell
./scripts/bump-patch.ps1
```

Expected result:

- `gradle.properties` has the next `talosVersion`.
- `CHANGELOG.md` has a new entry for that version.
- The changelog says what changed in plain words.
- Done-ticket entries include the ticket prefix, for example:

```text
- [T01-done-high] Blocked negative local-access claims on workspace turns by
  routing no-tool answers through the centralized outcome policy.
```

Important: versioning happens before the candidate evidence run. That is what
makes the evidence belong to a named candidate.

## Step 5: Build The Candidate Artifact

Goal: build the jar that belongs to the named candidate.

What the developer does:

1. Build the jar.
2. If needed, build the install distribution too.

Commands:

```powershell
./gradlew.bat jar
./gradlew.bat installDist
```

Expected result:

- `build/libs/talos.jar` exists.
- The build uses the version from `gradle.properties`.

## Step 6: Run The Mandatory Candidate Check

Goal: prove the named candidate version passes the hard local gate.

What the developer does:

1. Run the normal verification gate after the patch bump and changelog update.
2. Treat this run as candidate evidence.
3. Fix failures before collecting the rest of the candidate packet.

Command:

```powershell
./gradlew.bat check
```

Expected result:

- Unit tests pass for the named candidate version.
- Deterministic E2E tests pass for the named candidate version.
- JaCoCo coverage verification passes for the named candidate version.

Important: this step is mandatory for candidate review, even if Step 3 already
passed before the bump. Evidence must belong to the version declared in
`gradle.properties` and described in `CHANGELOG.md`; do not present a pre-bump
`check` run as sufficient review evidence.

## Step 7: Run Qodana Community Locally

Goal: run static analysis without paid Qodana services.

Recommended local-only command:

```powershell
./gradlew.bat qodanaLocal
```

Equivalent raw Docker command:

```powershell
docker run --rm -v "${PWD}:/data/project" -v "${PWD}\.qodana:/data/results" -v talos-qodana-cache:/data/cache -v talos-qodana-gradle-cache:/root/.gradle jetbrains/qodana-jvm-community:2026.1
```

What the developer does:

1. Keep Docker Desktop running.
2. Run the Community JVM image, not the paid JVM image.
3. Do not set `QODANA_TOKEN` for the local Community run.
4. Wait for analysis to finish.

Expected result:

- Qodana writes local output under `.qodana/`.
- Qodana and Gradle caches use Docker volumes, which is more stable than putting every cache on the Windows project bind mount.
- No Qodana Cloud upload is needed.
- No paid token is needed for the Community linter.
- Critical Qodana findings fail the Qodana command.
- High/moderate findings are reviewed but are not yet the hard gate.

Docker answer:

- If you use `docker run` or default `qodana scan`, yes, Docker or Podman must be installed and running.
- You do not need a Qodana container already active.
- The command creates a temporary container and removes it because of `--rm`.
- If you use Qodana native mode, Docker is not required, but the CLI downloads and runs a JetBrains IDE-based linter locally. That is less isolated than container mode.

For this repo, prefer container mode for candidate evidence because it is more
repeatable and keeps analysis environment differences smaller. If Docker mode
fails on Windows with a Gradle `Input/output error`, install Qodana CLI and run:

```powershell
./gradlew.bat qodanaNativeLocal
```

## Step 8: Generate The Candidate Summaries

Goal: produce one machine-readable packet for review.

What the developer does:

1. Run the Talos summary task.
2. Let it run candidate unit tests, candidate E2E tests, coverage summary, Qodana summary, and version summary.
3. Inspect the generated JSON files.

Command:

```powershell
./gradlew.bat talosQualitySummaries
```

Expected result:

- `build/reports/talos/version-summary.json`
- `build/reports/talos/coverage-summary.json`
- `build/reports/talos/e2e-summary.json`
- `build/reports/talos/qodana-summary.json`

The candidate test lanes are fail-soft. They preserve evidence even when tests
fail, so the summary can say what failed instead of hiding the result.

## Step 9: Review The Packet

Goal: decide whether the candidate is good enough.

What the developer checks:

1. `CHANGELOG.md` matches the actual change.
2. `version-summary.json` points to the jar you built.
3. `coverage-summary.json` has test status and coverage data.
4. `e2e-summary.json` shows the deterministic harness result.
5. `qodana-summary.json` says whether Qodana results match the current branch and revision.
6. `git status --short` contains only intended candidate files.

Useful commands:

```powershell
git status --short
Get-Content build/reports/talos/version-summary.json
Get-Content build/reports/talos/coverage-summary.json
Get-Content build/reports/talos/e2e-summary.json
Get-Content build/reports/talos/qodana-summary.json
```

Expected result:

- Test failures are either zero or explicitly accepted.
- Qodana findings are understood.
- Qodana provenance is not stale.
- The candidate can be reviewed as one unit.

## Step 10: If The Candidate Fails

Goal: fix the code, not the evidence.

What the developer does:

1. Return to the inner development loop.
2. Fix the problem.
3. Run focused tests.
4. Decide whether the fix needs a new patch bump.
5. Re-run the candidate evidence steps, including the mandatory post-bump
   candidate `./gradlew.bat check`.

Rule of thumb:

- If the candidate was already shared for review, create a new patch candidate.
- If this was still private local prep, it is acceptable to fix and rerun before sharing.

## Step 11: Commit Or Hand Off

Goal: leave a reviewer with clear evidence.

What the developer does:

1. Commit source, docs, version, and changelog changes.
2. Do not commit generated `build/` output.
3. Do not commit `.qodana/` output unless the team explicitly changes that policy.
4. Mention the commands run and the summary status in the handoff.

Suggested handoff text:

```text
Candidate version: <version>
Checks run:
- ./gradlew.bat check
- ./gradlew.bat qodanaLocal
- ./gradlew.bat talosQualitySummaries

Summary files reviewed:
- build/reports/talos/version-summary.json
- build/reports/talos/coverage-summary.json
- build/reports/talos/e2e-summary.json
- build/reports/talos/qodana-summary.json
```

## Qodana Evaluation For This Repo

Decision: keep Qodana Community as the local static-analysis candidate gate, but
do not treat it as the only quality or security tool.

Why it is useful:

- It is free in the Community edition.
- It supports JVM projects.
- It produces structured local results that this repo already summarizes.
- It gives IntelliJ-grade inspections that are usually stronger than basic Java linters.

Limits:

- Default CLI/container mode needs Docker or Podman running.
- Community is limited compared to paid Qodana editions.
- It is static analysis, not a full security audit.
- The paid `jetbrains/qodana-jvm` linter should not be used for the local-free path.

Security recommendation:

- For code quality: keep Qodana Community.
- For dependency vulnerability scanning: add OWASP Dependency-Check or OSV-Scanner later.
- For secret scanning: add Gitleaks later.
- For this repo today, no single free local tool is clearly better than Qodana Community for JVM code-quality inspections.

Practical conclusion:

- Qodana Community is worth keeping.
- Use the Community image locally.
- Keep Docker running for candidate scans unless you deliberately switch to native mode.
- Add focused security tools separately instead of expecting Qodana Community to cover everything.
