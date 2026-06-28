# Talos Harness Main Plan

> Status: current primary review + roadmap for Talos harness progress.
> Branch: `v0.9.0-beta-dev` (verified; see §2).
> Last refreshed: 2026-04-17 against HEAD `19a837d` (post-N1, post-N2, post-N3, post-N4).
>
> This is a **truth-refresh** of the prior version of this document. Every
> claim below was re-verified against code on the current branch. Prior
> wording that has been overtaken by landed work is corrected, not preserved.

---

## 1. Executive verdict

The R1-R7 runtime/harness passes that the earlier version of this plan
recommended have now **landed** on `v0.9.0-beta-dev`. The trust-layer story
has moved on:

- The **text-fallback detection-gate asymmetry** that silently dropped
  Turn 6's write intent is closed. `CODE_FENCE_PATTERN` and
  `BARE_JSON_PATTERN` both accept the same alias set the extractor already
  understood (`name | function | tool_name | tool`).
- The **false-mutation claim** category (Turn 5) now triggers a post-turn
  annotation at the executor seam on both streaming and non-streaming
  branches.
- The **long-fabrication-with-zero-tools** failure shape (Turns 2-4) is
  addressed on **both** the non-streaming branch (R6: keyword-gated,
  one-shot grounding retry at ≥ 600 chars) and the streaming branch
  (N2: post-stream grounding annotation with a shared predicate). The
  streaming path is intentionally detect-and-annotate, not retry -
  prose is already on the terminal by the time the gate could fire.
- The **harness** now has answer-content assertions, a strict-mode toggle
  that disables measurement cushions, and the first seed of
  transcript-derived regression coverage.
- **Build provenance** is surfaced both in a startup SLF4J log and in the
  banner, with graceful `unknown` fallbacks - no git-at-runtime dependency.
- The **workspace manifest** was already in code prior to R7. R7 only
  added verification tests. The earlier plan's open question is closed.

What has not moved: cushion observability counters (P7) and
compaction-cadence tuning (P8). With **N3** and **N4** landed, the
last P-level transcript failure shape (Turn 1 under-inspection) has
a runtime gate **and** an executor-seam regression anchor (T1), and
the T5 end-to-end scenario now runs through `execute()` via a
scripted `LlmClient` - closing the last open scope in the transcript
regression set and removing the seam caveat from
`TranscriptRegressions`. What remains open is narrower and
better-characterized than it was last refresh.

Concretely, Talos today is:

- **Trustworthy on mechanics** - unchanged from before; still mature.
- **Materially less untrustworthy on grounding** - every transcript
  trust breach from `test-output.txt` (T1 under-inspection,
  T2/T3/T4 long fabrication on both branches, T5 false mutation,
  T6 lost write) now has runtime coverage **and** a
  transcript-anchored regression test at the executor seam.
- **Measurable on answer text, not just on filesystem** - `ScenarioResult`
  exposes `finalAnswer()` plus `assertAnswerContains / NotContains`;
  strict mode exists to measure behavior with cushions off.

The next leap is no longer "add a truth layer." The truth layer exists
on both streaming and non-streaming branches and for both zero-tool
and with-tools turns. The remaining work is: (a) `LoopResult` cushion
counters so strict-vs-normal deltas are visible without log-grepping
(N5); (b) the infrastructure work on `feature/code-quality-stack`
(N6) plus the small docs refresh (N7).

---

## 2. Truth sources checked

### Git / branch state (verified 2026-04-17)

- `git branch --show-current` → `v0.9.0-beta-dev`
- HEAD commit: `19a837d` - *"N4: harness drives AssistantTurnExecutor +
  T5 end-to-end scenario"*
- `19a837d` ← `32a032b` (N3 inspect under-completion + T1 anchor) ←
  `d2c1701` (N1 transcript anchors) ← `852631a` (N2 streaming
  grounding annotation) ← `d48f44d` (R7 build identity + workspace
  manifest verification) ← `e6a6e8f` (R5 strict-mode) ← `c57bb03`
  (R6 grounding retry) ← `91b5d19` (R3 answer assertions + R4 seed)
  ← `9c97742` (R1 gate widening + R2 claim-vs-action annotation) ←
  `35cdc94` (completion contract: path canonicalization + broader
  deflection gate).
- `origin/v0.9.0-beta-dev` is at `852631a`; HEAD is **three** commits
  ahead (N1 `d2c1701` + N3 `32a032b` + N4 `19a837d` are local,
  pending push).
- Working tree clean at time of refresh.

### Code (re-read this pass)

- `src/main/java/dev/talos/runtime/ToolCallParser.java` - 227 lines;
  `CODE_FENCE_PATTERN` (line 62-65) and `BARE_JSON_PATTERN` (line 68-71)
  both include `(?:name|function|tool_name|tool)`.
- `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java` -
  `MUTATION_CLAIM_MARKERS` (line 352), `FALSE_MUTATION_ANNOTATION`
  (line 379), `annotateIfFalseMutationClaim` (line 420, called at lines
  137 streaming and 170 non-streaming); `UNGROUNDED_MIN_CHARS = 600`
  (line 441), `EVIDENCE_REQUEST_MARKERS` (line 451),
  `UNGROUNDED_ANNOTATION` (line 476), `looksLikeEvidenceRequest` (line
  505), `groundingRetryIfNeeded` (line 543, called at line 176
  non-streaming only); **N2 additions (commit `852631a`)**:
  `shouldAppendStreamingGroundingAnnotation` predicate shares
  `UNGROUNDED_MIN_CHARS` + `looksLikeEvidenceRequest` with the
  non-streaming gate and is called from the streaming no-tool branch
  (line 150) to append `UNGROUNDED_ANNOTATION` to both the stream sink
  and the turn output - additive, not a rewrite.
  **N3 additions (commit `32a032b`)**: `INSPECT_MIN_CHARS = 500`,
  `INSPECT_REQUEST_MARKERS` (20 plural-file-inspection phrases
  anchored to Turn-1 wording), `UNDER_INSPECTION_ANNOTATION`,
  `looksLikeInspectFirstRequest`, `readOnlyToolCount` (counts
  `read_file` / `list_dir` / `grep`, strips `talos.` prefix),
  `annotateIfInspectUnderCompletion`. Called in both
  streaming and non-streaming with-tools branches right after
  `annotateIfFalseMutationClaim`. Posture: annotate-only (not retry) -
  a retry would require re-running the tool loop.
  **N4 additions (commit `19a837d`)**: class / `TurnOutput` / `Options`
  / `execute` promoted from package-private to `public` (harness
  cross-package access). Three annotation constants
  (`FALSE_MUTATION_ANNOTATION`, `UNDER_INSPECTION_ANNOTATION`,
  `UNGROUNDED_ANNOTATION`) promoted to `public` - they are the
  public contract of the trust gates and the harness asserts on
  them directly.
