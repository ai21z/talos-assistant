# T740 - Sampling Governance For Provider Requests

Status: done - completed in wave 1; see completion evidence section
Severity: high
Release gate: yes - converts the flaky live bank into a reproducible regression gate
Branch: codex/wave1-stability-and-cycle
Created/updated: 2026-06-10
Owner: unassigned

## Problem

Talos sends no sampling parameters on any provider request, so llama-server
defaults apply (temperature ≈ 0.8, top_p 0.95, random per-request seed). The
decisive artifact: the r1-failing and focused-passing provider bodies for
`workspace-batch-apply-approved` are **byte-identical**, yet one run emitted a
valid tool call and the other emitted protocol debris. "Passes focused, fails
in bank" was re-rolled randomness, not bank-position state. The release gate
is currently a dice game.

## Evidence Analysis

- `src/main/java/dev/talos/engine/compat/CompatChatClient.java:144-167`
  (`buildBody`) emits only `model/messages/stream/tools/tool_choice/response_format`
  - no temperature/top_p/top_k/seed.
- `LlamaCppServerManager.buildCommand` (113-152) passes no `--temp/--top-p/--seed`;
  live config `server_args: []`.
- `spi/types/ChatRequestControls.java:13-19` record has five components
  (toolChoice, namedTool, responseFormat, jsonSchema, debugTags) and **13
  construction sites** in main (ProviderRequestControlPolicy:66, DEFAULTS:20,
  LlmClient:501,946, ExactWriteContextFallback:161, 8 toolcall planners) -
  positional widening would churn all of them; `LlmClient.withDebugTag`
  (939-952) reconstructs field-by-field.
- Qwen model-card guidance: temp 0.7 / top_p 0.8 / top_k 20 general;
  near-greedy is standard practice for tool-protocol turns on quantized 14B.
- At even 3-5% per-scenario emission failure, a 31-scenario bank fails
  somewhere in ≥60-80% of runs - matching 3/3 failed banks (scenarios 31, 25,
  31) with every focused rerun passing.
- `Config.java:286-291` `llm` section has only transport/default_backend/model;
  no sampling keys exist anywhere. Prompt-debug/provider-body capture is
  already wired (CompatChatClient.java:69-70), so presence/absence of sampling
  fields is assertable from artifacts.

## Architectural Hypothesis

Sampling is a provider-control concern exactly like tool choice; it belongs in
`ChatRequestControls` decided by `ProviderRequestControlPolicy` (single
obligation→controls decision point), with optional config-level overrides
stamped centrally in `LlmClient`.

## Architecture Metadata

Capability: provider request sampling control
Operation(s): all provider chat requests (managed llama.cpp; check Ollama body builder)
Owning package/class: `dev.talos.spi.types.ChatRequestControls` (+ new
`SamplingControls`), `dev.talos.runtime.policy.ProviderRequestControlPolicy`,
`dev.talos.core.llm.LlmClient`, `dev.talos.engine.compat.CompatChatClient`,
`dev.talos.core.Config`
New or changed tools: none
Risk, approval, and protected paths: unchanged (request shaping only)
Checkpoint, evidence, verification, and repair: unchanged
Outcome and trace:
  - Trace/debug fields: prompt-debug/provider-body show sampling fields when set
Refactor scope: the named classes plus tests; zero churn at the 13
ChatRequestControls construction sites (convenience ctor)

## Required Behavior

- New record `SamplingControls(Double temperature, Double topP, Integer topK,
  Long seed)` with `none()` and `NEAR_GREEDY` (0.2 / 0.8 / 20 / null seed).
- Add `SamplingControls sampling` component to `ChatRequestControls`:
  compact-ctor normalizes null → `none()`; keep a 5-arg convenience ctor
  delegating with null (zero call-site churn); add `withSampling(...)` copier.
- `ProviderRequestControlPolicy` attaches `NEAR_GREEDY` wherever it returns
  REQUIRED/NAMED (tool-obligation turns). Non-obligation turns: sampling unset
  (server defaults preserved - no silent behavior change for chat).
- Config keys `llm.sampling.seed|temperature|top_p|top_k` (putIfAbsent
  defaults pattern; unset by default). `LlmClient` stamps configured values
  onto outgoing controls (mirror `withDebugTag`), config overriding unset
  fields only.
- `CompatChatClient.buildBody` emits `temperature/top_p/top_k/seed` when
  present. Verify whether a separate Ollama body builder exists and needs the
  same emission.

## Non-Goals

- No grammar/json_schema changes; no `response_format` fixes (latent shape bug
  noted in the evaluation is out of scope).
- No retry-path escalation (T743).

## Tests

- `SamplingControls` defaults/normalization unit test.
- `ChatRequestControlsTest`: convenience ctor produces `none()`; `withSampling`
  preserves other fields.
- `ProviderRequestControlPolicyTest`: obligation turns carry NEAR_GREEDY;
  direct-answer turns carry `none()`.
- `CompatChatClientTest`: body contains the four fields when set; absent when
  `none()` (existing HttpServer-mock body-assertion style).
- Config test: keys parsed; unset by default; LlmClient stamping honors
  config seed.

## Acceptance Criteria

- Focused runs green for all the above test classes.
- Provider-body artifact for an obligation turn shows sampling fields; a
  small-talk turn shows none (asserted in T746 live evidence).
- A fixed `llm.sampling.seed` in a harness config produces identical
  provider bodies across reruns of the same scenario (used by T745 A/B).
- CHANGELOG `## [Unreleased]` gains a T740 entry.

## 2026-06-10 completion evidence

- Implemented: `spi/types/SamplingControls.java` (none()/NEAR_GREEDY/anySet/
  mergedWithFallback); `ChatRequestControls` gains a sixth `sampling`
  component with compact-ctor normalization, a 5-arg convenience ctor (zero
  churn at the 13 construction sites), and `withSampling`;
  `ProviderRequestControlPolicy` attaches NEAR_GREEDY whenever it returns
  REQUIRED/NAMED; `LlmClient` parses `llm.sampling.{temperature,top_p,top_k,
  seed}` into `configSampling` and layers it under turn controls at both
  request paths (`withConfigSampling`); `withDebugTag` now preserves sampling
  (it previously reconstructed field-by-field and would have dropped it);
  `CompatChatClient.buildBody` emits the four fields when set;
  `PromptDebugInspector` renders a `- Sampling:` line when any field is set.
- Ollama note: the legacy Ollama transport does not flow ChatRequestControls
  sampling (it reports no required-tool-choice support and the policy never
  decorates its turns); acceptable for the legacy backend.
- Tests green (focused): ChatRequestControlsTest (+4),
  ProviderRequestControlPolicyTest (+1 sampling test),
  CompatChatClientTest (+2 wire tests), LlmClientSamplingConfigTest (new, 3).
- Full unit lane green after the record change:
  `./gradlew.bat test --no-daemon` BUILD SUCCESSFUL (1m51s).
- Live provider-body confirmation deferred to T746 banks; fixed-seed A/B in
  T745.
