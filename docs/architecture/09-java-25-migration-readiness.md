# Java 25 Migration Readiness Spike

Date: 2026-05-05
Branch: `v0.9.0-beta-dev`
Status: readiness spike, no baseline change

## Recommendation

Keep Java 21 as the Talos baseline for now.

Java 25 should not become the product baseline in the current capability-spine
batch. The migration is feasible, but it is not a one-line `javaVersion=25`
change. It requires a separate implementation ticket that updates and verifies
the build/runtime stack together:

- Gradle wrapper to 9.1.0 or later;
- JavaFX to a Java 25-compatible line;
- Windows `installDist` and `jpackage` behavior;
- Lucene/vector runtime behavior;
- managed llama.cpp install and audit flows.

Java 25 can be revisited after the capability-spine and workspace-operation
architecture work is stable.

## Local Project Facts

Current local configuration:

| Item | Current value | Source |
|---|---:|---|
| Talos Java toolchain property | `javaVersion=21` | `gradle.properties` |
| JavaFX | `21.0.3`, Windows classifier | `gradle.properties`, `build.gradle.kts` |
| Gradle wrapper | `8.14` | `gradle/wrapper/gradle-wrapper.properties` |
| Local JDK | Eclipse Temurin `21.0.9+10-LTS` | `java -version`, `gradlew --version` |
| Lucene | `10.2.2` | `gradle.properties` |
| Test JVM flag | `--add-modules jdk.incubator.vector` | `build.gradle.kts` |
| Application JVM flag | `-XX:+UseZGC` | `build.gradle.kts` |
| Windows packaging | `jpackageApp` uses `JAVA_HOME/bin/jpackage.exe` when available | `build.gradle.kts` |

Local toolchain detection found only JDK 21:

```text
Eclipse Temurin JDK 21.0.9+10-LTS
Location: C:\Program Files\Eclipse Adoptium\jdk-21.0.9.10-hotspot
Language Version: 21
```

No local Java 25 verification was run because Java 25 is not installed on this
machine and the current Gradle wrapper is not the right wrapper for running
Gradle on Java 25.

## Compatibility Facts

| Area | Finding | Impact |
|---|---|---|
| Java release/support | Oracle lists Java SE 25 as an LTS release. | Java 25 is a legitimate future baseline candidate. |
| Gradle | Gradle's compatibility matrix lists Java 25 support starting with Gradle 9.1.0. Current wrapper is 8.14. | Talos must upgrade the wrapper before Java 25 can be a supported build/runtime path. |
| JavaFX | JavaFX 25 is compiled with `--release 23` and requires JDK 23 or later. | Moving JavaFX to 25 means Java 21 can no longer remain the runtime baseline for JavaFX artifacts. |
| Lucene | Lucene 10 runs on Java 21 or greater. | Lucene 10.2.2 does not block Java 25, but vector/runtime behavior still needs tests. |
| Windows packaging | Current `jpackageApp` resolves `jpackage` from `JAVA_HOME` first. | A Java 25 baseline means MSI/runtime packaging must be tested with JDK 25, not inferred from `installDist`. |

## Commands Run

```powershell
java -version
javac -version
jpackage --version
where.exe java
where.exe javac
where.exe jpackage
Get-ChildItem Env:JAVA_HOME -ErrorAction SilentlyContinue
.\gradlew.bat --version
.\gradlew.bat --no-daemon javaToolchains
.\gradlew.bat --no-daemon build installDist
```

Results:

- `java`, `javac`, and `jpackage` all resolve to Temurin JDK 21.0.9.
- `JAVA_HOME` points to `C:\Program Files\Eclipse Adoptium\jdk-21.0.9.10-hotspot\`.
- Gradle 8.14 runs on Java 21.0.9.
- Gradle toolchain detection reports only JDK 21.
- Current baseline `build installDist` passes on Java 21.

## Baseline Verification

Current baseline command:

```powershell
.\gradlew.bat --no-daemon build installDist
```

Result:

```text
BUILD SUCCESSFUL
15 actionable tasks: 15 up-to-date
```

This confirms that the current Java 21 baseline is healthy after the recent
runtime and documentation tickets.

## Migration Risks

### Gradle Wrapper

The current wrapper is Gradle 8.14. Java 25 support starts at Gradle 9.1.0 in
the official compatibility matrix. A Java 25 migration must therefore start by
upgrading the wrapper and running the full test/e2e/coverage gates.

Do not change `javaVersion` to 25 while keeping Gradle 8.14.

### JavaFX Runtime

Talos currently uses JavaFX 21.0.3 Windows artifacts. JavaFX 25 requires JDK 23
or later, so a JavaFX 25 upgrade is tied to dropping Java 21 as the runtime
baseline.

If Talos wants Java 25 as optional while keeping Java 21 baseline, JavaFX needs
separate compatibility testing. Do not assume JavaFX 21 artifacts are a good
long-term Java 25 packaging target.

### Windows Install And MSI

`installDist` is not enough for the baseline decision. The migration must also
test:

- generated launcher scripts;
- `jpackageApp` with JDK 25;
- JavaFX runtime resolution;
- the app starting from the installed distribution;
- managed llama.cpp server lifecycle from the installed distribution.

### Lucene And Vector API

Lucene 10 supports Java 21 or greater, so Java 25 is not blocked by Lucene's
minimum requirement. Still, Talos uses `jdk.incubator.vector` in test JVM args
for Lucene ANN performance. The Java 25 migration ticket should run the Lucene
unit tests and retrieval/e2e tests with Java 25 specifically.

### Build Script Compatibility

The build script uses Gradle APIs, Kotlin DSL, TestKit, JaCoCo, application
plugin, `jpackage`, and custom report tasks. Gradle 9.x can expose deprecations
or behavior changes that are not visible under Gradle 8.14.

The migration should be treated as build-infrastructure work, not as a drive-by
property edit.

## Decision

Recommendation: stay on Java 21 for now.

Reason:

- Java 21 baseline is currently passing.
- Java 25 is valid but requires a wrapper upgrade.
- JavaFX 25 changes the minimum runtime level.
- No local JDK 25 is installed for direct verification.
- The capability-spine/workspace-operation work is higher product leverage and
  should not be coupled to build-platform migration.

## Future Implementation Ticket Shape

If/when Talos moves to Java 25, create a separate ticket with this scope:

- Upgrade Gradle wrapper to a Java 25-compatible Gradle 9.x version.
- Decide whether Java 25 is baseline or optional.
- If baseline, update `javaVersion=25`.
- If baseline, update JavaFX to the JavaFX 25 line.
- Keep Lucene 10.2.2 unless tests reveal a specific issue.
- Run:
  - `.\gradlew.bat --no-daemon clean build installDist`
  - `.\gradlew.bat --no-daemon javaToolchains`
  - `.\gradlew.bat --no-daemon jpackageApp` if WiX/MSI prerequisites are present
  - installed-distribution smoke test
  - managed llama.cpp focused smoke test
- Document any Gradle 9.x deprecation or plugin fixes.

## Sources

- Oracle Java SE Support Roadmap: https://www.oracle.com/java/technologies/java-se-support-roadmap.html
- Gradle compatibility matrix: https://docs.gradle.org/current/userguide/compatibility.html
- Gradle 9.1 release notes: https://docs.gradle.org/current/release-notes
- JavaFX 25 release notes: https://docs.oracle.com/en/java/java-components/javafx/25/release-notes/
- OpenJFX 25 highlights: https://openjfx.io/highlights/25/
- Lucene 10 system requirements: https://lucene.apache.org/core/10_0_0/SYSTEM_REQUIREMENTS.html
