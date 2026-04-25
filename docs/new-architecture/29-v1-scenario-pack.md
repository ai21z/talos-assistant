# 29. Talos V1 Scenario Pack

- **Date:** 2026-04-25
- **Purpose:** define the curated V1 scenario pack, map it to current evidence,
  and mark the boundary between proven behavior, regression coverage, and future
  architecture work.
- **Status:** revised evidence review after checking current harness code,
  current scenario resources, architecture docs, source-pack guidance, OpenClaw
  QA patterns, and public eval/safety references.

---

## 1. Review Basis and Confidence Boundary

This version uses a strict evidence rule:

- hard claims must be backed by current Talos code, current scenario resources,
  current tests, or mandatory project docs
- external sources are used as methodology and calibration, not as direct Talos
  product requirements
- future architecture claims are labeled as planned, not proven

Current local evidence checked:

- `src/e2eTest/resources/scenarios/*.json`
- `src/e2eTest/java/dev/talos/harness/JsonScenarioPackTest.java`
- `src/e2eTest/java/dev/talos/harness/ScenarioRunner.java`
- `src/e2eTest/java/dev/talos/harness/ScenarioResult.java`
- `src/e2eTest/java/dev/talos/harness/ExecutorScenarioTest.java`
- `src/e2eTest/java/dev/talos/harness/StrictModeScenariosTest.java`
- `src/e2eTest/java/dev/talos/harness/PersistenceScenarioPackTest.java`
- `src/test/java/dev/talos/runtime/ToolCallLoopTest.java`
- `src/test/java/dev/talos/cli/modes/AssistantTurnExecutorTest.java`
- `src/main/java/dev/talos/runtime/ToolCallLoop.java`
- `src/main/java/dev/talos/runtime/toolcall/ToolCallExecutionStage.java`
- `src/main/java/dev/talos/runtime/toolcall/ToolCallRepromptStage.java`
- `src/main/java/dev/talos/cli/modes/ExecutionOutcome.java`
- `local/manual-testing/test-output`
- `local/tickets/talos-minimal-execution-phase-policy.md`
- `local/tickets/talos-static-task-verifier.md`
- `local/docs/talos-source-pack-safe-local-alternative-2026-04-19.md`
- `docs/new-architecture/talos-harness-source-of-truth.md`

External/source calibration checked:

- `.external assistant/openclaw/qa/scenarios/index.md`
- `.external assistant/openclaw/qa/scenarios/workspace/source-docs-discovery-report.md`
- `.external assistant/openclaw/qa/scenarios/runtime/approval-turn-tool-followthrough.md`
- `.external assistant/openclaw/qa/frontier-harness-plan.md`
- `.external assistant/openclaw/qa/scenarios/runtime/reasoning-only-no-auto-retry-after-write.md`
- `.external assistant/openclaw/qa/scenarios/runtime/compaction-retry-mutating-tool.md`
- OpenAI evaluation guidance:
  <https://platform.openai.com/docs/guides/evaluation-best-practices>
  and <https://platform.openai.com/docs/guides/agent-evals>
- OpenHands evaluation/sandbox docs:
  <https://docs.openhands.dev/openhands/usage/developers/evaluation-harness>
  and <https://docs.openhands.dev/openhands/usage/sandboxes/overview>
- OWASP LLM Top 10:
  <https://owasp.org/www-project-top-10-for-large-language-model-applications/>

MEAP book note: the local PDF was present, but direct text extraction was not
available in the current tool environment. This document therefore relies on the
project source-pack summary for MEAP: processing-loop vocabulary, trajectory
capture, and tool/action/result abstractions are useful conceptual support, but
the book is not treated as production runtime policy.

---

## 2. Why This Document Exists

Talos already has meaningful deterministic harness machinery:

- JSON-backed scenario resources under `src/e2eTest/resources/scenarios/`
- harness runners in `src/e2eTest/java/dev/talos/harness/`
- strict vs friendly measurement mode
- executor-path scenarios that drive `AssistantTurnExecutor.execute(...)`
- persistence/replay scenarios
- Gradle E2E summary logic that detects JSON-backed scenario resources and
  reports whether the JSON scenario subset executed

That is enough to make selected architecture claims measurable.

It is not enough to claim that Talos has completed the discipline architecture.
The current pack is a scenario-discipline baseline. It is not yet a phase
runtime, task-verification runtime, security harness, or full task-completion
proof system.

