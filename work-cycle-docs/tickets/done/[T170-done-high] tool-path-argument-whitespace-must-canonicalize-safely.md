# T170 - Tool Path Argument Whitespace Must Canonicalize Safely

Status: done

Severity: high

Source audit:
- `local/manual-testing/llama-cpp-t61h-full-audit-20260506-191922/FINDINGS-LLAMA-CPP-T61H-FULL-AUDIT.md`

## Problem

GPT-OSS under managed llama.cpp repeatedly called `talos.read_file` with a
leading-space path, ` .env`, instead of `.env`.

Talos handled this safely from a privacy standpoint: no protected content was
leaked. But the approved protected read still failed because the runtime treated
the obvious accidental whitespace as a literal path and did not canonicalize or
retry against the intended existing protected target.

This is an agent reliability problem at the tool boundary. Model-generated tool
arguments can contain small formatting errors. The runtime should be strict
about security, but forgiving about harmless accidental path whitespace when it
can do so without bypassing permission policy.

## Evidence

- `TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt:1946`
  - user asks to read `.env`
- `TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt:2001-2004`
  - first tool call targets ` .env` and fails `NOT_FOUND`; second targets `.env`
    and is approval-denied
- `TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt:2510`
  - user retries and approves the protected read
- `TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt:2531-2538`
  - Talos reports protected read incomplete; no protected content returned
- `TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt:2552-2558`
  - three tool calls target ` .env` and fail `NOT_FOUND`

## Scope

In scope:
- Add a centralized normalization/canonicalization step for file path tool
  arguments where leading/trailing whitespace is clearly accidental.
- Preserve security policy: if `trim(rawPath)` resolves to a protected path,
  require protected-read approval for the trimmed canonical path before reading.
- Record the raw model path and normalized runtime path in trace/debug output
  when normalization changes the path.
- Apply consistently to read/write/edit/move/copy style workspace path tools
  where safe.
- Keep nonexistent intentional whitespace paths distinguishable if the exact raw
  path exists.

Out of scope:
- No fuzzy filename correction.
- No automatic substitution of similar names such as `script.js` for
  `scripts.js`.
- No relaxation of protected read approval.

## Acceptance

- `talos.read_file` with raw path ` .env` is normalized to `.env` only after
  confirming the exact raw path does not exist and the trimmed path does exist.
- A normalized protected target still prompts for protected-read approval.
- After approval, the read succeeds through the canonical protected path.
- If approval is denied, no content is shown and the failure reason names the
  canonical protected path.
- Trace/debug output includes both raw and normalized paths.
- Tests cover protected read, ordinary read, nonexistent paths, and an exact
  existing whitespace-named path if the platform allows constructing one in the
  test fixture.
- `.\gradlew.bat --no-daemon check installDist` passes.

## Resolution

- Added centralized path argument canonicalization for accidental leading/trailing whitespace.
- Preserved exact whitespace-named files when the raw path exists.
- Canonicalized protected read classification so ` .env` is treated as canonical `.env` only after confirming `.env` exists.
- Recorded `TOOL_PATH_ARGUMENT_NORMALIZED` trace events with raw and normalized path values.
- Kept denied approval output failure-dominant, leak-free, and anchored to the canonical target path without permission-themed wording.

## Verification

- `./gradlew.bat test --tests dev.talos.runtime.TurnProcessorDenialWordingTest --tests dev.talos.tools.impl.ReadFileToolTest --tests dev.talos.runtime.policy.ProtectedPathPolicyTest --tests dev.talos.runtime.ApprovalGatedToolTest`
- `./gradlew.bat test --tests dev.talos.tools.impl.* --tests dev.talos.runtime.policy.* --tests dev.talos.runtime.TurnProcessor* --tests dev.talos.runtime.ApprovalGatedToolTest`
- `./gradlew.bat check`
- `./gradlew.bat installDist`
