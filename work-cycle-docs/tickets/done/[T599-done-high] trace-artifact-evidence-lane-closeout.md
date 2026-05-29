# [T599] Trace and artifact evidence lane closeout

## Decision

Close the trace/artifact evidence hygiene lane.

Do not start a `T600` implementation ticket from this lane.

The correct next move is to stop the implementation-burn-down cadence and plan
the deep manual Talos test packet from fresh `v0.9.0-beta-dev` evidence.

This does not mean Talos is release-ready. It means the current hygiene lane has
reached the point where more source-level extraction would be weaker evidence
than running Talos hard against the actual installed product, prompts, traces,
prompt-debug artifacts, provider bodies, session/turn logs, approval prompts,
workspace diffs, and artifact canary scans.

## Source Evidence

Inspected from fresh `origin/v0.9.0-beta-dev` at `611eb206`.

| File | Lines | Why inspected |
| --- | ---: | --- |
| `work-cycle-docs/tickets/done/[T550-done-high] next-hygiene-lane-decision.md` | 235 | Selected trace/artifact evidence ownership as the hygiene lane after the tool-loop outcome value lane. |
| `work-cycle-docs/tickets/done/[T551-done-high] trace-artifact-evidence-ownership-decision.md` | 286 | Initial trace/artifact evidence ownership decision and prompt-debug redaction slice selection. |
| `work-cycle-docs/tickets/done/[T557-done-high] prompt-debug-command-artifact-lane-closeout.md` | 199 | Closed prompt-debug command/artifact sublane after redactor, writer, and destination resolver extraction. |
| `work-cycle-docs/tickets/done/[T596-done-high] local-trace-event-shape-lane-closeout.md` | 153 | Closed local trace event-shape extraction after event-family owners were extracted. |
| `work-cycle-docs/tickets/done/[T597-done-high] trace-lifecycle-persistence-ownership-decision.md` | 264 | Decided not to extract trace lifecycle or trace persistence. |
| `work-cycle-docs/tickets/done/[T598-done-high] runtime-artifact-canary-ownership-decision.md` | 240 | Decided artifact canary scanning remains a release/test gate. |
| `src/main/java/dev/talos/runtime/trace/LocalTurnTraceCapture.java` | 466 | Current trace facade/lifecycle shape after event-family extraction. |
| `src/main/java/dev/talos/cli/repl/slash/PromptDebugCommand.java` | 128 | Current prompt-debug command facade after artifact writing and destination resolution extraction. |
| `src/main/java/dev/talos/runtime/policy/ArtifactCanaryScanner.java` | 148 | Current artifact canary gate owner. |
| `src/main/java/dev/talos/runtime/JsonSessionStore.java` | 575 | Current session, turn JSONL, and local trace persistence owner. |
| `work-cycle-docs/blended-manual-audit-scenario-bank.md` | 261 | Manual scenario bank requiring trace, prompt-debug, and artifact-scan evidence. |
| `work-cycle-docs/full-e2e-audit-workflow.md` | 293 | Full manual audit workflow and evidence requirements. |
| `work-cycle-docs/full-e2e-audit-operator-prompt.md` | 109 | Operator prompt for deep full E2E audit execution. |

## What This Lane Completed

### Prompt-debug command/artifact ownership

Completed through `T552`-`T557`.

The lane separated prompt-debug artifact concerns without moving the broader
provider/request capture lifecycle:

- `PromptDebugRedactor` owns prompt-debug message/provider-body redaction.
- `PromptDebugArtifactWriter` owns timestamped markdown/provider-body artifact
  writes and save-all index writing.
- `PromptDebugDestinationResolver` owns destination precedence and quoted path
  handling.
- `PromptDebugCommand` remains the hidden CLI command facade.
- `PromptDebugInspector` remains the maintainer display facade.
- `PromptDebugCapture` and `PromptDebugSnapshot` remain SPI/process-local
  capture surfaces.

Correctly rejected:

- prompt-debug lifecycle movement;
- provider-body capture normalization;
- artifact canary movement from the prompt-debug lane.

### Local trace event-family ownership

Completed through `T558`-`T596`.

`LocalTurnTraceCapture` remains the public thread-local trace facade, but the
former event-shape responsibilities now sit behind dedicated owners:

- command events -> `CommandTraceEventFactory`
- private-document handoff events -> `PrivateDocumentHandoffTraceEventFactory`
- permission decision events -> `PermissionTraceEventFactory`
- checkpoint summary/events -> `CheckpointTraceRecorder`
- protected-read postcondition events -> `ProtectedReadPostconditionTraceEventFactory`
- protocol sanitization events -> `ProtocolSanitizationTraceEventFactory`
- backend malformed response events -> `BackendMalformedResponseTraceEventFactory`
- exact literal write correction events -> `ExactLiteralWriteCorrectionTraceEventFactory`
- path argument normalization events -> `PathArgumentNormalizationTraceEventFactory`
- tool alias decision events -> `ToolAliasDecisionTraceEventFactory`
- model response summary/events -> `ModelResponseTraceRecorder`
- policy trace summary/events -> `PolicyTraceRecorder`
- prompt audit summary/events -> `PromptAuditTraceRecorder`
- repair summary/events -> `RepairTraceRecorder`
- verification summary/events -> `VerificationTraceRecorder`
- outcome summary/events -> `OutcomeTraceRecorder`
- expectation verification events -> `ExpectationVerificationTraceEventFactory`
- pending action-obligation events -> `PendingActionObligationTraceEventFactory`
- action-obligation events -> `ActionObligationTraceEventFactory`

