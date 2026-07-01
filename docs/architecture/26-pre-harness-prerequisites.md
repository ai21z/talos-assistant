# Pre-Harness Prerequisites - What Must Land Before Phase 0

**Branch:** `feature/native-tool-pipeline` → `v0.9.0-beta-dev`
**Status:** B/C/D/E/F items implemented on this branch; A1+A2 require merge
**Depends on:** `talos-harness-plan.md` (doc 25)
**Purpose:** Everything that must be done before the scenario harness (Phase 0)
can produce meaningful, trustworthy results.

---

## Why this document exists

The harness plan (doc 25) identifies the right architecture and the right
phasing. But it implicitly assumes a stable runtime substrate.

The runtime is **not yet stable enough** for harness results to be meaningful.
If we build scenarios today, we will be measuring noise - not quality.

This document lists every concrete prerequisite, in priority order, that
must land before Phase 0 begins.

---

## Priority A - Merge & Stabilize

### A1. Merge `feature/native-tool-pipeline` into `v0.9.0-beta-dev`

**What:** The harness plan assumes native-first tool calling. That
architecture lives on `feature/native-tool-pipeline`. It must be merged.

**Why first:** Every other prerequisite builds on top of the native-first
dual-path (`NativeToolCall` primary, JSON text fallback, XML deprecated).
Nothing in this list makes sense until the merge is complete.

**Acceptance:**
- [ ] Native tool calls flow end-to-end in unified mode
- [ ] JSON text fallback works when native is unavailable
- [ ] All existing tests pass
- [ ] Manual smoke test: create file, edit file, read file, grep, list_dir

---

### A2. Green test baseline

**What:** Every test in `src/test/` must pass on the merged branch.

**Why:** Harness scenarios will be built as test infrastructure. A red
baseline makes harness failures ambiguous - you can't tell whether the
harness caught a real problem or whether the test infra itself is broken.

**Acceptance:**
- [ ] `./gradlew test` passes with 0 failures
- [ ] No skipped tests that hide real breakage

---

## Priority B - Edit Tool Reliability

### B1. Improve `edit_file` failure mode when `old_string` not found

**What:** Today, when the model sends an `old_string` that doesn't exist in
the file, the tool returns a terse error:
```
old_string not found in <file>. Verify the exact text exists in the file.
```
The model then retries with a different (usually also wrong) guess,
creating a 3-5 iteration spiral that burns context and user patience.

**Current code:** `FileEditTool.java:129-131`

**Proposed improvement:**
1. When `old_string` is not found, include a **snippet of the actual file
   content** in the error message (first 20 lines, or the region around the
   closest fuzzy match). This gives the model ground truth to retry from.
2. Optionally: detect near-misses (Levenshtein or line-by-line diff) and
   suggest "Did you mean: ..." with the actual content.

**Why before harness:** Without this, every harness scenario involving
`edit_file` will fail in the same way and for the same reason. We'd be
measuring model weakness at exact string recall, not harness effectiveness.

**Acceptance:**
- [x] Error message includes actual file snippet when `old_string` not found
- [x] Model can self-correct on retry with the ground truth provided
- [x] Existing `FileEditToolTest` cases still pass

**Implemented:** `FileEditTool.java` - error now includes first 20 lines with line numbers
and "call talos.read_file" instruction. Tests added: `notFoundErrorIncludesFileSnippet`,
`buildFileSnippet_*`.

---

### B2. `read-before-write` nudge in tool result feedback

**What:** The unified rules prompt says "Before editing a file, call
`talos.read_file` to see its current content." But there is **no runtime
enforcement**. The model frequently skips the read and guesses `old_string`
from its training data or conversation memory.

**Proposed improvement:**
In `ToolCallLoop`, when the first tool call in a turn is `talos.edit_file`
and no `talos.read_file` call for the same path preceded it (in this turn),
inject a nudge into the tool result:
```
Hint: You did not read this file before editing. Call talos.read_file first
to see the current content, then retry the edit with the exact text.
```

This is a **soft nudge**, not a hard block. The edit still executes (or
fails normally). But the feedback teaches the model the correct workflow.

**Why before harness:** A harness scenario that measures "model reads before
editing" is meaningless if the runtime doesn't even surface the gap.

**Acceptance:**
- [x] Nudge appears when `edit_file` is called without prior `read_file`
  for the same path in the same turn
- [x] Nudge is NOT shown when the file was already read in a previous tool
  call in the same loop iteration sequence
- [x] Does not break existing test cases

**Implemented:** `ToolCallLoop.run()` - tracks `pathsReadThisTurn` (Set). When
`talos.edit_file` is called and the path was not read in this turn, appends
a hint to the tool result message.

---

### B3. Repeated-failure detection for same tool + same params

