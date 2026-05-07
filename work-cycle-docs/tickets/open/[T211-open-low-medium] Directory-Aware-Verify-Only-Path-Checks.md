# T211 - Directory-Aware Verify-Only Path Checks

Severity: low-medium
Status: open

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

- Tests cover a verify-only prompt asking whether a directory path exists and assert the visible tool surface includes `talos.list_dir`.
- Tests cover a verify-only prompt for file paths and preserve existing `talos.read_file` behavior.
- Tests assert no write/edit/workspace operation tools are visible during verify-only directory checks.
- Focused audit shows directory existence can be verified without relying on a `read_file` directory error.

## Non-Goals

- Do not implement delete or chmod-style tools.
- Do not change protected-read behavior.
- Do not claim task-specific verification when only read-only path evidence was gathered.