- `src/main/java/dev/talos/core/llm/LlmClient.java` - **N4
  additions**: `public static LlmClient scripted(List<String>)` and
  `scripted(String)` factories; `scriptedResponses` volatile field
  + `AtomicInteger scriptedCursor` + `nextScriptedResponse()` helper;
  early-return branches in `chatFull` and `chatStreamFull`
  (additive - normal transport paths untouched).
- `src/main/java/dev/talos/runtime/ToolCallLoop.java` - 4-arg constructor
  accepts `boolean strict`; `strict` gates redundant-read suppression
  (line 338), B3 edit short-circuit (line 312), B2 read-before-write
  nudge (line 364), E1 write_file suggestion (line 404). Safety rails
  (max iterations, sandbox, approval gate, missing-path refusal,
  engine-exception handling, output truncation) remain active in both
  modes.
- `src/main/java/dev/talos/tools/ToolRegistry.java` - `strict` field +
  `ToolRegistry(boolean)` constructor; in strict mode `get()` returns
  null after the exact-match step (alias / prefix / case-insensitive
  rescue skipped).
- `src/main/java/dev/talos/core/util/BuildInfo.java` - `version()` /
  `buildTimestamp()` read jar-manifest via
  `Package.getImplementation*`; `commitSha()` / `branch()` read optional
  `META-INF/talos-build.properties`; all readers return `"unknown"` on
  absent metadata.
- `src/main/java/dev/talos/app/Main.java` - one
  `LOG.info("Talos startup - {}", BuildInfo.summary())` line.
- `src/main/java/dev/talos/cli/ui/TalosBanner.java` - hard-coded
  `VERSION = "0.9.0-beta"` removed; uses `BuildInfo.version()` and emits
  a dim `commit <sha> · built <ts>` line when either is known.
- `src/main/java/dev/talos/core/llm/SystemPromptBuilder.java` -
  `withWorkspace(Path)` injects a `WorkspaceManifest` section.
- `src/main/java/dev/talos/core/util/WorkspaceManifest.java` - depth
  ≤ 3, ≤ 80 entries, noise-dir skip list, README excerpt ≤ 600 chars,
  total cap 2000 chars. Not modified in R7.

### Tests (counts verified this pass)

| File | `@Test` count | Covers |
|---|---:|---|
| `src/test/java/dev/talos/harness/Phase0ScenariosTest.java` | 10 | S1-S10: mechanics, approval, safety |
| `src/test/java/dev/talos/harness/AnswerAssertionScenariosTest.java` | 3 | R3 prose assertions; R3 false-creation-claim demo; R4 T6 alias-key end-to-end |
| `src/test/java/dev/talos/harness/StrictModeScenariosTest.java` | 2 | R5 alias-rescue difference; R5 redundant-read suppression difference |
| `src/test/java/dev/talos/harness/ExecutorScenarioTest.java` | 1 | N4 `t5_false_mutation_claim_end_to_end` - scripted-LLM drive through `AssistantTurnExecutor.execute()` |
| `src/test/java/dev/talos/cli/modes/AssistantTurnExecutorTest.java` | 66 | Streaming / non-streaming / deflection / synthesis retry / R2 `ClaimVsActionTests` / R6 `GroundingRetryTests` / N2 `StreamingGroundingTests` / N3 `InspectUnderCompletionTests` / N1 `TranscriptRegressions` (T1-T5) / inspect regressions |
| `src/test/java/dev/talos/runtime/ToolCallParserTest.java` | 53 | R1 gate-widening cases + existing JSON/XML/native fallbacks |
| `src/test/java/dev/talos/core/util/BuildInfoTest.java` | 6 | R7 fallback behavior + resource-missing branches |
| `src/test/java/dev/talos/core/llm/SystemPromptBuilderWorkspaceManifestTest.java` | 4 | R7 workspace-manifest injection + bounded size + no-workspace absence |

### Docs

- `docs/architecture/talos-harness-main-plan.md` (this file)
- `docs/architecture/talos-harness-plan.md`
- `docs/architecture/talos-harness-source-of-truth.md`

### Transcript + playground

- `test-output.txt` at repo root remains the primary transcript. The
  runtime binary now emits `Talos startup - talos v… · build … · commit
  … · branch …` at startup via SLF4J, so future transcripts captured
  through any file appender will carry build provenance. The current
  `test-output.txt` predates R7 and does **not** carry that line - that
  is expected and not a regression.

---

## 3. What has actually landed (beyond Phase 0)

Phase 0 substrate (S1-S10, completion contract, deflection gate) was
described in the previous version of this plan and remains intact. The
following landed **since** that draft:

### R1 - fenced + bare-JSON detection-gate widening (commit `9c97742`)

Both `CODE_FENCE_PATTERN` and `BARE_JSON_PATTERN` now admit the same
key-alias set the extractor already accepts. The original plan only
asked for `CODE_FENCE_PATTERN`; `BARE_JSON_PATTERN` was widened too in
the same commit. Turn 6's `"tool_name"` / `"params"` shape now reaches
the extractor. Covered by new `ToolCallParserTest` cases; the
end-to-end path (loop + registry) is covered by
`AnswerAssertionScenariosTest#turn6AliasKeysTriggerRealToolCallEndToEnd`.

