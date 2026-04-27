# [done] Ticket: Streaming Protocol Fence And Pre-Tool Prose Display Hygiene

Date: 2026-04-25
Priority: medium
Status: done
Architecture references:
- `work-cycle-docs/tickets/done/talos-streaming-bare-tool-json-display-hygiene.md`
- `docs/new-architecture/29-v1-scenario-pack.md`
- `work-cycle-docs/work-test-cycle.md`

## Why This Ticket Exists

Installed Talos manual verification after the minimal failure-policy slice still
showed user-visible stream debris before the tool loop took over.

The final answer was safe and truthful, approval denial stopped cleanly, and no
raw `"name"` / `"arguments"` Talos tool-call JSON object appeared. However, the
live transcript showed:

- empty streamed ```json fences
- speculative prose before tool execution, including "let's assume the relevant
  section looks like this"

This is not the same as raw final-answer JSON leakage. It is live stream display
hygiene.

## Problem

The stream filter suppresses bare Talos tool-call JSON objects, but the live
terminal can still show surrounding protocol scaffolding or model prose that is
part of an unfinished tool-call attempt.

That creates noisy and misleading terminal output before the controlled
post-tool final answer is rendered.

## Goal

Suppress empty protocol fences and clearly pre-tool speculative tool-call prose
from the live stream without hiding normal user-relevant prose or non-tool JSON
examples.

## Scope

### In scope

- Extend `ToolCallStreamFilter` or adjacent stream-display handling.
- Suppress empty ```json fences that are immediately associated with tool-call
  detection.
- Consider buffering/suppressing obvious pre-tool speculative prose only when a
  tool call is detected in the same streamed answer.
- Preserve final-answer safety behavior.
- Add deterministic tests for empty fence suppression and normal prose
  preservation.

### Out of scope

- Parser changes for final-answer tool-call extraction.
- Runtime approval/failure policy.
- Broad UI redesign.
- Hiding legitimate non-tool JSON examples.

## Likely Files / Areas

- `src/main/java/dev/talos/runtime/ToolCallStreamFilter.java`
- `src/test/java/dev/talos/runtime/ToolCallStreamFilterTest.java`
- installed CLI manual verification transcript

## Acceptance Criteria

- empty streamed ```json fences do not appear when they are protocol debris
- raw Talos tool-call JSON still does not appear
- ordinary non-tool JSON examples still display
- ordinary prose still displays
- installed Talos transcript is cleaner without changing final-answer truth

## Completion Notes

- Tightened `ToolCallStreamFilter` so partial code-fence prefixes are held
  correctly across character-by-character chunks.
- Suppressed complete empty `json` fences, blank incomplete `json` fences, and
  adjacent empty-fence + tool-JSON protocol shapes.
- Suppressed malformed bare Talos protocol JSON when the top-level protocol
  signature is visible but JSON parsing fails.
- Held back tool-loop follow-up model prose from live streaming; tool progress
  remains visible and final answers still go through centralized outcome
  shaping.
- Preserved ordinary prose, ordinary non-tool JSON, and generic code fences.

## Verification

- `./gradlew.bat test --tests "dev.talos.runtime.ToolCallStreamFilterTest" --tests "dev.talos.cli.modes.AssistantTurnExecutorTest" --tests "dev.talos.runtime.ToolCallLoopTest"`
- `./gradlew.bat test --tests "dev.talos.runtime.ToolCallParserTest" --tests "dev.talos.runtime.NativeToolPipelineTest"`
- `./gradlew.bat test`
- `./gradlew.bat e2eTest`
- `./gradlew.bat check`
- Installed CLI verification in `local/playground/horror-synth-site`, transcript
  captured at `local/manual-testing/test-output`.

Manual transcript result:
- no visible empty `json` fence debris
- no visible raw `"name"` / `"arguments"` Talos protocol object
- no unsupported no-mismatch prose leaked before the grounded final answer
- approval denial prevented writes and stopped after one failed mutation
- tracked playground files remained unchanged
- session saved cleanly

Residual follow-up:
- Medium UX debt: malformed `edit_file` arguments with empty `old_string` /
  `new_string` can still reach the approval prompt before tool execution rejects
  them. This should be tracked separately as pre-approval mutating-tool
  argument validation.
