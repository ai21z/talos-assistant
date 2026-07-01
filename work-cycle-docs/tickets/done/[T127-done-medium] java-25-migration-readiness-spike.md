# T127 - Java 25 Migration Readiness Spike

Severity: medium
Status: done

## Problem

Talos currently uses Java 21 LTS. Java 25 is now an LTS release, but the project cannot assume migration is safe without checking Gradle, JavaFX, Windows packaging, and manual llama.cpp flows.

Current local facts:

- `gradle.properties`: `javaVersion=21`
- Gradle wrapper: `8.14`
- JavaFX: `21.0.3`

Official compatibility facts:

- Oracle lists Java SE 25 as LTS.
- Gradle's compatibility matrix lists Java 25 support starting at Gradle 9.1.0.
- JavaFX 25 requires JDK 23 or later.

## Sources

- Oracle Java SE roadmap: https://www.oracle.com/europe/java/technologies/java-se-support-roadmap.html
- Gradle compatibility matrix: https://docs.gradle.org/current/userguide/compatibility.html
- JavaFX 25 release notes: https://docs.oracle.com/en/java/java-components/javafx/25/release-notes

## Scope

- Evaluate Java 25 migration feasibility.
- Check Gradle 9.1+ wrapper migration.
- Check JavaFX 25.x compatibility and Windows artifacts.
- Check Lucene/runtime compatibility.
- Run build/test/install verification where feasible.
- Decide whether Java 25 should be baseline, optional, or deferred.

## Acceptance

- Written readiness report is committed.
- Report includes local commands run and results.
- Report includes compatibility conclusions for Gradle, JavaFX, Windows install path, and Talos runtime.
- Recommendation is one of:
  - stay on Java 21 for now;
  - support Java 25 as optional;
  - migrate baseline to Java 25 through a separate implementation ticket.

## Non-Goals

- No baseline change in this spike unless explicitly split into a follow-up implementation ticket.
- No unrelated dependency upgrade.
- No code refactor.

## Verification

- At minimum, run current baseline `.\gradlew.bat --no-daemon build installDist`.
- If Java 25 is installed or provisioned, run the same verification on the Java 25 branch/spike state.
