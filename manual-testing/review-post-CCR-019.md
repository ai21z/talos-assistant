# Test-output review — after CCR-019

Transcript: `manual-testing/test-output` (2523 lines, same session from
`talos-0.9.0-beta` startup at 23:21:31 through Turn 7 at 23:28:08).

---

## 1. CCR-019 (compaction-on-failure data-loss) — VERIFIED WORKING

No failed-compaction-with-prune event in this run.

| Event | Line | Succeeded? | Pruned? | Correct? |
|---|---|---|---|---|
| Compaction after Turn 4 | 1276–1278 | yes, `6 turns → 324 char sketch` | yes, `pruned 6 turns` | ✅ |
| Compaction after Turn 7 | 2367–2369 | yes, `12 turns → 526 char sketch` | yes, `pruned 12 turns` | ✅ |

The `qwen3:8b` model-not-found at line 41 did **not** this time cascade
into a failed-compaction, because the user immediately switched to
`qwen2.5-coder:14b` before any further turn ran. So the exact failure
path from the prior transcript is not re-executed here, but the
CCR-019 gate is still correctly in place — both observed compactions
went through the success branch and pruned only after a non-blank
sketch was produced.

Conclusion: CCR-019 fix does **not** regress normal compaction and is
working as the contract now states. The defensive gate remains
necessary for future failure cases.

---

## 2. Still-confirmed defects with new evidence

### 2.1 Partial-success premature stop (P0) — **fires again**

- **Line 1013**: `ToolCallRepromptStage -- P0: skipping re-prompt after
  1 successful mutation(s) this iteration`
- Context: iteration 2 of Turn 3. `style.css` edit succeeded (line
  903). `index.html` edit in the same iteration **failed** with a
  useful error ending in the suggestion `Consider using talos.write_file
  with the complete updated file content instead` (line 1012). The
  loop then stopped because one mutation succeeded, leaving
  `index.html` unchanged.
- Summary line: `[Used 8 tool(s): … | 2 iteration(s)] [4 failed]` plus
  a single `✓ Edited style.css`. Three files requested, one written.
- Fix target: `runtime/toolcall/ToolCallRepromptStage.java:17` and
  `runtime/toolcall/ToolCallExecutionStage.java:95`.
- **This is the highest-leverage bug remaining.** Recommend opening
  `CCR-020 — re-prompt on partial mutation failures`.

### 2.2 Placeholder-content resilience — **fires twice**

- **Line 414–434** (iteration 1): Model called `talos.edit_file` three
  times with literal placeholder `new_string` values
  (`<updated_synthwave_theme_styles>`, `…_html>`, `…_js>`).
  `TemplatePlaceholderGuard` correctly rejected all three. ✅
- **Line 980** (iteration 2): Model called `edit_file` on index.html
  with an `old_string` it had *not* actually read — the file on disk
  was a boilerplate placeholder (`<!-- Your HTML content here -->`),
  not the long "Music Player" HTML the model invented. The guard does
  not catch this shape because neither argument is the literal
  `<updated_foo>` pattern; the model just fabricated the "old" content.
- Same class of error repeated at Turn 7, lines 2108 and 2195: both
  `edit_file` calls provided hallucinated `old_string` values.
- Fix surface:
  - `TemplatePlaceholderGuard` check is insufficient against fabricated
    content; a structural check against the last `read_file` output
    would catch this.
  - The error message *does* already suggest `write_file` after repeat
    failures (line 1012) — the model just doesn't follow the hint in
    the same iteration because of the partial-success stop (§2.1).

### 2.3 Persona drift — **fires hard**

- **Line 1751** (Turn 6): `"I'm sorry for the misunderstanding. As an
  AI language model, I don't have direct access to edit files on your
  system."`
- Occurs right after the compaction pruned history down to 5 turns
  (`buildMessages: including 5 history turns (2 exchanges)` at line
  1748). Consistent with the earlier hypothesis that persona drift
  correlates with post-compaction turns where the system prompt is
  present but recent tool-use evidence is not.
- Turn 7 recovers — the user's explicit "I want YOU to make the
  changes!" does snap it back to tool-shaped output and partial
  execution.

### 2.4 Bare JSON reaching the terminal

- Turn 3 response (lines 307–406) is a long, user-visible prose that
  contains 6 fenced tool-call JSON blocks before the runtime parses
  them and executes. The filter is suppressing fenced JSON at the
  *output* layer? The transcript shows these blocks *did* reach the
  user visually (they are in the streamed text). Need to re-confirm
  against `ToolCallStreamFilter` behavior with fenced blocks — this
  looks like the fenced path is also leaking, not only the bare path.
- Distinct from the prior bare-JSON evidence. Add to CCR-021 scope
  (`ToolCallStreamFilter` fenced + bare suppression review).

