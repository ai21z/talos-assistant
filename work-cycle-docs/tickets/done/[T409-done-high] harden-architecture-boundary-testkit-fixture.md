# [T409-done-high] Harden Architecture Boundary TestKit Fixture

## Status

Done.

## Trigger

The T408 beta push CI run failed in `ArchitectureBoundaryValidationTaskTest.rejectsForbiddenPackageWildcardImport`.

The failing CI log showed the fixture build failed during nested Gradle configuration with a `TimeoutException`, before the test could assert the expected architecture-boundary violation output.

Evidence collected:

- T408 PR CI passed end to end.
- T408 local full `.\gradlew.bat check --no-daemon` passed.
- The beta push retry job passed all steps.
- The workflow-level rerun then hit GitHub `startup_failure` before any job was allocated.
- Local forced reruns of `ArchitectureBoundaryValidationTaskTest` passed.

## Change

Hardened the `ArchitectureBoundaryValidationTaskTest` Gradle TestKit fixture:

- append `org.gradle.daemon=false` to the copied fixture `gradle.properties`
- route success and expected-failure fixture executions through one `validationRunner(...)`
- keep only Tooling-API-supported arguments

## Rejected Fixes

Two investigated approaches were rejected and removed:

- `GradleRunner.withGradleUserHomeDir(...)`: unavailable in the Gradle TestKit API used by this project.
- `GradleRunner.withTestKitDir(...)`: created locked native/cache files under JUnit temp directories on Windows and caused temp cleanup failures.
- `--no-daemon` as a TestKit argument: rejected by the Gradle Tooling API.

## Scope Discipline

This ticket changes test fixture stability only.

Not changed:

- production source
- architecture-boundary scanner logic
- baseline file
- workflow YAML
- T408 protected-read answer guard
- outcome wording or runtime behavior

## Verification

Focused:

- `.\gradlew.bat test --tests "dev.talos.build.ArchitectureBoundaryValidationTaskTest" --rerun-tasks --no-daemon`

Final ticket gate:

- `.\gradlew.bat validateArchitectureBoundaries --no-daemon`
- `git diff --check`
- `.\gradlew.bat check --no-daemon`

## Next

Publish T409 and require a clean PR CI plus beta push CI before resuming outcome-ownership work.

After beta is clean, delete merged `T408` and `T409` branches/worktrees, then inspect post-T408/T409 `ExecutionOutcome` before choosing T410.