### R2 - post-turn claim-vs-action annotation (commit `9c97742`)

`annotateIfFalseMutationClaim` runs on both streaming and non-streaming
branches after any synthesis retry. Triggers when the answer matches
any of ~30 phrase-level markers in `MUTATION_CLAIM_MARKERS` and
`loopResult.mutatingToolSuccesses() == 0`. Output is annotated, never
silently rewritten. Covered by the `ClaimVsActionTests` nested suite in
`AssistantTurnExecutorTest`.

### R3 - answer-content assertions in the harness (commit `91b5d19`)

`ScenarioResult.finalAnswer()` plus `assertAnswerContains(String)` and
`assertAnswerNotContains(String)`. Proof of usefulness lives in
`AnswerAssertionScenariosTest#proseOnlyAnswerAssertions`, including
explicit negative-case `assertThrows` checks so the helpers fail loudly
when expected.

### R4 - transcript-derived regression coverage (partial)

Initial seed (commit `91b5d19`):

- **Prose-only answer assertions** - R3 smoke.
- **False-creation-claim harness mismatch** - shows that the harness can
  now express the T5 shape directly (answer claims creation, filesystem
  disproves). This is a **demo at the harness seam**, not the R2 runtime
  regression; the runtime regression lives at the executor seam.
- **Turn 6 alias-key end-to-end** - scripted `{"tool_name": …, "params":
  …}` reaches the tool executor and mutates the workspace.

Transcript anchors for T2/T3/T4/T5 subsequently landed at the executor
seam (see N1 in §8 and commit `d2c1701`), and T1 landed with the N3
gate (commit `32a032b`). The `TranscriptRegressions` class now has
full T1-T5 scope at the executor seam. An end-to-end T5 variant
through the executor is still open, blocked on N4.

### R5 - strict-mode toggle for scenario runs (commit `e6a6e8f`)

`ScenarioRunner.runStrict(ScenarioDefinition)` threads a `strict` flag
through `ToolRegistry` and `ToolCallLoop`. In strict mode:

- `ToolRegistry.get()` returns null after the exact-match step - no
  `talos.` prefix insertion, no alias map, no case-insensitive
  normalization.
- `ToolCallLoop` disables the redundant-read suppression, B3 duplicate
  edit short-circuit, B2 read-before-write hint, and E1 write_file
  suggestion.

Safety rails are **not** disabled: max iterations, sandbox, approval
gate, missing-path refusal, engine-exception handling, output
truncation, tool-call stripping all remain active in strict mode.

Proof (`StrictModeScenariosTest`): two scenarios that observe real
normal-vs-strict behavioral differences (alias rescue, redundant-read
suppression). Discovered in the process that the parser dedupes
identical fenced-block text while the loop dedupes canonicalized
signatures - the redundant-read test now uses key-order-swapped blocks
to exercise that distinction honestly.

### R6 - no-tool evidence-required grounding retry (commit `c57bb03`)

`groundingRetryIfNeeded` fires when **all** of these hold:

- the turn produced zero successful tool calls;
- the answer is ≥ 600 chars (`UNGROUNDED_MIN_CHARS`);
- the **latest user message** contains at least one of 17
  evidence-request markers (`read the`, `inspect`, `check`, `verify`,
  `evidence`, `actual file`, `wiring`, `mismatch`, `broken reference`, …).

On match: one retry via `ctx.llm().chatFull()` with an explicit
read-from-evidence instruction. If the retry is still ungrounded, the
answer is **annotated**, not silently discarded.

**Explicit scope limitation** (documented in the commit): wired only
into the non-streaming branch. The streaming branch has already emitted
prose to the terminal by the time the gate would fire; a safe
streaming retry needs more thought and was deliberately deferred.
Covered by a `GroundingRetryTests` nested suite in
`AssistantTurnExecutorTest` (10 tests). The streaming-branch gap was
subsequently closed by **N2** (commit `852631a`) - see §8.

### R7 - build identity + workspace manifest verification (commit `d48f44d`)

- `BuildInfo` reads jar manifest `Implementation-Version` /
  `Implementation-Vendor` (already populated by
  `build.gradle.kts:88-95`) and optional `META-INF/talos-build.properties`
  for commit SHA / branch. Every reader falls back to the constant
  `"unknown"`. No `ProcessBuilder`, no filesystem walk, no git
  dependency at runtime.
- `Main.main()` emits a single `INFO` log line at startup:
  `Talos startup - talos v… · build … · commit … · branch …`.
- `TalosBanner` no longer hard-codes `VERSION = "0.9.0-beta"`; it reads
  `BuildInfo.version()`. A dim `commit <sha> · built <ts>` line appears
  under the tagline when either value is known; fully omitted
  otherwise.
- `SystemPromptBuilder.withWorkspace(Path)` already injected a
  `WorkspaceManifest` section before R7 (file tree ≤ depth 3, ≤ 80
  entries, README excerpt ≤ 600 chars, total ≤ 2000 chars). R7 added
  `SystemPromptBuilderWorkspaceManifestTest` (4 tests): header + paths
  present; bodies **not** leaked under the manifest label; manifest is
  bounded; no headers leak when `withWorkspace()` is not called.

**Limitation** (stated honestly): until a build-time Gradle task
writes `META-INF/talos-build.properties` with a real commit SHA,
`commitSha()` / `branch()` report `"unknown"` and the banner's
provenance line is omitted. That is a truthful state, not a bug. Adding
the Gradle task belongs on `feature/code-quality-stack` per the branch
rules, not here.

### N2 - streaming-path grounding annotation (commit `852631a`)

Closes the streaming half of R6's deferral. Introduces
`shouldAppendStreamingGroundingAnnotation(String answer,
List<ChatMessage> messages)` - a package-private predicate that reuses
`UNGROUNDED_MIN_CHARS`, `latestUserRequest`, and
`looksLikeEvidenceRequest`, so the streaming and non-streaming gates
agree on the same inputs. Called from the streaming no-tool branch;
on match, appends `UNGROUNDED_ANNOTATION` to **both** `ctx.streamSink()`
(so the user sees it on the terminal after the streamed prose) **and**
the turn `out` buffer (so the annotation enters history / memory).