### 2.5 Schema drift

- **Lines 2337, 2353**: model emitted `"function_name"` and
  `"file_path"` instead of `"name"` and `"path"`. This happened in
  prose only, not as executed tool calls, so no harm done — but it is
  a signal that `qwen2.5-coder:14b` is mixing schemas from other tool
  APIs. Keep in the watch list.

---

## 3. Newly observed pattern — History drop after compaction

After Turn 4 compaction pruned 6 turns, the next `buildMessages` at
Turn 6 reports `including 5 history turns (2 exchanges)`. That is the
"history collapse" symptom from the prior transcript — now confirmed
to be a consequence of normal compaction, not a separate bug. The
sketch at `324 chars` is carrying the older context.

This is **not** a defect, but it is worth documenting: persona drift
§2.3 happened on the first turn after this drop. Hypothesis to test:
tool-use reinforcement in the system prompt or the sketch is not
strong enough to survive a compaction boundary.

---

## 4. Proposed next tickets (branch targets, not yet opened)

| Ticket | Title | Target seams | Priority |
|---|---|---|---|
| **CCR-020** | Re-prompt on partial mutation failures | `ToolCallRepromptStage.java:17`, `ToolCallExecutionStage.java:95` | P0 — repeatedly demonstrated, leaves workspace in known-inconsistent state |
| CCR-021 | Structural check that `edit_file.old_string` came from a recent `read_file` | `TemplatePlaceholderGuard` or new `OldStringGroundingCheck`, plus `ReadFileTool` accounting | P1 — high false-negative rate of the current placeholder guard |
| CCR-022 | Persona drift probe + prompt reinforcement at sketch boundary | `prompts/sections/unified-rules.txt`, `ConversationManager` sketch insertion | P1 — reproduced once, correlates with post-compaction turns |
| CCR-023 | Review `ToolCallStreamFilter` suppression for fenced code blocks | `ToolCallStreamFilter.java:78,:204` | P2 — cosmetic + trust hazard |
| CCR-024 | Register a synonym / schema-drift rejection for `function_name`/`file_path` | `ToolCallParser.java:98,:151` + `ToolRegistry` dispatch | P2 — cheap correction, not yet causing real execution errors |

My recommendation: open **CCR-020 first** — it is a workspace-integrity
bug (one file edited, another silently skipped) that is trivially
reproducible from this transcript, and the fix is localized to the
re-prompt gate.

---

## 5. Headline summary

1. **CCR-019 confirmed deployed and behaving correctly.** No
   history-loss events; both compactions succeeded and pruned.
2. **Partial-success premature stop is still firing.** Same log line,
   same consequence (inconsistent multi-file state). This is the next
   ticket to land.
3. **Placeholder-content resilience is now the #2 problem.** The guard
   catches `<literal_placeholder>`, but not `old_string` values the
   model fabricated out of thin air; these are the majority of the
   `edit_file` failures.
4. **Persona drift reproduced once**, aligned with the compaction
   boundary. Not yet ticket-ready — need one more reproduction on
   another model to confirm it is not a one-off.

---

## 6. Ticket set from the later manual review

These are the ticket definitions that survived the deeper re-review of
`manual-testing/test-output`. They supersede the provisional list above
for the specific later transcript problems.

### CCR-024 — Text tool parser should accept `function_name` alias in JSON tool calls

- Problem:
  Turn 8 emitted bare JSON tool payloads using `function_name` instead
  of `name`, for example:

  ```json
  {
    "function_name": "talos.read_file",
    "arguments": { "path": "index.html" }
  }
  ```

  Talos did not enter the text tool-call loop, so the turn became a
  silent no-op instead of executing the intended file operations.
- Evidence:
  `manual-testing/test-output`, Turn 8 (`Update the website's files
  yourself do not give me instructions please`) shows repeated JSON
  tool payloads using `function_name` and no subsequent
  `Tool calls detected` / tool-loop execution.
- Source confirmation:
  `ToolCallParser` bare/fenced JSON detection accepts `name`,
  `function`, `tool_name`, and `tool` but does **not** accept
  `function_name`.
- Why it matters:
  This is a parser compatibility defect, not a model refusal. The model
  attempted tool output, but Talos silently ignored a common alias
  shape.
- Acceptance criteria:
  - `ToolCallParser.containsToolCalls(...)` recognizes JSON payloads
    using `function_name`
  - `ToolCallParser.parse(...)` extracts tool calls from bare JSON and
    fenced JSON using `function_name`
  - add regression tests for transcript-shaped examples
  - malformed payloads should still log and skip cleanly rather than
    failing the whole turn