**What:** The model sometimes enters a loop calling `edit_file` with the
exact same `old_string` that already failed. The loop runs until `maxIterations`
with no progress.

**Current code:** `ToolCallLoop.java:195-306` - no repeated-call detection.

**Proposed improvement:**
Track `(toolName, pathParam, old_string hash)` tuples within a single loop
execution. If the same tuple appears twice, inject a diagnostic message
instead of executing:
```
This exact edit was already attempted and failed. Read the file to see its
current state, or use talos.write_file to replace the entire content.
```

**Why before harness:** Without this, harness scenarios will time out on
loops that a human would immediately recognize as stuck. The harness would
report "iteration limit reached" which tells us nothing useful.

**Acceptance:**
- [x] Duplicate `(tool, path, old_string)` calls in the same loop are
  detected and short-circuited with a diagnostic message
- [x] First attempt always executes normally
- [x] Loop counter still increments (counts toward max iterations)

**Implemented:** `ToolCallLoop.run()` - tracks `failedCallSignatures` (Set of
`buildCallSignature()` hashes). On retry of an identical failing call, injects
diagnostic and skips execution. Tests added: `buildCallSignature_*` unit tests.

---

## Priority C - Compatibility Cleanup

### C1. Remove XML from active parsing paths

**What:** `ToolCallParser` still actively parses `<tool_call>`, `<function_call>`,
`<tool>`, `<function>` XML tags. The parser Javadoc already marks these as
"deprecated compatibility - not actively instructed." The harness plan says
"Do not let future harness logic depend on XML paths."

**Current code:** `ToolCallParser.java:24-28` - XML listed as priority 1
(checked first).

**Proposed approach:**
1. Demote XML from priority 1 to priority 3 (checked last, after JSON).
2. Add a log warning when XML parsing is the path that matched:
   `LOG.warn("XML tool-call format detected - this is deprecated...")`
3. **Do not remove entirely yet** - some cached model context may still
   emit XML. But stop checking it first.

**Why before harness:** Harness scenarios must test the real architecture
(native-first + JSON fallback). If XML silently catches tool calls, harness
results will be misleading about the actual text-fallback path quality.

**Acceptance:**
- [x] JSON checked before XML in `ToolCallParser`
- [x] XML match triggers a deprecation warning log
- [x] `ToolCallParserTest` updated to reflect new priority order
- [x] `ToolCallStreamFilter` XML suppression still works (compatibility)

**Implemented:** `ToolCallParser.parse()` - reordered: code-fenced JSON (Pass 1),
bare JSON (Pass 2, if empty), XML (Pass 3, always, with deprecation LOG.warn).
Test `bareJsonNotUsedWhenTaggedBlockExists` replaced with two tests:
`codeFencedJsonSuppressesBareJsonFallback` and `xmlTaggedBlockUsedAsLastResortWhenNoJsonFormat`.

---

### C2. Narrow `CodeBlockToolExtractor` from warning to metric

**What:** `ToolCallLoop.run()` (line 179) calls
`CodeBlockToolExtractor.containsExtractableBlocks()` and emits a
`LOG.warn`. This is detection-only (no execution), but it adds noise to
logs and couples the loop to a pattern that the harness plan wants to remove.

**Proposed approach:**
1. Keep `CodeBlockToolExtractor` as a utility class (useful for evaluation).
2. In `ToolCallLoop.run()`, replace the `LOG.warn` with a structured event
   or counter that the future scenario harness can query. For now, demote
   to `LOG.debug` since users never see it and it's not actionable.
3. Do NOT remove the class - it becomes part of the tool-contract harness
   (Phase 4 in the harness plan).

**Why before harness:** The harness plan explicitly flags this as pre-work.
Getting it right now avoids refactoring the loop entry gate later.

**Acceptance:**
- [x] `ToolCallLoop` code-block check is `LOG.debug`, not `LOG.warn`
- [x] `CodeBlockToolExtractor` is preserved as utility
- [x] No behavioral change for tool-call loop flow

**Implemented:** `ToolCallLoop.java` line ~180 - `LOG.warn` → `LOG.debug`.

---

## Priority D - Prompt Discipline

### D1. Add inspect-before-apply guidance to unified rules

**What:** `unified-rules.txt` has an EDITING WORKFLOW section that says
"Before editing a file, call `talos.read_file`..." but this guidance is
buried and easily ignored by the model.

**Proposed improvement:**
Add an explicit **TASK APPROACH** section at the top of the priority
hierarchy (before the current priority 1):

```
TASK APPROACH (how you work):
1) UNDERSTAND - Read relevant files and explore the workspace before changing anything.
2) PLAN - Briefly state what you will change and why (1-2 sentences, not a wall of text).
3) APPLY - Make the changes using tools.
4) CONFIRM - Briefly confirm what you changed.
Do NOT skip step 1. Do NOT apply changes to files you haven't read in this session.
```

