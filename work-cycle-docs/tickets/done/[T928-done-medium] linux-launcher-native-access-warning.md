# [T928-done-medium] Linux launcher native-access warning

Status: done
Priority: medium

## Evidence Summary

- Source: WSL2 Ubuntu installed-product status and REPL smoke
- Date: 2026-07-02
- Talos version / commit: 0.10.7 /
  2314360f2f972d482405437581160436b456c939
- Platform: WSL2 Ubuntu 26.04, installed Linux Talos under
  `/home/ai21z/.local/talos`

Observed behavior:

```text
WARNING: A restricted method in java.lang.foreign.Linker has been called
WARNING: java.lang.foreign.Linker::downcallHandle has been called by the unnamed module
WARNING: Use --enable-native-access=ALL-UNNAMED to avoid a warning for this module
```

The warning printed before ordinary `talos status --verbose` and REPL output.

Code/dependency evidence:

- `build.gradle.kts` `applicationDefaultJvmArgs` currently includes UTF-8 flags
  and `-XX:+UseZGC`, but not `--enable-native-access=ALL-UNNAMED`.
- `jdeps` on the installed distribution shows bundled `jline-3.30.13.jar`
  references `java.lang.foreign.Linker` through
  `org.jline.terminal.impl.ffm.*`.
- `jdeps` also shows bundled `lucene-core-10.2.2.jar` references
  `java.lang.foreign.*`.
- Running the same installed Linux binary with
  `JAVA_OPTS="--enable-native-access=ALL-UNNAMED"` suppresses the warning.

## Classification

Primary taxonomy bucket: `OUTCOME_TRUTH`

Secondary buckets:

- `UX`
- `RELEASE_HYGIENE`

Severity: P2

Why this level:

```text
This is not a functional runtime failure, but it makes a clean Linux/WSL install
look broken and pollutes installed-product evidence before the Talos output
starts. For a trust-positioned CLI, noisy runtime warnings on the first command
are a credibility issue.
```

## Goal

```text
Generated Talos launchers should include the Java native-access flag required
by the bundled terminal/index libraries so normal Linux/WSL status and REPL
startup output is clean.
```

## Non-Goals

- No dependency upgrades.
- No replacement of JLine or Lucene.
- No suppression by redirecting stderr or hiding arbitrary warnings.
- No weakening of diagnostic output from Talos itself.

## Acceptance Criteria

- `applicationDefaultJvmArgs` includes `--enable-native-access=ALL-UNNAMED`.
- The generated Unix and Windows launchers inherit that flag through Gradle's
  application plugin.
- A focused contract test pins the flag in `build.gradle.kts`.
- Clean Linux installed-product smoke for `talos status --verbose` no longer
  prints the native-access warning.

## Tests / Evidence

Required focused tests:

```powershell
.\gradlew.bat test --tests "dev.talos.release.PublicInstallPackagingContractTest" --no-daemon
git diff --check
```

Required installed-product verification:

```powershell
.\gradlew.bat clean installDist --no-daemon
wsl.exe -e bash -lc 'cd /mnt/c/Users/arisz/Projects/LOQ/loqj-cli && bash tools/install-unix.sh --force'
wsl.exe -e bash -lc 'cd ~/talos-wsl-install-smoke && /home/ai21z/.local/talos/bin/talos status --verbose'
```

## Work-Test Cycle Notes

- This ticket is split from T926 because it is a small launcher hygiene defect
  exposed by the WSL setup run, not the setup wizard itself.
- If the JVM flag has platform-specific downside in future Java versions, add a
  launcher-flag compatibility ticket rather than dropping the warning fix
  silently.

## Resolution

Implemented in `build.gradle.kts` by adding
`--enable-native-access=ALL-UNNAMED` to `applicationDefaultJvmArgs`.

Regression coverage:

- `PublicInstallPackagingContractTest.generatedLaunchersAllowNativeAccessForBundledTerminalAndIndexLibraries`
  failed before the flag was added and passed after the fix.

Verification:

```text
.\gradlew.bat test --tests "dev.talos.release.PublicInstallPackagingContractTest.generatedLaunchersAllowNativeAccessForBundledTerminalAndIndexLibraries" --no-daemon
.\gradlew.bat test --tests "dev.talos.release.PublicInstallPackagingContractTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.docs.TicketHygieneTest" --no-daemon
.\gradlew.bat clean installDist --no-daemon
wsl.exe -e bash -lc 'cd /mnt/c/Users/arisz/Projects/LOQ/loqj-cli && bash tools/install-unix.sh --force'
wsl.exe -e bash -lc 'cd ~/talos-wsl-install-smoke && /home/ai21z/.local/talos/bin/talos status --verbose ...'
```

Installed WSL evidence:

```text
DEFAULT_JVM_OPTS='"-Dfile.encoding=UTF-8" "-Dstdout.encoding=UTF-8" "-Dstderr.encoding=UTF-8" "--enable-native-access=ALL-UNNAMED" "-XX:+UseZGC"'
NATIVE_ACCESS_WARNING_ABSENT
```
