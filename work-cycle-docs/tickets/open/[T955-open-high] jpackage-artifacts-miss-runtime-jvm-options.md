# [T955-open-high] Jpackage release artifacts miss Talos runtime JVM options

Status: open
Priority: high

## Evidence Summary

- Source: post-merge QA staging artifact installed smoke
- Date: 2026-07-04
- Branch under ticket: `v0.9.0-beta-dev`
- Candidate version / commit: `0.10.8` /
  `945f57e297990c23e966e35e7c923f611c105e73`
- GitHub Actions run: `28714259463`
- Downloaded artifact root:
  `local/release-artifact-smoke/run-28714259463-20260704-195918/`
- Linux test environment: WSL2 Ubuntu 26.04 LTS, x86_64

The staged Linux tarball installs and starts, but `talos status --verbose` from
the installed jpackage app image prints the Java native-access warning:

```text
WARNING: A restricted method in java.lang.foreign.Linker has been called
WARNING: java.lang.foreign.Linker::downcallHandle has been called by the unnamed module
WARNING: Use --enable-native-access=ALL-UNNAMED to avoid a warning for this module
```

The staged Linux launcher config confirms the packaged app image does not carry
the runtime JVM options:

```text
local/release-artifact-smoke/run-28714259463-20260704-195918/linux-smoke/install/app/lib/app/talos.cfg

[JavaOptions]
java-options=-Djpackage.app-version=0.10.8
```

The Gradle `application` plugin default JVM args do include the intended flags:

```kotlin
applicationDefaultJvmArgs = listOf(
    "-Dfile.encoding=UTF-8",
    "-Dstdout.encoding=UTF-8",
    "-Dstderr.encoding=UTF-8",
    "--enable-native-access=ALL-UNNAMED",
    "-XX:+UseZGC"
)
```

But the jpackage tasks construct their command lines manually and do not pass
those flags with `--java-options`:

```text
build.gradle.kts:1007 jpackageApp
build.gradle.kts:1050 jpackageAppImage
build.gradle.kts:1158 jpackageLinuxAppImage
```

Installed smoke from the staged Windows app ZIP did not display the warning on
the tested commands, but the staged Windows `Talos.cfg` is also missing the
intended JVM options:

```text
windows-appzip-extract/Talos/app/Talos.cfg

[JavaOptions]
java-options=-Djpackage.app-version=0.10.8
```

## Classification

Primary taxonomy bucket: `RELEASE_PACKAGING`

Secondary buckets:

- `CLI_UX`
- `PUBLIC_ARTIFACT_GATE`
- `TERMINAL_RENDERING`

Blocker level: public-artifact blocker

Why this level:

```text
This is not a core runtime safety failure, but it is a public artifact quality
failure: the packaged app images do not preserve the runtime flags already
required by the repo, and the Linux public artifact emits a JVM warning during
normal status output. It should be fixed before publishing public release
assets, because users should not see JDK foreign-linker warnings in the first
installed smoke path.
```

## Architectural Hypothesis

Architectural hypothesis:

```text
`applicationDefaultJvmArgs` affects Gradle application/installDist launchers,
but manually constructed jpackage invocations need explicit repeated
`--java-options` entries. The release app images bypass the plugin launcher
flags unless the jpackage tasks add them.
```

Likely code/document areas:

- `build.gradle.kts`
- `src/test/java/dev/talos/release/PublicInstallPackagingContractTest.java`
- `src/test/java/dev/talos/release/CiWorkflowContractTest.java`

## Goal

```text
Every public jpackage artifact launcher must carry the same required Talos
runtime JVM options as the normal application launcher, including UTF-8 stdout
and stderr encoding and `--enable-native-access=ALL-UNNAMED`.
```

## Non-Goals

- No changing Talos runtime behavior beyond launcher JVM flags.
- No suppressing warnings by filtering stdout/stderr.
- No changing model setup behavior.
- No publishing public artifacts while validating the fix.

## Implementation Notes

Add one shared list for required runtime JVM options and use it from both:

- `application.applicationDefaultJvmArgs`
- all jpackage tasks via `--java-options`

Avoid duplicating the list in multiple places without a test guard.

Pin the packaged launcher config with a release packaging contract test. The
test does not need to run a full jpackage build if a cheaper task-output or
command-line construction seam exists, but the final acceptance should include
a fresh staged artifact inspection proving the generated `.cfg` files contain:

```text
java-options=-Dfile.encoding=UTF-8
java-options=-Dstdout.encoding=UTF-8
java-options=-Dstderr.encoding=UTF-8
java-options=--enable-native-access=ALL-UNNAMED
```