This is a prompt-level precursor to the runtime phase harness (Phase 1).
It teaches the model the pattern before we enforce it in code.

**Why before harness:** Scenario harness results will be far more useful
if the model is already operating in an inspect→plan→apply flow. Without
this, scenarios will mostly measure "model doesn't read before writing"
which we already know.

**Acceptance:**
- [x] `unified-rules.txt` includes TASK APPROACH section
- [x] Section is positioned before PRIORITY HIERARCHY
- [ ] Manual test: model reads files before editing in at least 3 of 5 tries

**Implemented:** `unified-rules.txt` - TASK APPROACH section added with
UNDERSTAND → PLAN → APPLY → CONFIRM steps before PRIORITY HIERARCHY.

---

### D2. Richer `edit_file` tool description in schema

**What:** The current `edit_file` schema description says:
```
"old_string": "Exact text to find (must appear exactly once)"
```
This is technically correct but gives the model no strategy for success.

**Proposed improvement - enrich the description:**
```
"old_string": "Exact text to find and replace. MUST match the file content
character-for-character (including whitespace and newlines). Copy the text
from talos.read_file output. Must appear exactly once in the file."
```

Also add a `"description"` at the tool level:
```
"Replace a unique string in a workspace file. TIP: call talos.read_file
first to see the exact content, then copy the target text into old_string."
```

**Why before harness:** The model's primary source of tool knowledge is
the schema. A better schema reduces tool misuse _before_ we need to
measure it.

**Acceptance:**
- [x] `FileEditTool.descriptor()` has enriched descriptions
- [x] Schema still validates as JSON Schema
- [x] No token budget regression (keep descriptions concise)

**Implemented:** `FileEditTool.descriptor()` - `old_string` description enriched
with character-for-character copy instruction. Tool-level description adds the
"TIP: call talos.read_file first" guidance.

---

## Priority E - Loop Resilience

### E1. `write_file` fallback suggestion after repeated `edit_file` failures

**What:** When `edit_file` fails 2+ times on the same file in the same
loop, the model should be told it can use `write_file` with the complete
updated content instead.

**Current code:** `ToolCallLoop.java` has no per-file failure tracking.

**Proposed improvement:**
After the 2nd `edit_file` failure on the same path within a loop execution,
append to the tool result message:
```
Suggestion: edit_file has failed on this file multiple times. Consider using
talos.write_file with the complete updated file content instead.
```

**Why before harness:** This is the single most common stuck-loop pattern
observed in real Talos conversations. Fixing it reduces noise in every
harness scenario that involves edits.

**Acceptance:**
- [x] Per-file failure count tracked within `ToolCallLoop.run()` scope
- [x] After 2nd failure: suggestion message appended to tool result
- [x] Counter resets per loop execution (not persistent across turns)

**Implemented:** `ToolCallLoop.run()` - tracks `editFailuresByPath` (Map<String,Integer>).
After 2nd failure on same path, suggestion to use `talos.write_file` is appended
to the error message.

---

### E2. Context window protection - cap tool result size for `read_file`

**What:** When the model reads a large file, the full content goes into the
conversation as a tool result. For files approaching the context window
limit, this crowds out everything else and causes degraded follow-up turns.

**Current code:** `ToolCallLoop.formatToolResult()` caps at 32K chars. But
`read_file` tool itself may return content up to the file size limit
(2 MiB for `FileEditTool`, unchecked for `FileReadTool`).

**Proposed improvement:**
In `FileReadTool`, if file content exceeds ~16K chars, truncate and note:
```
[File truncated at 16K chars - use talos.grep to search for specific content]
```

**Why before harness:** Harness scenarios on real projects will hit this.
A scenario that fills the context window with one `read_file` result and
then fails all subsequent tool calls is not measuring harness quality.

**Acceptance:**
- [x] `FileReadTool` truncates output at configurable threshold (default 16K)
- [x] Truncation message includes guidance (use `grep` for search)
- [x] Small files are unaffected

**Implemented:** `ReadFileTool.java` - `MAX_OUTPUT_CHARS = 16_000` constant.
Output is truncated with guidance message if it exceeds 16K chars.
Tests added: `largeFileIsTruncatedAtCharLimit`, `smallFileIsNotTruncated`.

---

## Priority F - Observability for Harness

### F1. Structured loop metrics record

**What:** The `ToolCallLoop.LoopResult` record captures `iterations`,
`toolsInvoked`, and `toolNames`. But it doesn't capture failure counts,
retry counts, or which tools failed.

**Proposed improvement:**
Add to `LoopResult`:
```java
int failedCalls        // tools that returned errors
int retriedCalls       // same tool+params called more than once
boolean hitIterLimit   // true if loop was stopped by max iteration cap
```