- Notes:
  Limited to alias compatibility. Does not cover malformed JSON
  escaping in large `edit_file` payloads.

### CCR-025 — Add convergence / fixed-point termination for partial-success tool loops

- Problem:
  Talos can get stuck in a partial-success reprompt loop where one
  mutation succeeds, another repeatedly fails, and the loop continues
  until iteration limit without recognizing that it is oscillating or
  making no net progress.
- Evidence:
  `manual-testing/test-output`, Turn 13 (`But you didnt edit it. You
  have to edit the files so the website is for the horror synthwave
  band!`) shows:
  - 10 iterations
  - 20 tool calls
  - 6 failed calls
  - `index.html` title flipping back and forth across iterations
  - `style.css` edit failing once, then being duplicate-skipped on later
    iterations
  - `script.js` edit payload malformed each iteration and never
    executing
  - final model summary claiming files are back in original state, which
    is factually wrong
- Source confirmation:
  `ToolCallLoop` / `ToolCallRepromptStage` have no higher-level
  workspace-state or semantic fixed-point detection; they rely on
  per-call duplicate skipping, no-more-tools, or iteration limit.
- Why it matters:
  This is a harness-correctness problem, not just answer quality.
  Talos can spend the full loop budget while producing the wrong final
  state and a false terminal summary.
- Acceptance criteria:
  - detect no-progress / oscillation patterns across iterations of the
    same turn
  - terminate early with a truthful summary when the loop is no longer
    converging
  - distinguish between:
    - productive partial-success retries
    - repeated same failure pattern
    - file-state oscillation / revert-flip behavior
  - add regression coverage for a transcript-shaped partial-success
    loop
  - final answer must not falsely claim all requested changes were
    completed when they were not
- Notes:
  Adjacent to CCR-020, not a duplicate of it. CCR-020 fixes premature
  exit after partial success; this ticket prevents pathological
  non-converging retries.

### CCR-026 — Prevent unsolicited or over-scoped mutations on diagnostic and narrowly scoped requests

- Problem:
  Talos sometimes mutates files even when the user asked only for
  diagnosis, or makes broader changes than the user explicitly asked
  for.
- Evidence:
  - Turn 1 prompt: `Read style.css and then change only the body
    background to black.`
    Observed behavior: eventual fallback `write_file` succeeded, but the
    written CSS contained much more than the requested single
    background-color change.
  - Turn 5 prompt: `What is wrong with this website? Read the relevant
    files first.`
    Observed behavior: model read files, started diagnosis, then emitted
    an `edit_file` inserting sample body content even though no mutation
    was requested.
- Why it matters:
  This is separate from tool-selection bias. A model can choose the
  right tool and still violate user scope. For Talos as a harness,
  mutating more than requested is a correctness defect.
- Acceptance criteria:
  - diagnosis requests should not mutate files unless the user
    separately asks for changes
  - explicit narrow-scope requests such as `only change X` should avoid
    broad rewrites unless a larger rewrite is strictly necessary and
    explained
  - add transcript-shaped regression coverage for:
    - diagnosis-only prompt -> no mutations
    - narrow-scope edit prompt -> change confined to requested scope
  - final response should accurately state when no file changes were
    made
- Notes:
  Likely needs a mix of prompt guidance and runtime guardrails. The
  ticket is about behavior, not a single required implementation.

### CCR-027 — Rebalance `write_file` vs `edit_file` guidance for multi-line and multi-file modification requests

- Problem:
  Talos shows a strong tendency to start with `talos.edit_file` on
  direct modification requests, even when the change is multi-line,
  multi-file, or likely to be more reliable as a full-file rewrite.
- Evidence:
  `manual-testing/test-output` shows repeated `edit_file` first-line
  behavior on turns asking to change, update, fix, or apply
  modifications. Many of these fail because `old_string` does not
  exactly match current file contents.
- Source confirmation:
  Base prompt sections explicitly instruct `talos.edit_file` for
  existing-file edit requests, while runtime nudges toward
  `write_file` only reactively after repeated `edit_file` failures.
- Why it matters:
  The current setup appears to bias the model toward brittle exact-match
  patching, especially on local coder models. `write_file` often becomes
  the safer choice only after avoidable failures.
- Acceptance criteria:
  - review and adjust prompt/tool guidance so multi-line or multi-file
    changes are not overly biased toward `edit_file`
  - preserve `edit_file` for genuinely small targeted patches
  - consider proactive guidance toward `write_file` when:
    - the request implies a substantial rewrite
    - the model is modifying multiple files
    - prior exact-match edit failures already occurred in the turn
  - add regression coverage that demonstrates improved first-tool
    selection on transcript-shaped change requests
- Notes:
  About tool-selection guidance, not parser compatibility or convergence
  handling.

