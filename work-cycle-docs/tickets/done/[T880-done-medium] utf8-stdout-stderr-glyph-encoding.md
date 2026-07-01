# [T880-done-medium] UTF-8 stdout/stderr encoding for interactive lane glyphs

Status: done
Priority: medium

## Evidence Summary

- Source: owner manual REPL testing (lane glyphs rendering as `?`)
- Date: 2026-06-26
- Talos version / commit: 0.10.6 / bb8b659a
- Model/backend: not applicable (launcher/encoding)
- Verification status: source-verified

## Findings

In the interactive REPL the lane glyphs render as `?`: `•` (the `route` line, via
`ProgressLineRenderer.route()` -> `SemanticGlyphSet` bullet) and `→` (tool-step
lines, `ProgressLineRenderer.tool(..., "executing", ...)` arrow). The `?` is the
JVM's charset-replacement character, not a terminal-font issue.

Cause: the launcher sets `-Dfile.encoding=UTF-8` but NOT `-Dstdout.encoding` /
`-Dstderr.encoding` (`build.gradle.kts:764-767`). On Java 18+ (the build runs Java
21), `System.out` uses `stdout.encoding`, which defaults to the Windows console
code page (for example cp1252) when unset -- and that code page cannot encode
`•`/`→`, so the JVM substitutes `?` before the bytes ever reach the terminal.
Talos correctly detects the terminal as Unicode-capable (which is why it emits the
Unicode glyphs at all); only the output charset is wrong. The bug is invisible in
piped/non-interactive output because that path falls back to ASCII glyphs
(`*`, `->`, `|`).

Impact: cosmetic (decorative progress markers, no functional effect), but it is the
first thing a user sees on the headline interactive surface, so it undermines the
polished/trustworthy impression.

## Goal

Interactive Unicode lane glyphs render correctly on a Unicode-capable Windows
terminal; the ASCII fallback for degraded/piped modes is unaffected.

## Likely code areas

- `build.gradle.kts` (`application { applicationDefaultJvmArgs = ... }`, lines 762-768)

## Implementation Notes

```text
Add -Dstdout.encoding=UTF-8 and -Dstderr.encoding=UTF-8 to applicationDefaultJvmArgs
alongside the existing -Dfile.encoding=UTF-8. Then System.out emits real UTF-8 and a
UTF-8-capable terminal draws the glyphs. Verify on a real Windows terminal (the bug
does not reproduce in piped/non-interactive output, which uses ASCII glyphs). Only
add a console-codepage fallback (e.g. chcp) if a target terminal proves non-UTF-8.
```

## Non-Goals

- No change to `SemanticGlyphSet` or the terminal-capability detection.
- No bypassing approval, permission, checkpoint, trace, or verification.

## Acceptance Criteria

- `talos run` shows `•`/`→` (not `?`) in the route/tool-step lines on a UTF-8-capable Windows terminal.
- The ASCII-glyph fallback used in degraded/piped/NO_COLOR/dumb-terminal modes is unchanged.
- No regressions to privacy, permissions, checkpointing, trace redaction, or outcome truth.

## Tests / Evidence

```text
Manual: refresh the global install (gradlew installDist + mirror), run `talos run`,
confirm the route/tool-step lane glyphs render as bullet/arrow rather than `?`.
Encoding is launcher-level; no unit test asserts terminal rendering, so the evidence
is the manual transcript + a screenshot.
```

## Work-Test Cycle Notes

- Inner dev loop; no version bump. Add a one-line `## [Unreleased]` CHANGELOG entry when it lands.
- This was greenlit for an immediate fix during testing but deferred to a ticket under the "freeze the branch, ticket everything" decision (2026-06-26).

## Closeout (2026-06-27, glyphs confirmed)

`build.gradle.kts` `applicationDefaultJvmArgs` now includes `-Dstdout.encoding=UTF-8`
and `-Dstderr.encoding=UTF-8` (alongside the existing `-Dfile.encoding=UTF-8`). The
global install was rebuilt (build 2026-06-27T10:30Z) with the flags confirmed in the
generated launcher:
`DEFAULT_JVM_OPTS="-Dfile.encoding=UTF-8" "-Dstdout.encoding=UTF-8" "-Dstderr.encoding=UTF-8" "-XX:+UseZGC"`.

Acceptance MET: the owner ran `talos run` on a real terminal and confirmed the
route/tool-step lane glyphs render as `•`/`→`/`✓` (not `?`). The ASCII fallback for
non-Unicode/redirected output is unchanged. This was the one acceptance that could
not be verified headlessly (non-interactive output uses the ASCII fallback), so the
owner's visual confirmation is the closing evidence.