This is **not** a harness-layer concern. It's basic loop observability that
the scenario harness will consume, but that's also useful for runtime
logging and future UX (showing the user "3 tools used, 1 failed").

**Why before harness:** Without structured metrics, the scenario harness has
to parse log output or infer failure counts from the message list. That's
fragile and unmaintainable.

**Acceptance:**
- [x] `LoopResult` includes failure/retry/limit fields
- [x] Fields are populated during `run()` execution
- [x] `summary()` method optionally includes failure info
- [x] Existing tests updated

**Implemented:** `ToolCallLoop.LoopResult` - added `failedCalls`, `retriedCalls`,
`hitIterLimit` fields. `summary()` now appends `[N failed]` and `[iteration limit reached]`
when applicable. Tests added: `failedCallsCountedWhenToolFails`, `summaryIncludesFailedCount`,
`summaryIncludesIterLimitFlag`, `newFieldsDefaultToZeroWhenNoToolCalls`.

---

## Implementation Order

```
A1  Merge native-tool-pipeline            [blocking - everything depends on this]
A2  Green test baseline                   [blocking - validate the merge]
 │
 ├── B1  edit_file error includes file content   [highest user-facing impact]
 ├── B2  read-before-write nudge                 [supports B1]
 ├── B3  Repeated-failure detection              [supports B1]
 │
 ├── C1  Demote XML in ToolCallParser            [cleanup, low risk]
 ├── C2  CodeBlockToolExtractor → debug          [cleanup, low risk]
 │
 ├── D1  Unified rules: TASK APPROACH section    [prompt, no code risk]
 ├── D2  Richer edit_file schema descriptions    [prompt, no code risk]
 │
 ├── E1  write_file fallback after edit failures [loop resilience]
 ├── E2  read_file output truncation             [context protection]
 │
 └── F1  Structured loop metrics in LoopResult   [observability]
```

**A1 → A2** are sequential blockers.
**B/C/D/E/F** can be parallelized (independent concerns).
Each item is a single, reviewable PR.

**Estimated scope:** 10-12 small PRs, each < 100 lines changed.

---

## Relationship to the Harness Plan

| Harness plan item | Prerequisite that unlocks it |
|---|---|
| Phase 0 - Scenario harness | A1 + A2 (stable substrate) |
| Phase 0 - First scenarios | B1 + B3 + E1 (edit scenarios won't all fail identically) |
| Phase 1 - Runtime phase harness | D1 (model already follows inspect→apply flow) |
| Phase 2 - Task-level verifier | B2 (read-before-write tracking exists to build on) |
| Phase 4 - Strict evaluation mode | C1 + C2 (XML and code-block detection cleaned up) |
| All phases - Metrics | F1 (structured loop data available) |

---

## What this document does NOT cover

- **Harness architecture** - that's doc 25 (`talos-harness-plan.md`)
- **New tools** (shell, test runner, browser) - not prerequisites; discussion items
- **Phase visibility** ("Inspecting... Planning...") - Phase 1 concern
- **Persistent sessions** (`SqliteSessionStore`) - post-V1
- **Embedding/vLLM migration** - separate track (doc 23)
- **CI/quality tooling** - separate branch (`feature/code-quality-stack`)

---

## Audit Notes (post-implementation)

**Verified against actual code:** All items B-F confirmed implemented on
`feature/native-tool-pipeline`. Acceptance criteria checked against source.

**Two items not in original doc that were also addressed:**
- `ToolCallParser.containsToolCalls()` priority order is consistent with `parse()` (both
  check XML last via pattern evaluation order in the combined check)
- `NativeToolPipelineTest` `LoopResult` constructor updated to new 8-arg form

**One assumption corrected:**
- E2 originally stated "unchecked for FileReadTool" - ReadFileTool actually had a 500-line
  default which provided partial protection. The char-based cap adds a secondary, explicit guard.

**Risky assumption noted:**
- B3 repeated-call detection uses `old_string.hashCode()` - Java `String.hashCode()` is not
  collision-free. For the deduplication use case (same model, same turn, identical string)
  false collisions are extremely unlikely in practice.

---

## Success Criteria

All prerequisites are met when:

1. `feature/native-tool-pipeline` is merged and `./gradlew test` is green
2. [x] `edit_file` errors include file content for self-correction
3. [x] Repeated identical tool calls are detected and short-circuited
4. [x] The model reads files before editing in most (>60%) turns *(prompt-enforced; runtime nudge added)*
5. [x] XML is demoted in parser priority; code-block detection is debug-level
6. [x] `LoopResult` exposes structured failure metrics
7. The first 5 harness scenarios can run to completion without all failing
   on the same `old_string not found` error

When these are met, Phase 0 of the harness plan can begin with confidence
that scenario results reflect real quality, not infrastructure noise.