Correctly rejected:

- generic tool lifecycle factory wrapping;
- warning extraction from trace lifecycle;
- broad `LocalTurnTraceCapture` movement;
- trace lifecycle extraction during the event-shape lane.

### Trace lifecycle and persistence ownership

Closed by `T597`.

Current ownership is coherent enough to stop:

- `TurnProcessor` owns runtime turn boundaries and starts/completes trace
  capture.
- `LocalTurnTraceCapture` owns thread-local trace assembly, current trace id,
  current turn number, outcome dominance guard, and context-ledger pairing.
- `TurnAudit` carries the completed local trace out of thread-local state.
- `JsonTurnLogAppender` persists completed turn evidence after the turn.
- `SessionStore` is the persistence seam.
- `JsonSessionStore` is the file-backed implementation for session snapshots,
  turn JSONL, and local trace JSON artifacts.
- `ExplainLastTurnCommand` is the CLI debug rendering surface for persisted
  turn/trace evidence.

Correctly rejected:

- a pass-through trace persistence wrapper;
- moving `/last trace` rendering into runtime;
- merging prompt-debug and local trace lifecycle.

### Artifact canary ownership

Closed by `T598`.

Current ownership is coherent enough to stop:

- `ArtifactCanaryScanner` owns deterministic scan mechanics and sanitized
  finding snippets.
- `ArtifactCanaryScanCli` owns command-line invocation and exit semantics.
- `checkGeneratedArtifactCanaries` runs during normal `check`.
- `checkRuntimeArtifactCanaries` is an explicit maintainer/live-audit gate over
  selected evidence roots.

Correctly rejected:

- live-turn canary scanning;
- scan-root manifest extraction without release-packet evidence;
- allowlist provenance modeling before manual audit proves the need;
- merging prompt-debug/session/trace persistence policy into the scanner.

## Current Stop Point

The trace/artifact evidence lane has removed the obvious ownership confusion
without over-extracting the remaining lifecycle and gate surfaces.

The remaining source-level risks in this area are not good automatic extraction
tickets:

- prompt-debug/provider-body capture lifecycle is cross-layer SPI/core/engine
  behavior;
- local trace lifecycle is turn-boundary behavior;
- trace persistence is session-store behavior;
- artifact canary scanning is release/test-gate behavior;
- warning ownership crosses outcome, protected-read containment, exact-write
  fallback, compact continuation, and retry budget policy.

Treating any of those as the next automatic implementation ticket would be
counter-chasing.

## Next Correct Move

Do not start another implementation hygiene ticket yet.

Start a manual test planning packet from the current beta head:

```text
Manual Talos deep test packet
```

The next work should:

1. reset or create a clean audit worktree/environment from fresh
   `origin/v0.9.0-beta-dev`;
2. record branch, commit, version, backend, model, installed executable, and
   evidence roots;
3. run deterministic gates first;
4. build and clean-install the current candidate if the test is
   installed-product relevant;
5. run a focused manual prompt bank before claiming full audit coverage;
6. capture `/last trace`, `/prompt-debug last`, `/prompt-debug save`,
   provider-body JSON, session/turn artifacts, approval evidence, command
   output, verifier output, workspace status, and workspace diff;
7. run `checkRuntimeArtifactCanaries` over the selected audit roots;
8. classify every answer against evidence, not final prose.

This should be planned before execution. The audit should be stressful but
controlled, with fresh fixtures and no stale artifact reuse.

## Recommended Manual Test Scope

Start with a milestone packet, not a claimed full release audit.

A correct first packet should cover:

- identity and local-first boundaries;
- no-workspace/general prompt privacy;
- minimal directory listing and evidence disclosure;
- retrieval/grounding over known fixture facts;
- protected read denial;
- approved protected read with no raw secret in final answer;
- prompt-debug redaction and provider-body redaction;
- `/last trace` correctness after real turns;
- proposal-only versus apply distinction;
- approval denial and retry behavior;
- one exact edit/write path;
- static web repair with similar-file trap;
- command profile boundary;
- runtime artifact canary scan over captured evidence roots.

Do not claim full audit coverage unless every native tool is probed or
explicitly excluded with rationale.

## Why Manual Testing Beats Another Extraction Now

The last several lanes improved ownership in source code. That is useful, but
Talos's real risk is not only source shape. It is runtime truthfulness under
real prompts:

- final answers can still overclaim;
- prompt-debug evidence can still contradict the final answer;
- provider-body evidence can expose prompt construction mistakes;
- `/last trace` can expose task-contract or tool-surface mistakes;
- approval prompts can fail in terminal UX even when unit tests pass;
- artifact canary gates can pass on generated reports but fail on real manual
  audit roots;
- model behavior can vary between Qwen and GPT-OSS.

The next strongest evidence is a controlled manual run, not another small class.

## Acceptance Criteria

- T599 makes no runtime code changes.
- The trace/artifact evidence lane is closed explicitly.
- The completed sublanes are summarized.
- Remaining risks are assigned to manual audit/release evidence rather than
  automatic extraction.
- The next move is manual test planning, not a new implementation ticket.
- No generated artifacts, prompt-debug evidence directories, or user site
  changes are committed.

## Verification

This ticket is documentation-only. Required gates:

- `git diff --check`
- `validateArchitectureBoundaries`
- full `check`
