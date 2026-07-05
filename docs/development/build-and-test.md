# Build and test

Use Java 21 or newer.

Talos uses a narrow inner loop and a wider release loop. During implementation, run the focused tests for the changed area first. Before candidate or release decisions, run the full check from a clean working tree and test the installed product when behavior is user-visible.

Windows:

```powershell
.\gradlew.bat test --no-daemon
.\gradlew.bat check --no-daemon
.\gradlew.bat installDist
.\build\install\talos\bin\talos.bat --version
```

Linux:

```bash
./gradlew test --no-daemon
./gradlew check --no-daemon
./gradlew installDist
./build/install/talos/bin/talos --version
```

Run focused tests first for the area changed. Run the full check before candidate or release decisions.

Do not treat one successful model response as a test. Use traces, tool results, final file state, and deterministic tests.

## Practical order

1. Inspect the relevant code and tests.
2. Add or update a focused regression test when behavior changes.
3. Run the focused Gradle test.
4. Run site tests if public web or docs surfaces changed.
5. Run `git diff --check`.
6. Run `.\gradlew.bat check --no-daemon` before merging or staging release artifacts.

For installed-product checks, verify which `talos` executable your shell resolves before judging behavior.

## Reports

The normal build does not generate reviewer Markdown reports. Generate them only when you need local review evidence:

```powershell
.\gradlew.bat writeQualityMarkdownReports --no-daemon
```

You can also ask `check` to write those snapshots after the automated gate:

```powershell
.\gradlew.bat check -PwithQualityReports=true --no-daemon
```

Before release staging or publication, use the maintainer packet instead:

```powershell
.\gradlew.bat releaseQualityPacket --no-daemon
```