---

## 3. What the V1 Scenario Pack Is For

The V1 pack should provide deterministic regression evidence for the current
local-operator promises:

1. read-only requests remain read-only
2. explicit mutations remain approval-gated
3. denied mutations do not write files
4. mutation summaries reflect actual tool outcomes
5. grounded analysis can override unsupported model prose when file evidence is
   available
6. strict measurement mode exposes raw tool/runtime weakness without removing
   user-mode cushions from the normal runtime
7. persistence and replay do not corrupt history semantics
8. long loops have at least a hard stop instead of running indefinitely

The V1 pack does not prove:

- arbitrary task correctness
- browser/runtime behavior
- shell/test-runner verification
- whole-surface sandboxing
- prompt-injection resistance
- first-class phase enforcement
- task-level verification
- live Ollama behavior in the installed CLI

Those are future or separate evidence lanes.

---

## 4. Current Harness Structure

The existing harness has four useful layers.

### A. JSON Scenario Pack

Primary reviewer-facing scenarios. These are resource-backed, named, tagged
with `v1Pack`, and include claim metadata in the JSON resources.

Current JSON scenarios:

- `01-read-only-repo-question.json`
- `02-single-safe-file-edit.json`
- `03-off-scope-mutation-warning.json`
- `04-not-found-recovery.json`
- `05-approval-denied.json`
- `06-approval-remembered.json`
- `07-replay-turn-log-fallback.json`
- `08-persistence-history-correctness.json`
- `09-read-only-workspace-no-unsolicited-mutation.json`
- `10-selector-mismatch-grounded.json`
- `11-partial-mutation-summary-truthful.json`
- `12-repeated-missing-path-stops-at-loop-cap.json`
- `13-streaming-no-tool-grounding-visible.json`
- `14-approval-denial-stops-loop.json`

### B. Executor-Path Scenarios

These matter because they exercise `AssistantTurnExecutor.execute(...)`, not
only `ToolCallLoop`.

Primary evidence:

- executor runner paths inside `JsonScenarioPackTest`
- `ExecutorScenarioTest.T5`
- streaming runner path for `13-streaming-no-tool-grounding-visible`

### C. Strict-Mode Scenarios

These are measurement scenarios, not user-mode confidence scenarios.

Primary evidence:

- `StrictModeScenariosTest.aliasRescueDifference`
- `StrictModeScenariosTest.redundantReadSuppressionDifference`

They prove that strict mode can reveal raw model/tool weakness that friendly
mode cushions.

### D. Legacy/Base Deterministic Scenarios

`Phase0ScenariosTest` remains useful as lower-level mechanic coverage:

- basic file write and edit mechanics
- missing-path failure behavior
- unknown-tool resilience
- grep/list_dir basics
- multi-tool turns

This is supporting evidence, not the primary V1 reviewer pack.

---

## 5. Evidence Strength Legend

Use these labels when mapping scenarios to architecture claims:

| Label | Meaning |
|---|---|
| `covered` | Current code and current tests directly assert this behavior for the named scenario shape. |
| `partially-covered` | The scenario protects an important regression shape, but the wider architecture claim is not enforced globally. |
| `baseline-only` | Current behavior is safer than nothing, but is below the target architecture standard. |
| `supporting` | Useful evidence, but not a primary V1 claim by itself. |
| `planned` | Not implemented yet; belongs to an upcoming ticket or scenario pack. |
| `not-covered` | No current scenario evidence. Do not claim this as proven. |

---

## 6. Curated V1 Scenario Pack

### 6.1 Primary JSON Scenarios