Evaluate whether `-XX:+UseZGC` should also be carried into jpackage artifacts
on all supported platforms. If it is intentionally excluded for packaged
artifacts, document and test that decision.

## Architecture Metadata

Capability:

- installed CLI startup and release packaging

Operation(s):

- jpackage MSI app launcher
- jpackage Windows app-image launcher
- jpackage Linux app-image launcher

Owning files:

- `build.gradle.kts`
- release packaging contract tests

New or changed tools:

- none

Risk, approval, and protected paths:

- Risk level: high for public packaging quality, none for workspace mutation
- Approval behavior: unchanged
- Protected path behavior: unchanged

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: not applicable
- Evidence obligation: packaged launcher configs contain required JVM options
- Verification profile: contract test plus staged Windows/Linux artifact smoke
- Repair profile: no model reprompt change

Outcome and trace:

- Outcome/truth warnings: status/doctor output should not be prefixed by JVM
  native-access warnings
- Trace/debug fields: unchanged

Refactor scope:

- Allowed: factor shared runtime JVM option list for application and jpackage
- Forbidden: broad Gradle release pipeline redesign

## Acceptance Criteria

- All jpackage tasks pass the required runtime JVM options into generated
  launchers.
- Staged Linux `talos status --verbose` no longer prints the foreign-linker
  native-access warning.
- Staged Windows launcher config contains the same required JVM options.
- UTF-8 stdout/stderr launcher flags remain preserved for terminal glyphs.
- Release packaging contract tests fail if future jpackage artifacts drop the
  required JVM options.

## Tests / Evidence

Required deterministic coverage:

```powershell
.\gradlew.bat test --tests "dev.talos.release.PublicInstallPackagingContractTest" --no-daemon
```

Required staging evidence:

```powershell
.\gradlew.bat windowsReleaseArtifacts --no-daemon
wsl.exe -e bash -lc './gradlew linuxReleaseArtifacts --no-daemon'
```

or a fresh GitHub release-staging run if local Linux jpackage is not used for
final proof.

Required installed smoke:

```text
Windows staged app ZIP:
  Talos.exe --version
  Talos.exe status --verbose

Linux staged tarball installed through install-talos.sh:
  talos --version
  talos status --verbose
```

Expected result:

```text
No "WARNING: A restricted method in java.lang.foreign.Linker" output appears.
```

## Known Risks

- `--java-options` handling differs slightly across jpackage target types.
  Test the generated launcher config for Windows app-image and Linux app-image,
  and smoke the MSI path when feasible.

## Known Follow-Ups

- If Windows MSI local smoke remains awkward before a public signed release,
  keep app ZIP smoke as the minimum and record MSI install smoke as a named
  exclusion until a safe per-user install/uninstall script is in place.

## Implementation Status

Implemented locally on `v0.9.0-beta-dev` after commit
`9d7174ee9129c0a566d3a1656adf6d7894f54f5d`:

- `build.gradle.kts` now owns one `talosRuntimeJvmOptions` list used by
  `applicationDefaultJvmArgs`.
- All jpackage tasks call `appendTalosRuntimeJavaOptions(args)`, which emits
  one `--java-options` entry per Talos runtime JVM option.
- The shared option set currently includes:
  - `-Dfile.encoding=UTF-8`
  - `-Dstdout.encoding=UTF-8`
  - `-Dstderr.encoding=UTF-8`
  - `--enable-native-access=ALL-UNNAMED`
  - `-XX:+UseZGC`
- `PublicInstallPackagingContractTest` fails if future jpackage tasks stop
  receiving the shared runtime JVM options.

Status remains `open` until fresh staged Windows/Linux artifacts prove their
generated launcher config carries these Java options and Linux installed smoke
no longer emits the native-access warning on `talos status --verbose`.

Focused verification:

```powershell
.\gradlew.bat test --tests "dev.talos.release.PublicInstallPackagingContractTest" --no-daemon
```

Local packaging smoke:

```powershell
.\gradlew.bat jpackageAppImage --no-daemon
Get-Content -Raw -LiteralPath 'build/dist/windows-app-image/Talos/app/Talos.cfg'
```

Observed generated config:

```text
[JavaOptions]
java-options=-Djpackage.app-version=0.10.8
java-options=-Dfile.encoding=UTF-8
java-options=-Dstdout.encoding=UTF-8
java-options=-Dstderr.encoding=UTF-8
java-options=--enable-native-access=ALL-UNNAMED
java-options=-XX:+UseZGC
```