**Design posture** (documented at the gate site): post-stream
annotation, not pre-flush buffering and not a silent retry. Streamed
prose is already on the terminal; any "retry" that replaced it would
violate the transparent-transcript invariant R2 established. This is
detect-and-annotate by choice.

Covered by a `StreamingGroundingTests` nested suite in
`AssistantTurnExecutorTest` (8 tests), including a
`predicate_mirrors_non_streaming_decision` invariant test and a
`streaming_execute_does_not_rewrite_streamed_content` integration test
that proves the annotation is additive.

### N1 - transcript-regression anchors (commit `d2c1701`)

Pins the verbatim `test-output.txt` failure shapes to the existing
trust gates at the executor seam. New nested class
`AssistantTurnExecutorTest.TranscriptRegressions` with 3 tests:

- `t2_wiringFabrication_triggersR6` - Turn-2 verbatim prompt + ≥ 600-char
  wiring-claim answer → `groundingRetryIfNeeded` fires.
- `t3_codeFabrication_triggersR6` - Turn-3 verbatim prompt + ≥ 600-char
  code-claim answer → `groundingRetryIfNeeded` fires.
- `t5_falseMutationClaim_triggersR2` - Turn-5 verbatim phrasing +
  `LoopResult` with 1 read, 0 mutating successes →
  `annotateIfFalseMutationClaim` prepends `FALSE_MUTATION_ANNOTATION`
  and preserves the original text verbatim.

**T4** is already anchored by
`GroundingRetryTests#firesOnTranscriptTurn4Shape` (commit `c57bb03`);
the new class has a Javadoc pointer, no duplicate.

**T1** landed with **N3** (commit `32a032b`) as
`t1_underInspection_triggersN3`. The placeholder Javadoc block was
replaced by a real test pinning the verbatim Turn-1 prompt from
`test-output.txt:22` against a `LoopResult` with 1 read and 0 mutating
successes, and asserting `annotateIfInspectUnderCompletion` prepends
`UNDER_INSPECTION_ANNOTATION`.

**Seam note** (in the class Javadoc): `ScenarioRunner` bypasses
`AssistantTurnExecutor`, and `LlmClient` is `final` with no
scripted-mode seam, so scenario-level R2/R6 coverage would require a
speculative abstraction the branch rules discourage. Static-gate tests
at the executor seam are the lowest-risk anchor today. The harness-
seam gap is tracked as **N4**.

### N3 - inspect under-completion truth layer + T1 anchor (commit `32a032b`)

Closes P4 and lands the final `TranscriptRegressions` anchor (T1).
Adds an annotate-first gate that fires when the user asked for
multi-file inspection but the turn made ≤ 1 read-only tool call and
emitted a substantive (≥ 500-char) answer with zero mutating-tool
successes.

New code in `AssistantTurnExecutor`:

- `INSPECT_MIN_CHARS = 500` - intentionally lower than
  `UNGROUNDED_MIN_CHARS = 600` because N3 fires on the with-tools
  branch (answer already filtered through deflection / synthesis-retry
  tiers).
- `INSPECT_REQUEST_MARKERS` - 20 plural-file-inspection phrases
  anchored to Turn-1 wording: `entry file(s)`, `read the relevant`,
  `read the main`, `read each`, `read them all`, `all three`,
  `look at each`, `inspect each`, `start by reading`, `first read`, …
- `UNDER_INSPECTION_ANNOTATION` - single-line visible notice.
- `looksLikeInspectFirstRequest(String)` - latest-user-message only.
- `readOnlyToolCount(LoopResult)` - counts `read_file` / `list_dir` /
  `grep`, strips `talos.` namespace prefix.
- `annotateIfInspectUnderCompletion(answer, messages, loopResult)` -
  called from both streaming and non-streaming with-tools branches
  right after `annotateIfFalseMutationClaim`.

**Posture**: annotate, do not retry. A retry here would require
re-running the tool loop (another LLM + tool cycle), substantially
more invasive than R6's no-tool retry. Mirrors R2's annotate-first
decision. Streaming-visibility limitation inherited from R2 is
documented at the gate site (not a new regression, and when real
transcript evidence justifies a separate streaming-visible variant it
can be added symmetrically - mirroring the R6 → N2 split).

Covered by `InspectUnderCompletionTests` nested suite in
`AssistantTurnExecutorTest` (11 tests): canonical fires, tools-invoked-
but-no-reads fires, negative two-reads / zero-tools / mutating-success /
short-answer / no-marker / null-or-blank-answer / null-loopResult, plus
`looksLikeInspectFirstRequest` marker-set discrimination and
`readOnlyToolCount` correctness (including `talos.` prefix stripping).
The companion transcript anchor `t1_underInspection_triggersN3` lives
in `TranscriptRegressions` (§3 N1) with the verbatim Turn-1 prompt.

### N4 - harness drives `AssistantTurnExecutor` + T5 end-to-end (commit `19a837d`)

Closes the last open scope in the transcript regression set: T5
through the full executor pipeline, not just the R2 annotator in
isolation. Three coordinated pieces:

1. **Scripted-LLM seam in `LlmClient`** (smallest diff that avoids
   an interface extraction):
   - `public static LlmClient scripted(List<String>)` and
     `scripted(String)` factories;
   - a `volatile List<String> scriptedResponses` field + an
     `AtomicInteger` cursor;
   - early-return branches at the top of `chatFull` and
     `chatStreamFull` that emit the next scripted response and
     clamp to the last entry after exhaustion.

   Normal PLACEHOLDER / ENGINE transport is untouched - the
   early-return is additive. No existing test changes behavior.

2. **`ScenarioRunner.runThroughExecutor(scenario, userPrompt,
   scriptedResponses)`** - symmetric to `runStrict`, but replaces
   `loop.run(...)` with
   `AssistantTurnExecutor.execute(messages, workspace, ctx, opts)`
   driven by a scripted `LlmClient`. Non-streaming only (no
   `streamSink`) for deterministic assertions; a streaming variant
   will land when a scenario needs it.

