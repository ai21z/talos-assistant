# [T860-done-low] Remove dead JavaFX runtime dependency from build and MSI

Status: done
Priority: low

## Summary

`build.gradle.kts` declared three JavaFX runtime dependencies
(`org.openjfx:javafx-base`, `javafx-graphics`, `javafx-controls`, version
`21.0.3` with the `win` classifier), wired through the `javafxVersion` /
`javafxPlatform` properties in `gradle.properties`. JavaFX was used by the
original LOQ-J `FirstRunWizard` UI scaffold but is dead in the current Talos
CLI: there is no JavaFX code, no UI surface, and no module/packaging hook that
needs it. Because the three `org.openjfx` jars sit on the `implementation`
classpath, `installDist` copies them into `build/install/talos/lib`, and
`jpackageApp` then bundles that whole `lib` dir into the Windows MSI. The result
is a CLI installer that ships a large JavaFX runtime it never loads.

This matters before the public beta: the "intentionally boring stack" framing is
undercut the moment a reader opens `build.gradle.kts` and asks why a terminal
CLI pulls in JavaFX.

## Evidence Summary

- Source: manual static audit (owner-directed)
- Date: 2026-06-22
- Talos version / commit: 0.10.5 / branch v0.9.0-beta-dev @ 1706cf49
- Verification status: PASS (see Outcome below)

Dead-code proof (repo-wide, case-insensitive `javafx`/`openjfx`):

```text
build.gradle.kts            - the 3 deps + 2 property reads (the declarations themselves)
gradle.properties           - javafxVersion / javafxPlatform (the declarations themselves)
work-cycle-docs/tickets/...  - T300, T127 documentation mentions only
LuceneStoreBm25Test.java:17 - the literal string "JavaFX first run wizard and UI
                              scaffold" used as BM25 search-corpus test data only
```

Non-import vectors checked, all negative:

- No `import javafx.*` / `import org.openjfx.*` anywhere in `src/main` or `src/test`.
- No `module-info.java` (non-modular app), so no `requires javafx.*`.
- No `.fxml` resources.
- No `--add-modules javafx.*`, no `jlink` module list, no `Class.forName`
  reflection loading a JavaFX class.
- `jpackageApp` / `jpackageAppImage` pass `--main-jar talos.jar` over the
  `installDist` `lib` input with no module arguments; JavaFX was included only
  as bundled jars, not as a runtime-image module.
- No `.ps1` / `.bat` / installer script references JavaFX.
- No test asserts the JavaFX dependency must exist
  (`PublicInstallPackagingContractTest` checks jpackage options, not deps).

## Change

- `build.gradle.kts`: removed the three `org.openjfx` `implementation` lines and
  the `javafxVersion` / `javafxPlatform` `project.property(...)` reads.
- `gradle.properties`: removed `javafxVersion=21.0.3` and `javafxPlatform=win`
  plus their comment.
- `LuceneStoreBm25Test` corpus string left untouched (intentional test data).

## Goal

The Talos build and Windows package no longer reference or bundle JavaFX. No
production or test behavior changes; the only artifact change is a smaller
install payload.

## Non-Goals

- No change to the BM25 test corpus string in `LuceneStoreBm25Test`.
- No version bump (inner-loop hygiene change).
- No packaging-task restructuring beyond dropping the dead deps.

## Acceptance Criteria

- [x] The three `org.openjfx` dependencies and the `javafxVersion` /
  `javafxPlatform` properties are removed.
- [x] `.\gradlew.bat check --no-daemon` passes.
- [x] The jpackage Windows package still assembles, and the bundled `lib` /
  app-image no longer contains any `javafx-*` jar.
- [x] No regressions to privacy, permissions, checkpointing, trace redaction, or
  outcome truth.

## Outcome (verification, 2026-06-22, HEAD 1706cf49 + this change)

```text
.\gradlew.bat check --no-daemon
  -> BUILD SUCCESSFUL in 3m 18s, exit 0 (full check, all suites green)

build/install/talos/lib (jpackage's exact MSI input)
  -> 47 jars, ZERO javafx/openjfx jars

.\gradlew.bat jpackageAppImage --no-daemon
  -> BUILD SUCCESSFUL in 9s, exit 0
  -> build/dist/windows-app-image/Talos/Talos.exe launcher produced
  -> ZERO javafx files anywhere in the app-image, 47 app jars

.\gradlew.bat jpackageApp --no-daemon  (MSI type)
  -> FAILS at the WiX wrapping step only:
     "Can not find WiX tools (light.exe, candle.exe)" /
     "Error: Invalid or unsupported type: [msi]"
  -> This is a missing host prerequisite (WiX is not installed on this host;
     it is a documented packaging prerequisite in docs/public-installation.md),
     NOT a regression from this change. :jar, :startScripts, and :installDist
     all succeeded and jpackage assembled the app before the WiX step. The
     app-image path (same runtime+jar bundling, no WiX) builds cleanly, which
     verifies the substantive packaging.
```

## Work-Test Cycle Notes

- Inner dev loop. No version bump. One-line entry added under `## [Unreleased]`
  in `CHANGELOG.md`.

## Known Follow-Ups

- To produce the actual MSI on this host (or in CI), install the WiX toolset and
  add it to PATH. Tracked as a host/CI prerequisite, not a code change.