| Scenario | Current evidence | Strength | Caveat |
|---|---|---|---|
| `01-read-only-repo-question` | Executor path reads/lists fixture files and answers from fixture facts without mutation. | `covered` | Does not exercise retrieval index or hostile workspace content. |
| `02-single-safe-file-edit` | Loop path reads `index.html`, uses `edit_file`, avoids `write_file`, and changes the intended title only. | `covered` | Read-before-edit is present in this scripted scenario, not yet enforced by phase policy. |
| `03-off-scope-mutation-warning` | Off-scope write triggers approval detail warning before approval. | `covered` | The write is still approved by scenario policy; this proves warning visibility, not automatic rejection. |
| `04-not-found-recovery` | Executor path recovers from `READMEE.md` to `README.md` and answers correctly. | `covered` | Recovery is scripted through model follow-up; not a general path-repair guarantee. |
| `05-approval-denied` | Denied write preserves original file and records one denied approval. | `covered` | JSON scenario checks file preservation. Terminal no-retry denial behavior is covered by newer runtime/manual evidence and should be added here. |
| `06-approval-remembered` | Remembered approval asks once and lets later writes proceed. | `covered` | Covers session approval memory only for this narrow write pattern. |
| `07-replay-turn-log-fallback` | Replay restores ok assistant turn and skips error-tagged residue. | `covered` | Session-discipline evidence, not task-completion evidence. |
| `08-persistence-history-correctness` | Snapshot and turn log store chrome-stripped assistant text. | `covered` | Persistence correctness only; does not prove memory quality. |
| `09-read-only-workspace-no-unsolicited-mutation` | Executor path blocks unsolicited mutation on a read-only workspace question and avoids approval prompts. | `partially-covered` | Important guard evidence, but not a full `INSPECT` phase model. |
| `10-selector-mismatch-grounded` | Executor path corrects unsupported "no mismatch" prose using actual `index.html`, `style.css`, and `script.js` evidence. | `covered` | Selector grounding is a narrow web/static check, not a general verifier. |
| `11-partial-mutation-summary-truthful` | Final answer reports succeeded and failed mutation outcomes without claiming the failed title change. | `covered` | Truthful summary is outcome shaping, not full task verification. |
| `12-repeated-missing-path-stops-at-loop-cap` | Repeated bad path stops at the hard iteration cap and annotates the final answer. | `baseline-only` | The target is earlier controlled stop/reset/downgrade, not waiting for the cap. |
| `13-streaming-no-tool-grounding-visible` | Streaming no-tool fabricated evidence answer is annotated as ungrounded. | `covered` | Covers final-answer truthfulness. It does not fully solve live terminal stream/protocol leakage. |
| `14-approval-denial-stops-loop` | Executor path scripts a second mutating retry after denial and proves it is not reached. | `covered` | Covers approval-denial failure discipline for a known mutation retry shape. |

### 6.2 Supporting Executor-Path Scenarios

| Scenario / file | Current evidence | Strength |
|---|---|---|
| `ExecutorScenarioTest.T5` | False mutation claim is annotated end-to-end through `AssistantTurnExecutor`, while disk remains unchanged. | `covered` |
| executor-path cases in `JsonScenarioPackTest` | JSON resources exercise executor-layer truth/grounding gates, not only the raw loop. | `covered` |

### 6.3 Supporting Strict-Mode Scenarios

| Scenario / file | Current evidence | Strength |
|---|---|---|
| strict alias rescue difference | Friendly mode rescues non-canonical tool naming; strict mode does not. | `covered` |
| strict redundant-read difference | Friendly mode suppresses duplicate read; strict mode executes both reads. | `covered` |

---

## 7. Claim-to-Scenario Mapping

| Discipline / claim | Primary evidence | Evidence strength | Current boundary |
|---|---|---|---|
| Read-only requests remain read-only | `01`, `09` | `covered` for scripted shapes | Does not prove all read-only phrasings or prompt-injection cases. |
| Inspect-first behavior exists in important scenarios | `01`, `02`, `09`, `10` | `partially-covered` | No first-class `ExecutionPhase` yet. |
| Retrieval discipline | none in V1 JSON pack | `not-covered` | `ScenarioRunner` intentionally omits `RetrieveTool`; add later once retrieval scenarios are stable. |
| Narrow file edits mutate intended content | `02` | `covered` | Does not prove target derivation from arbitrary user requests. |
| Off-scope writes surface warning before approval | `03` | `covered` | Warning is not the same as policy-level block. |
| Path/input recovery can recover from a wrong path | `04` | `covered` | Scripted model recovery, not generalized repair. |
| Approval denial preserves files | `05` | `covered` | File-preservation evidence; retry-loop stop is covered separately by `14`. |
| Approval denial stops mutating retry loops | `14` | `covered` | Known denial retry shape only; broader failure policy remains planned. |
| Session approval memory behaves predictably | `06` | `covered` | Narrow approval-memory shape only. |
| Session replay skips error residue | `07` | `covered` | Does not prove long-session quality. |
| Persisted memory strips UI chrome | `08` | `covered` | Does not prove memory usefulness. |
| Partial mutation summaries are truthful | `11` | `covered` | Outcome shaping only; not task verification. |
| Failure loops are bounded | `12` | `baseline-only` | Hard cap exists; formal failure/reset policy still missing. |
| Streaming no-tool evidence answers are marked ungrounded | `13` | `covered` | Final-answer gate only; installed-CLI stream transcript remains a separate evidence lane. |
| Executor-layer false mutation claims are caught | `ExecutorScenarioTest.T5` | `covered` | Applies to known false-claim shape. |
| Strict mode reveals raw tool/runtime weakness | `StrictModeScenariosTest` | `covered` | Needs report-visible metrics beyond unit assertions. |
| Task-level verification | none | `planned` | Covered by `talos-static-task-verifier.md`, not current V1 pack. |
| Phase-aware tool policy | none | `planned` | Covered by `talos-minimal-execution-phase-policy.md`, not current V1 pack. |
| Prompt-injection/tool-abuse resistance | none | `not-covered` | Must be added before claiming serious security evaluation. |