3. **`ExecutorScenarioResult`** - narrower sibling of
   `ScenarioResult`. Surface is answer-text-focused
   (`assertAnswerContains` / `NotContains` / `StartsWith`) plus the
   workspace-fixture file assertions. Deliberately does **not**
   expose `LoopResult` fields: the executor seam does not surface
   them directly and exposing them via this path would be
   dishonest.

**Production-code visibility changes** (commit `19a837d`):
`AssistantTurnExecutor` class, `TurnOutput`, `Options`, `execute`,
and the three annotation constants (`FALSE_MUTATION_ANNOTATION`,
`UNDER_INSPECTION_ANNOTATION`, `UNGROUNDED_ANNOTATION`) all
promoted from package-private to `public`. These are the public
contract of the trust gates - the harness asserts on them, and the
class was always the primary executor entry point used by
`AskMode` / `RagMode` / `UnifiedAssistantMode`.

**Landed scenario**: `ExecutorScenarioTest#t5_false_mutation_claim_end_to_end`
scripts the T5 shape - (0) `read_file` JSON tool call, (1) verbatim
Turn-5 false-mutation claim - and asserts:

- `FALSE_MUTATION_ANNOTATION` is prepended (R2 fires through the
  full pipeline, not just the isolated annotator);
- the original T5 claim is preserved verbatim (annotate-first);
- `index.html` on disk contains the original content and never
  mentions the claimed edit (filesystem parity - the check the
  static-gate anchor `t5_falseMutationClaim_triggersR2` cannot
  make);
- N3 does **not** fire (the user prompt lacks inspect-first
  markers - a guard against N3 broadening into R6 territory);
- `TurnOutput.streamed()` is `false` (non-streaming path
  confirmation; future streaming variant will show up as a
  visible API change).

**Scope discipline** (in `ExecutorScenarioTest` Javadoc): ship with
one scenario. Each future addition should pin a *distinct*
transcript failure shape; do not accumulate redundant variants of
the same shape here. The static-gate tests in
`AssistantTurnExecutorTest` cover predicate coverage; the
executor-path scenarios prove integration.

---

## 4. What the latest transcript still proves (delta since last pass)

The transcript is unchanged. What changed is which of its failures are
now covered:

| Transcript shape | Turn(s) | Current runtime coverage | Current harness coverage |
|---|---|---|---|
| Premature inspect-task completion (1 read on 3-file task) | 1 | **N3 annotates** (both branches) | Executor-seam anchor `t1_underInspection_triggersN3` |
| Long confident fabrication on evidence-required prompt | 2, 3, 4 | **R6** (non-streaming retry) **+ N2** (streaming annotation) | Executor-seam anchors (T2, T3 via `TranscriptRegressions`; T4 via `GroundingRetryTests#firesOnTranscriptTurn4Shape`) |
| False mutation claim | 5 | **R2 annotates** (both branches) | Executor-seam anchor `t5_falseMutationClaim_triggersR2` **+ end-to-end** `ExecutorScenarioTest#t5_false_mutation_claim_end_to_end` (N4) |
| Fenced-JSON detection narrowness | 6 | **R1 fix** | **R4 end-to-end scenario green** |
| Tool dispatch / safety / approval | all | Solid | S1-S10 green |

Every transcript failure shape now has runtime coverage **and** an
executor-seam regression anchor. The remaining open work is
observability (N5), end-to-end seam (N4), infrastructure (N6), and
docs (N7) - not new trust-layer gates.

---

## 5. Pain points - status refresh

Each item is tagged: **[C]**ode, **[D]**ocs, **[T]**ranscript.

### P1 - Long confident fabrication on evidence-required prompts - **ADDRESSED (both branches)** [C][T]

R6 retries on the non-streaming branch when the answer is ≥ 600 chars,
used zero tools, and the latest user message contains an
evidence-request marker. **N2** extends the same gate to the streaming
branch as a post-stream annotation (detect-and-annotate, not retry -
prose is already on the terminal). Keyword gate (17 markers) is
intentionally narrower than a pure length-and-no-tools heuristic to
keep false-positive rate low. **Residual risk**: evidence-request
prompts that don't include any of the 17 markers are still uncovered;
this is calibration work, not an architectural gap.

### P2 - False mutation claim - **ADDRESSED (annotate-first)** [C][T]

R2 annotates on both streaming and non-streaming branches when mutation
claims are present and no mutating tool succeeded. Promote-to-retry is
deferred until annotations are observed in real runs, matching the
annotate-first decision in the original plan.

### P3 - Fenced + bare-JSON detection-gate asymmetry - **ADDRESSED** [C]

R1 widened both patterns. The invariant "detection gate is not narrower
than the alias-aware extractor" is now explicit in the Javadoc on
`CODE_FENCE_PATTERN`. Covered in `ToolCallParserTest` and end-to-end in
the harness.

### P4 - Inspect-task under-completion - **ADDRESSED** [C][T]

