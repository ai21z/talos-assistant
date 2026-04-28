# Talos Work-Test Cycle

This document defines the Talos work-test cycle as implemented on `feature/work-test-cycle`.

The cycle exists to make one Talos patch build a reviewable unit.
That means the repo should let you answer, clearly and honestly:

- what version was evaluated
- what changed in that version
- what artifact was actually built
- what the normal test lane did
- what the deterministic scripted E2E lane did
- what coverage and static analysis said
- whether the generated summaries belong to the current candidate

## The Key Point

The correct model is **not**:

`unit tests -> e2e tests -> versioning -> qodana/jacoco -> repeat`

That flat loop is weak because version identity arrives too late.
You end up collecting evidence first and naming the candidate afterward.

The correct model is:

- a fast **inner dev loop**
- a slower **versioned candidate loop**

The inner loop is for implementation speed.
The candidate loop is for trustworthy review.

## The Visual Cycle

```text
    change code
         |
         v
    +--------------------------+
    | bump patch + changelog   |
    +------------+-------------+
                 |
                 v
    +--------------------------+
    | versioned candidate      |
    +------------+-------------+
                 |
                 v
    build -> test -> e2eTest -> JaCoCo -> optional Qodana/security -> summaries -> review
                                                              |
                                         not good enough -----'
                                                              |
                                                              v
                                                         change code
```

The circle is the core of the process on purpose:

- the candidate is the thing being reviewed
- all evidence should attach to that candidate
- if the review fails, you do not "repair the evidence"
- you change code and create a new candidate

## The Two Loops

### Inner Dev Loop

Use this while actively building or debugging.

Typical behavior:

- change code
- run focused tests
- run a targeted `e2eTest` if needed
- iterate fast

This loop is intentionally cheap.
It should not force:

- a patch bump on every edit
- a changelog update on every edit
- a full Qodana run on every edit
- a full review packet on every edit

### Versioned Candidate Loop

Use this when the current state is worth evaluating as a real patch build.

The order is:

1. Finish the intended change set.
2. Bump the patch version.
3. Update `CHANGELOG.md`.
4. Build the jar.
5. Run the normal test lane.
6. Run the candidate test and deterministic scripted E2E evidence lanes.
7. Run JaCoCo and optional Qodana/security inputs.
8. Generate summary artifacts.
9. Review the candidate as one unit.

This ordering is deliberate.
Versioning happens before the evidence run, not after it.

## Why Versioning Comes Early

The weaker sequence is:

1. change code
2. run tests
3. run E2E
4. run coverage
5. run static analysis
6. assign version later

That produces evidence without a stable identity.

The stronger sequence is:

1. decide this state is a real candidate
2. give it a patch version
3. record the changelog
4. produce evidence for that named candidate

That lets you make exact statements like:

- version `0.9.1` passed these tests and produced these summaries
- version `0.9.2` changed these things and failed this scenario

That is the main discipline the branch now supports.

## The Candidate Packet

For Talos, a serious candidate packet is:

- `CHANGELOG.md`
- the built jar
- normal test results
- deterministic scripted `e2eTest` results
- JaCoCo outputs
- Qodana outputs when the optional local Qodana scan was run
- `build/reports/talos/version-summary.json`
- `build/reports/talos/coverage-summary.json`
- `build/reports/talos/qodana-summary.json`
- `build/reports/talos/e2e-summary.json`

That packet is what makes a patch version comparable to the next one.

## Current Practical Commands

### Inner Dev Loop

Examples:

```powershell
./gradlew.bat test --tests "dev.talos.runtime.JsonTurnLogAppenderTest"
./gradlew.bat e2eTest
```

### Candidate Review Loop

Current branch-ready sequence:

```powershell
./scripts/bump-patch.ps1
./gradlew.bat jar
./gradlew.bat check
./gradlew.bat qodanaLocal
./gradlew.bat talosQualitySummaries
```

Notes:

- `./scripts/bump-patch.ps1` updates `gradle.properties` and `CHANGELOG.md`
- `./gradlew.bat check` is the hard local gate: unit tests, deterministic `e2eTest`, and coverage baseline must pass
- a pre-bump `./gradlew.bat check` is allowed as a readiness check, but it is not candidate evidence
- the candidate `./gradlew.bat check` run is mandatory after the patch version and changelog entry are declared, even if the same command passed before the bump
- review evidence must belong to the named candidate version in `gradle.properties` and `CHANGELOG.md`
- `./gradlew.bat qodanaLocal` is optional but highly recommended; it runs the free local Qodana Community JVM image
- `qodanaLocal` mounts persistent Docker volumes for Qodana and Gradle caches to reduce Windows bind-mount file-lock and I/O problems
- if Docker mode is unavailable and native Qodana is used for candidate evidence, run `./gradlew.bat qodanaNativeFreshLocal` before `./gradlew.bat talosQualitySummaries`; `qodanaNativeLocal` may print findings without refreshing the summary-compatible `.qodana/report/results` path
- `version-summary.json` records jar artifact identity from the built jar itself plus the jar task state observed in the current Gradle invocation
- `talosQualitySummaries` runs candidate evidence lanes that preserve test and E2E results even when those lanes fail, so a failed candidate still produces a packet
- summary tasks declare their source artifacts as inputs, so Gradle re-runs them when the underlying evidence changes; `coverage-summary.json`, `qodana-summary.json`, and `e2e-summary.json` are deliberately content-reproducible (no wall-clock `generatedAt` inside the payload), while `version-summary.json` intentionally records current-invocation jar task state and therefore is not byte-identical across repeated runs
- summary tasks are fail-soft: if a malformed upstream file (e.g. truncated SARIF, corrupt JUnit XML) causes the payload builder to throw, the task still writes a `{"summaryStatus": "summary-generation-failed", ...}` fallback payload instead of taking down the packet
- `e2e-summary.json` now traces JSON scenario resources into executed test cases and distinguishes that tagged scenario-pack subset from untagged harness-only tests
- the `ScenarioRunner.run(...)` and `ScenarioRunner.runStrict(...)` paths of the harness-backed `e2eTest` lane are deterministic with respect to scripted model behavior inside the tool-call loop; the persistence-backed `runWithPersistence(...)` path also injects a scripted LLM so `MemoryUpdateListener` compaction cannot reach a real backend
- `qodana-summary.json` now exposes provenance and freshness status instead of pretending stale results are current
- the community Qodana image works locally without `QODANA_TOKEN`
- the paid `jetbrains/qodana-jvm` image still requires a token and should not be used for the local-free candidate path
- the setup guide is `work-cycle-docs/work-test-cycle-setup.md`
- the practical step-by-step runbook is `work-cycle-docs/work-test-cycle-step-by-step.md`

## What Good Looks Like

A candidate is in good shape when:

- the patch version is intentional and numeric
- the changelog matches the candidate
- the jar identity in the packet matches the artifact under review
- the candidate test lane status is explicit
- the candidate `e2eTest` lane status is explicit
- JSON scenario resources are traceable to their executed E2E cases, and untagged harness-only tests are reported explicitly as outside that tagged subset
- coverage is current enough to review
- Qodana provenance matches the current branch and revision, or mismatch is explicit and understood
- summary artifacts are build-owned and clearly tied to this candidate

## What This Cycle Is Not

This cycle is not:

- a release-management framework
- a requirement to bump patch version after every tiny edit
- a requirement to run Qodana after every tiny edit
- a flat checklist with no distinction between development and candidate review
- permission to use a pre-bump `check` run as the only proof for a named candidate
- a way to generate pretty JSON files without checking freshness and provenance

## Bottom Line

The rigorous conclusion is:

- Talos needs two loops, not one
- patch versioning belongs at the start of candidate review, not at the end
- `test`, `e2eTest`, JaCoCo, Qodana, and summary generation are evidence-producing steps for a named candidate
- `./gradlew.bat check` may run before the bump as a readiness check, but must run again after the bump as candidate evidence
- if the candidate fails review, you change code and create a new patch candidate

That is the correct Talos work-test cycle.