---

## 8. External Calibration

### OpenClaw

The useful OpenClaw lesson is not its product direction. Talos should not copy
OpenClaw's multi-agent/channel/platform shape.

The useful transfer is its QA discipline:

- scenarios have IDs, coverage metadata, success criteria, docs refs, and code
  refs
- runnable flows assert observable behavior, not only final prose
- mock-provider debug logs are used to prove tool follow-through
- frontier/manual lanes are separated from deterministic regression lanes

Talos already has the beginning of this shape with JSON scenarios, claim tags,
executor-path seams, and Gradle E2E summaries that report V1 resources and
claims. The gap is that Talos does not yet have OpenClaw-style coverage metadata
such as primary/secondary coverage IDs, docs/code refs, success criteria, and a
per-scenario trajectory artifact.

### MEAP Book

Per the source pack, the book is useful for:

- processing-loop mental models
- trajectory capture
- BaseTool / ToolCall / ToolCallResult style abstractions
- memory and human-in-the-loop vocabulary

Talos already has matching concepts in `ToolCall`, `ToolResult`,
`ToolCallLoop.LoopResult`, `ToolCallLoop.ToolOutcome`, and `ExecutionOutcome`.
The missing piece is not vocabulary. The missing piece is durable trajectory
evidence: each scenario should preserve enough structured facts to explain what
the loop did and why the final outcome was accepted, blocked, partial, or
unverified.

### OpenAI Evaluation Guidance

OpenAI's eval guidance reinforces three points relevant to Talos:

- task-specific evals are better than vague quality checks
- logs/traces are needed to mine failures and compare changes
- agent workflows should be judged on tool choice, arguments, guardrail
  violations, and end-to-end trace behavior

Talos V1 aligns with task-specific scripted scenarios. It does not yet fully
align with trace grading or continuous coverage inventory.

### OpenHands

OpenHands is useful as a methodology source because it separates:

- runtime/sandbox execution
- simulated user responses in evaluation
- max-iteration controlled agent runs
- collected `EvalOutput` style artifacts

Talos already has an analogous split in `ScenarioRunner`: tool execution runs
against a fixture workspace, and approval/user behavior is deterministic. The
implementation should stay Java/Windows-first and should not import Docker-first
assumptions as Talos policy.

### OWASP and Prompt-Injection Sources

The source pack ranks prompt-injection research and OWASP LLM Top 10 as
mandatory safety references. The current V1 pack does not yet cover the relevant
safety classes:

- indirect prompt injection in local files or retrieved content
- insecure tool design / bad argument handling
- excessive agency through repeated or unsolicited actions
- overreliance on unsupported model claims

Some Talos runtime guards reduce these risks, but the scenario pack should not
claim prompt-injection or tool-abuse resistance until adversarial scenarios
exist.

---

## 9. Current Gaps That Matter

### 1. No First-Class Phase Model

The V1 pack can show that some scripted turns inspect before acting. It cannot
prove phase discipline. Current code still lacks:

- `ExecutionPhase`
- phase transitions
- phase-aware tool policy
- write/edit blocking during inspect or verify

This remains the next major runtime architecture move.

### 2. No Task-Level Verifier

Current checks prove file effects and some answer truthfulness. They do not
prove task completion.

Missing:

- expected target changed
- forbidden targets unchanged
- post-apply static verification result
- distinction between applied, verified, failed verification, and unverified

### 3. Failure Discipline Is Still Too Coarse

The loop cap is necessary but not enough.

The target behavior is:

- repeated same missing path stops early
- repeated same failed edit stops or downgrades
- approval denial is terminal for that mutation path
- no-progress turns stop with a truthful outcome

The recent approval-denial failure-discipline fix belongs in this direction and
should be reflected by expanding scenario `05` or adding a dedicated scenario.

### 4. No Adversarial Safety Pack

The V1 pack is mostly regression and trust behavior. It is not yet a security
scenario pack.

Needed later:

- malicious README tries to override Talos policy
- retrieved document requests a write
- workspace file embeds fake tool instructions
- model emits mutating tool for a read-only prompt after reading hostile content
- tool arguments contain template/path debris

### 5. Trace/Report Surface Is Useful but Still Too Thin

Gradle already extracts scenario resources, V1 flags, claims, pass/fail status,
and traceability status into the E2E summary. That is real progress.

The remaining gap is trajectory evidence. Tier-1 reference architecture needs
enough per-scenario detail to explain behavior without reading every test body.

Each scenario should eventually expose:

- scenario ID
- coverage IDs
- user prompt
- runner type
- scripted model turns
- tools called
- approvals asked/granted/denied/remembered
- files changed
- failed tool calls
- loop status
- verification status
- final outcome classification

---

## 10. Recommended Scenario Backlog

Add these in order as the relevant runtime work lands.

### Immediate V1.0.x Hardening

- add report-visible assertion for strict-mode counters
  - expected: alias rescue and redundant-read cushions are measurable in summary

### Phase Policy V1.1

- `inspect-phase-blocks-write.json`
  - user asks diagnose-only; model emits write; runtime blocks due to phase
- `apply-phase-still-asks-approval.json`
  - explicit mutation enters apply and still requires approval
- `verify-phase-blocks-write.json`
  - after apply, model tries another edit during verify; runtime blocks it

### Static Verifier V1.2

- `apply-succeeds-verifier-fails.json`
  - file write succeeds but static verifier finds unresolved selector/linkage
- `apply-succeeds-verifier-passes.json`
  - expected target changed and static web coherence checks pass
- `partial-mutation-not-verified-as-complete.json`
  - one mutation succeeds, one fails, verifier does not bless the whole task

### Safety/Adversarial V1.3

- `hostile-readme-cannot-trigger-write.json`
- `retrieved-context-cannot-grant-permission.json`
- `template-path-debris-blocked-before-approval.json`
- `read-only-after-hostile-content-remains-read-only.json`

### Failure Policy V1.4

- `same-missing-path-stops-before-loop-cap.json`
- `same-edit-failure-downgrades-to-inspect.json`
- `same-tool-no-progress-stops-with-blocked-outcome.json`

---

## 11. Practical Guidance for Next Work

Do not replace the harness.

Do improve it in place:

- keep the JSON scenario resources
- keep executor-path scenarios visible
- keep strict-mode scenarios separate from user-mode confidence
- add coverage IDs and evidence strength to scenario metadata
- add scenario trace/report output before growing the scenario count too far
- avoid claiming unsupported architecture guarantees

The next implementation ticket should still be:

```text
local/tickets/talos-minimal-execution-phase-policy.md
```

After that:

```text
local/tickets/talos-static-task-verifier.md
```

The scenario pack should grow immediately around those two tickets. Otherwise
phase policy and verifier work will become another set of local patches instead
of measurable architecture.

---

## 12. Summary

The V1 scenario pack is good and worth keeping.

Its correct role is:

- deterministic regression baseline
- reviewer-facing scenario discipline
- evidence that current truth/approval/session/failure guards work for known
  shapes
- the scoreboard for the next runtime architecture slices

Its incorrect role would be:

- proof that Talos already has full execution discipline
- proof that Talos verifies task completion
- proof that Talos is security-hardened against prompt injection
- proof that live installed-CLI behavior is solved

The next level is not more scenarios by volume. The next level is stronger
scenario evidence tied to first-class runtime concepts:

```text
ExecutionPhase -> TaskContract -> TaskOutcome -> TaskVerifier -> FailurePolicy
```

That is the path from useful V1 harness to reference-grade local operator
architecture.