**N3** (commit `32a032b`) lands an annotate-first gate at the
executor seam. Fires on the with-tools branch when the user asked
for multi-file inspection (`INSPECT_REQUEST_MARKERS`, narrower than
R6's evidence set), the turn made ≤ 1 read-only tool call, the
answer is ≥ 500 chars, and no mutating tool succeeded. Covered by
`InspectUnderCompletionTests` (11 tests) and the transcript anchor
`t1_underInspection_triggersN3`. Residual risk: under-inspection with
≥ 2 reads is not gated by intent (only by count) - calibration work,
not an architectural gap.

### P5 - Prompt-only enforcement for trust-critical invariants - **PARTIALLY ADDRESSED (ongoing)** [C][D]

R1 (detection-gate invariant), R2 (claim-vs-action), R6 (grounding
retry), R7 (build provenance visible in transcript) each migrate one
prompt expectation into a code-level check. The direction is correct.
`unified-rules.txt` still contains rules without runtime twins; R2 and
R6 reduce but do not close the gap.

### P6 - Scenario harness did not assert on answer content - **ADDRESSED** [C]

`ScenarioResult.finalAnswer()`, `assertAnswerContains`,
`assertAnswerNotContains` exist and have test coverage including
negative-case `assertThrows`. The original framing in the old plan
("the harness measures tool behavior, not answer truth") is no longer
true.

### P7 - UX cushions mask model weakness in measurement - **ADDRESSED for strict-mode toggle; observability still open** [C]

R5 lets a scenario opt into running with the four measurement cushions
off. What R5 did **not** add: per-cushion counters in `LoopResult`
(e.g. `cushionFires_redundantRead`, `cushionFires_aliasRescue`). A
scenario that runs in normal mode still doesn't know how much cushion
fired.

### P8 - Compaction cadence in edit sessions - **STILL OPEN (unverified)** [T][C]

Untouched. The 55% / 10-pair assist-mode budget is unchanged. Still no
direct evidence this contributed to T5, so this remains a speculative
pain point.

### M1 - Answer-shape invariants - **ADDRESSED in two places** [C]

R2 (claim-vs-action) and R6 (grounding retry) are both answer-shape
invariants at the executor seam.

### M2 - Gate/extractor asymmetry pattern elsewhere - **STILL OPEN**

A short audit of `ContentVerifier`, `ToolCallStreamFilter`, and
`Sanitize` for parallel detection-vs-processing asymmetries has not
been done.

### M3 - Scripted-LLM-with-deflection / claim-vs-action scenarios - **ADDRESSED**

R4 shipped 3 harness-seam scenarios. N1 (commit `d2c1701`) added
executor-seam transcript anchors for T2, T3, T5 (T4 already covered by
`GroundingRetryTests#firesOnTranscriptTurn4Shape`). N3 (commit
`32a032b`) added the T1 anchor. N4 (commit `19a837d`) added the
end-to-end T5 scenario (`ExecutorScenarioTest#t5_false_mutation_claim_end_to_end`)
driving `AssistantTurnExecutor.execute()` via a scripted `LlmClient`.
The `TranscriptRegressions` class now has full T1-T5 scope at the
executor seam, and T5 additionally has executor-pipeline end-to-end
coverage (filesystem parity + annotation invariant through the full
streaming / tool-loop / synthesis-retry / gate pipeline).

### M4 - Strict-mode cushion toggle - **ADDRESSED** [C]

R5.

### M5 - Workspace manifest injection - **ADDRESSED (was already in code; now verified)** [C]

R7's tests nail down the wiring invariant. The earlier plan's open
question is closed.

### M6 - `copilot-instructions.md` stale - **STILL OPEN**

The repo instruction file still describes LOQ-J rather than Talos.
Untouched in any recent pass.

### M7 - Transcript-binary provenance not logged - **ADDRESSED (runtime side only)** [C]

R7 added the SLF4J startup line and banner provenance. What is
**not** yet done: a build-time Gradle task that writes
`META-INF/talos-build.properties` with a real commit SHA. Without that
task, `commitSha()` and `branch()` return `"unknown"` in every
production build, which is honest but not useful. That Gradle work is
on `feature/code-quality-stack`.

---

## 6. Corrections that remain relevant

Correction 1 (Turn 6 was a detection-gate narrowness, not an
alias-support gap) and Correction 2 (Phase 0 framing) from the prior
pass have now been **implemented away** - the runtime matches what the
corrections said it should match.

Correction 3 (deflection gate does not cover long fabrications) remains
**partially true**. R6 covers the subset gated by the evidence-request
keyword set, and N2 extends that coverage to the streaming branch.
N3 adds the orthogonal under-inspection gate for the with-tools
branch. Outside the combined R6 / N2 / N3 marker sets the
long-fabrication pattern is still unhandled by intent; this is
calibration risk, not an architectural gap.

Correction 4 (branch-state claims) is refreshed in §2.

Correction 5 (primary evidence is code + transcript + playground, not
screenshots) stands.

---

## 7. Priority / risk / status matrix

Status legend: ✅ done · 🟡 partial · ⬜ open.

| Item | Priority | Risk | Status | Notes |
|---|:---:|:---:|:---:|---|
| R1 - detection-gate widening | High | Low | ✅ | `CODE_FENCE_PATTERN` + `BARE_JSON_PATTERN` both widened |
| R2 - claim-vs-action audit (annotate) | High | Low | ✅ | Both streaming + non-streaming |
| R3 - harness answer assertions | High | Low | ✅ | `finalAnswer`, `assertAnswer(Not)Contains` |
| R4 - transcript regression scenarios (T1-T6) | High | Low | ✅ | Full T1-T5 anchored at executor seam (N1 `d2c1701` + N3 `32a032b`); T6 + R4 seed at harness seam |
| R5 - strict-mode toggle | Medium | Low | ✅ | 2 meaningful difference tests |
| R6 - long-fabrication grounding retry | High | Medium | ✅ | Non-streaming retry + N2 streaming annotation |
| R7 - build identity + workspace manifest | Medium | Low | ✅ | Runtime banner + log; manifest was already wired |
| N1 - transcript-regression anchors (T1-T5) | High | Low | ✅ | T2/T3/T5 in `d2c1701`; T4 pre-existing; T1 in `32a032b`; T5 E2E in `19a837d` |
| N2 - streaming-path grounding annotation | High | Medium | ✅ | Commit `852631a`; post-stream annotation, additive |
| N3 - inspect under-completion (P4) | High | Medium | ✅ | Commit `32a032b`; annotate-only; 11-test suite + T1 anchor |
| N4 - harness drives `AssistantTurnExecutor` | Medium | Low-Medium | ✅ | Commit `19a837d`; `LlmClient.scripted(...)` + `runThroughExecutor` + T5 E2E |
| N5 - `LoopResult` cushion counters | Low | Low | ⬜ | P7 observability |
| P8 - compaction cadence review | Low | Medium | ⬜ | Unverified contributor |
| M2 - audit gate/extractor asymmetry elsewhere | Low | Low | ⬜ | `ContentVerifier`, `ToolCallStreamFilter`, `Sanitize` |
| M6 - `copilot-instructions.md` Talos rewrite | Low | Low | ⬜ | Docs only |
| M7 - build-time `talos-build.properties` (Gradle) | Low | Low | ⬜ | Belongs on `feature/code-quality-stack` |
| R2 promote-to-retry (was deferred) | Low | Medium | ⬜ | Wait for annotation data |

---

## 8. Recommended next moves (current)

This replaces the old R1→R8 roadmap, which has largely shipped.

### N1 - Transcript regression anchors (T1-T5) - ✅ **LANDED (T1-T5 complete)**

**Status update (2026-04-17, post-N3 refresh):** T1-T5 all anchored
at the executor seam. T2/T3/T5 in commit `d2c1701`; T4 via
pre-existing `GroundingRetryTests#firesOnTranscriptTurn4Shape`
(`c57bb03`); T1 landed together with the N3 gate in commit
`32a032b`. No remaining scope at the executor seam.

**Course correction - seam changed from harness to executor.** The
original plan proposed encoding T1-T5 as `ScenarioRunner` scenarios in
`dev.talos.harness.*`. On careful re-examination that seam is wrong for
these tests:

1. `ScenarioRunner` drives `ToolCallLoop` directly and bypasses
   `AssistantTurnExecutor`. The R2 / R6 / N2 gates that catch T2-T5
   shapes never fire in the harness, so an answer-content assertion
   against a *scripted* LLM response is tautological - we author the
   response being asserted against.
2. `LlmClient` is `final` with no scripted-mode seam. Making harness
   scenarios exercise `execute()` with controlled responses would
   require extracting an interface - a speculative abstraction the
   branch rules explicitly discourage, and unnecessary given the
   pattern established by `ClaimVsActionTests`, `GroundingRetryTests`,
   and `StreamingGroundingTests`.

**Landed shape (commit `d2c1701`):** a new nested class
`AssistantTurnExecutorTest.TranscriptRegressions` (3 new tests) plus a
cross-reference to the existing T4 anchor. Each test pins a verbatim
transcript user prompt + a fabrication-shaped answer and asserts the
corresponding static gate fires:

- **T2** - `t2_wiringFabrication_triggersR6`. Turn-2 "how is the site
  wired" prompt + ≥ 600-char wiring-claim answer. Asserts
  `groundingRetryIfNeeded` appends assistant + corrective user message.
- **T3** - `t3_codeFabrication_triggersR6`. Turn-3 "three concrete
  improvements … evidence from the actual files" prompt + ≥ 600-char
  improvement-list answer referencing code patterns the files don't
  contain. Asserts R6 fires.
- **T4** - already anchored by
  `GroundingRetryTests#firesOnTranscriptTurn4Shape` (selector-mismatch
  audit prompt + long ungrounded answer). No duplicate; the new class
  has a doc pointer.
- **T5** - `t5_falseMutationClaim_triggersR2`. Verbatim Turn-5 phrasing
  ("I've updated the CTA button text to 'Let's Get Healthy'. The
  changes have been applied to the `index.html` file.") + `LoopResult`
  with 1 read, 0 mutating successes. Asserts
  `annotateIfFalseMutationClaim` prepends `FALSE_MUTATION_ANNOTATION`
  and preserves the original answer verbatim.

**Still open:** nothing in the T1-T5 scope. **T5 end-to-end through
the executor** landed in N4 (commit `19a837d`) as
`ExecutorScenarioTest#t5_false_mutation_claim_end_to_end`.

**Seam**: `AssistantTurnExecutorTest` (5 static-gate anchors) +
`ExecutorScenarioTest` (1 end-to-end anchor). **Type**: test-only.
**Risk**: low. **Blocks nothing.**

### N2 - Extend R6 grounding retry to the streaming branch - ✅ **LANDED (commit `852631a`)**

Closed in this pass. Streaming no-tool branch now runs
`shouldAppendStreamingGroundingAnnotation` - a predicate that reuses
`UNGROUNDED_MIN_CHARS` + `looksLikeEvidenceRequest` so the streaming
and non-streaming gates agree on the same inputs - and appends
`UNGROUNDED_ANNOTATION` to both `ctx.streamSink()` and the turn `out`
buffer on match. Posture is **detect-and-annotate, not retry**:
streamed prose is already on the terminal, and replacing it would
break the transparent-transcript invariant R2 established.

Covered by `StreamingGroundingTests` (8 tests), including an
invariant test that locks streaming/non-streaming predicate parity
and an integration test that proves the annotation is additive to
the streamed content (not a rewrite). See §3.

### N3 - Inspect-task under-completion heuristic (P4) - ✅ **LANDED (commit `32a032b`)**

Closed in this pass. Adds an annotate-first gate
(`annotateIfInspectUnderCompletion`) that fires when **all** hold:

- the tool loop invoked at least one tool (zero-tool turns are R6 / N2
  territory);
- zero mutating tool successes;
- answer is ≥ `INSPECT_MIN_CHARS` (500);
- `readOnlyToolCount(loopResult)` ≤ 1;
- the latest user request contains an `INSPECT_REQUEST_MARKERS` phrase.

On match, prepends `UNDER_INSPECTION_ANNOTATION` - the answer is
annotated, never silently rewritten. Posture intentionally differs
from R6: no retry, because a retry here would require re-running the
tool loop (another LLM + tool cycle). Mirrors R2's annotate-first
decision.

Covered by `InspectUnderCompletionTests` (11 tests) and the
`t1_underInspection_triggersN3` anchor in `TranscriptRegressions`
(pinning the verbatim Turn-1 prompt from `test-output.txt:22`). See
§3 N3 for the detailed description.

### N4 - Harness drives `AssistantTurnExecutor` + T5 end-to-end - ✅ **LANDED (commit `19a837d`)**

Closed in this pass. See §3 N4 for the full description. The landing
added `LlmClient.scripted(...)` as the minimal test seam (option (a)
from the prior recommendation), promoted `AssistantTurnExecutor` +
its `TurnOutput` / `Options` / `execute` surface and its three
annotation-constant strings to `public`, added
`ScenarioRunner.runThroughExecutor(...)` symmetric to `runStrict`,
and introduced `ExecutorScenarioResult` + `ExecutorScenarioTest`
with one scenario (`t5_false_mutation_claim_end_to_end`). This
closes the last open scope in `TranscriptRegressions` (T5 end-to-end)
and removes the static-gate-only caveat.

### N5 - `LoopResult` cushion counters (P7)

Add `int cushionFires_redundantRead`, `cushionFires_aliasRescue`,
`cushionFires_b3EditShortCircuit`, `cushionFires_e1Suggestion` to
`LoopResult`. Increment at the existing gate sites. Exposed via
`ScenarioResult` for assertions like "normal-mode run fired the
redundant-read cushion exactly once." Makes strict-vs-normal deltas
observable without grepping logs.

**Seam**: `ToolCallLoop`, `LoopResult`, `ScenarioResult`. **Type**:
runtime + test. **Risk**: low.

### N6 - Build-time `talos-build.properties` (Gradle, on `feature/code-quality-stack`)

A Gradle task that runs `git rev-parse HEAD` and `git rev-parse
--abbrev-ref HEAD` (with a fallback when git is unavailable) and writes
the result to `build/resources/main/META-INF/talos-build.properties`.
Once landed, R7's banner and log will carry real commit / branch in
every packaged build.

**Per branch rules**, this work does **not** go on
`v0.9.0-beta-dev`. It goes on `feature/code-quality-stack` and is
reviewed as a standalone PR.

**Seam**: `build.gradle.kts`. **Type**: infrastructure. **Risk**: low.

### N7 - `copilot-instructions.md` rewrite for Talos (M6)

Replace LOQ-J wording with Talos-accurate project instructions. Low
urgency, zero risk, prevents persistent AI-assistant drift.

---

## 9. What should wait

- **A full phase model (`INSPECT` / `APPLY` / `VERIFY` states in
  runtime).** The trust-layer work that was its implicit motivation has
  landed in narrower, testable pieces. Do not add a phase model unless
  a specific transcript failure proves R1 / R2 / R6 / N3 are
  insufficient.
- **New tools (shell, test runner, browser, MCP server).** Still
  premature. With R1-R7, N1-N4 in place the trust layer is strong
  enough to consider this, and the executor-path harness now exists
  so new tools can ship with real end-to-end scenario tests. Gate on
  a concrete use case, not on further infrastructure.
- **Multi-agent / swarm / orchestration experiments.** Out of vision.
- **Long-term / durable memory changes.** Out of scope per branch
  rules.
- **Qodana / Sonar / JaCoCo threshold changes** on this branch - belong
  on `feature/code-quality-stack`.
- **R2 promote-to-retry.** Keep as annotate-first until we have at
  least a handful of real-run annotations to calibrate against.

---

## 10. Final recommendation

### Where Talos is now (one paragraph)

The trust layer the prior plan asked for exists and is complete across
every transcript failure shape. Detection gates match extraction gates
(R1). False mutation claims are annotated on both branches (R2). Long
confident fabrication is retried on non-streaming (R6) and annotated
on streaming (N2). Inspect-task under-completion is annotated on both
branches (N3). Each shape has a transcript-anchored executor-seam
regression test (N1 + N3's T1 anchor). The harness can assert on
answer text and can run with measurement cushions off. Build identity
is surfaced at startup and in the banner. The workspace manifest is
injected (and test-locked). What remains open is no longer
trust-layer work - it is observability (N5), end-to-end seam (N4),
infrastructure (N6), and docs (N7).

### Single best next implementation target

**N5 - `LoopResult` cushion counters (P7 observability).**

Rationale: with N1-N4 landed, the trust layer is complete on both
branches and for both zero-tool and with-tools turns, and the harness
can now drive `AssistantTurnExecutor` end-to-end via scripted
`LlmClient`. The sharpest-edge remaining gap is no longer *behavior*
- it is *observability* of that behavior. Today, "did the redundant-
read cushion fire on this turn?" or "did strict mode actually disable
the B3 edit short-circuit?" can only be answered by grepping logs.
That is exactly the kind of fragile, human-eye-dependent verification
the harness was built to retire. N5 promotes those signals to
first-class counters on `LoopResult`, surfaced through
`ScenarioResult`, so strict-vs-normal deltas become assertable facts.

N5 is small, local, and does not touch the trust layer. It is the
natural successor to N4: the end-to-end seam now exists, so cushion
counters can be asserted in real scenarios rather than only in
`ToolCallLoop` unit tests.

### Discussion items for the next human pass

1. **Counter set - which cushions are worth counting?** Candidates:
   (a) redundant-read suppressions; (b) B2 alias-rescue fires;
   (c) B3 edit short-circuits; (d) E1 suggestion-rewrite fires.
   Recommend starting with all four - the increment sites already
   exist as log points, so the diff is mechanical and the marginal
   cost of one more `int` field is negligible.
2. **Shape - flat fields on `LoopResult`, or a sibling `CushionTelemetry`
   record?** Flat fields keep the diff tight and match the existing
   `toolsInvoked` / `failedCalls` / `retriedCalls` style. A sibling
   record is cleaner long-term but speculative. Recommend flat fields
   for N5; promote to a record only if the set grows past ~6.
3. **Strict-mode invariant - should strict runs assert
   `cushionFires_* == 0`?** If strict mode is defined as "measurement
   cushions off," then any non-zero counter under strict mode is by
   definition a bug in strict-mode wiring. Recommend adding that
   assertion inside `ScenarioResult.assertStrictIntegrity()` (or
   equivalent) as part of N5 - it is the cheapest way to lock the
   contract.
4. **Executor-path counters - do R2 / R6 / N2 / N3 annotation fires
   belong on `LoopResult` too, or on a sibling executor-telemetry
   record?** `LoopResult` today is a tool-loop summary; annotation
   gates live one layer above it. Recommend deferring executor-gate
   counters to a follow-up pass (call it N5b) so N5 stays a pure
   tool-loop-observability change. The `ExecutorScenarioResult`
   seam from N4 is the natural home for gate-fire assertions.
