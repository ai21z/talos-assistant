# T211 - Directory-Aware Verify-Only Path Checks

Severity: low-medium
Status: done

## Problem

Verify-only turns that ask about directory paths currently expose only `talos.read_file` in the focused T209 audit. Both models verified `scratch/nested/reports` by calling `talos.read_file` and interpreting:

`Path is a directory, not a file: scratch/nested/reports`

The answers were truthful, but this is a rough verification path. Talos should make directory verification first-class when the user asks to verify paths that may be directories.

## Evidence

Audit:
`local/manual-testing/llama-cpp-t209-focused-re-audit-20260507-231118/`

Examples:
- Qwen final verify: `TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt` around lines 2307-2412.
- GPT-OSS final verify: `TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt` around lines 2513-2603.

Both models reached the correct answer, but only by using `talos.read_file` on a directory path.

## Scope

- For verify-only path checks that include directory-like paths or ask to verify final workspace paths, expose directory-capable inspection tools such as `talos.list_dir`.
- Keep the turn read-only.
- Keep answers grounded in tool output.
- Do not let directory verification become mutation-capable.

## Acceptance

- Done. Tests cover a verify-only prompt asking whether a directory path exists and assert the visible tool surface includes `talos.list_dir`.
- Done. Tests cover a verify-only prompt for file paths and preserve existing `talos.read_file` behavior.
- Done. Tests assert no write/edit/workspace operation tools are visible during verify-only directory checks.
- Done. Focused audit shows directory existence can be verified without relying on a `read_file` directory error.

## Implementation

- Added directory-aware verify-only path detection in `ToolSurfacePlanner`.
- Mixed file/directory verification exposes only `talos.list_dir` and `talos.read_file`.
- Standalone directory existence verification also exposes only `talos.list_dir` and `talos.read_file`, not broad command verification.
- File-only verify prompts retain the existing `talos.read_file`-only expected-target surface.
- Added `[DirectoryAwareVerification]` guidance to the current-turn capability frame when both file and directory inspection tools are visible.
- Added deterministic verify-only path answer shaping so a successful `talos.list_dir` result, including `(empty directory)`, produces grounded visible prose instead of model-authored directory-content guesses.

## Verification

Targeted tests:

```text
.\gradlew.bat test --tests dev.talos.runtime.toolcall.ToolSurfacePlannerTest.verifyOnlyDirectoryPathWithoutFileTargetsUsesNarrowReadOnlyPathSurface --tests dev.talos.runtime.toolcall.ToolSurfacePlannerTest.defaultNamesMatchCurrentPromptFallbackSurfaces --tests dev.talos.runtime.toolcall.ToolSurfacePlannerTest.verifyOnlyMixedFileAndDirectoryPathChecksExposeReadFileAndListDirOnly --tests dev.talos.runtime.toolcall.ToolSurfacePlannerTest.verifyOnlyFilePathChecksKeepExpectedTargetReadSurface --tests dev.talos.runtime.policy.CurrentTurnCapabilityFrameTest.verifyOnlyDirectoryAwareFrameDistinguishesDirectoryAndFileTools --no-daemon
```

Result: pass.

```text
.\gradlew.bat test --tests "*verifyOnlyDirectoryPathSummaryOverridesUngroundedDirectoryContentClaim" --no-daemon
```

Result: pass.

Relevant suite:

```text
.\gradlew.bat test --tests dev.talos.runtime.toolcall.ToolSurfacePlannerTest --tests dev.talos.runtime.policy.CurrentTurnCapabilityFrameTest --tests "dev.talos.cli.modes.AssistantTurnExecutorTest$NonStreaming" --no-daemon
```

Result: pass.

Full build/install:

```text
.\gradlew.bat build installDist --no-daemon
```

Result: pass.

## Focused Audit

Final focused audit:

`local/manual-testing/llama-cpp-t211-focused-re-audit-20260508-000852/FINDINGS-LLAMA-CPP-T211-FOCUSED-RE-AUDIT.md`

Result: pass for both Qwen and GPT-OSS.

Key observed counts across both model transcripts:

```text
visibleTools: talos.list_dir, talos.read_file: 16
talos.list_dir -> scratch/nested/reports [ok]: 4
talos.read_file -> scratch/nested/reports [failed]: 0
Path is a directory, not a file: scratch/nested/reports: 0
talos.run_command ->: 0
talos.write_file ->: 0
talos.edit_file ->: 0
contains files: 0
not shown here: 0
```

## Non-Goals

- Do not implement delete or chmod-style tools.
- Do not change protected-read behavior.
- Do not claim task-specific verification when only read-only path evidence was gathered.
