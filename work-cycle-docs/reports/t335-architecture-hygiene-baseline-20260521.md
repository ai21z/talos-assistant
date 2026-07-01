# T335 Architecture Hygiene Baseline - 2026-05-21

## Scope

Static architecture baseline for Talos code hygiene, dependency direction,
policy ownership, dependency injection seams, verification ownership, CLI
composition, and release evidence gates.

This report does not change runtime behavior. It is the evidence-backed map for
the next refactor sequence.

## Provenance

```text
Branch: v0.9.0-beta-dev
Commit inspected: c32957e95925168947b46e60a393e09091d90bb3
Candidate version: talosVersion=0.9.9
Date: 2026-05-21
Audit type: static source/report/ticket audit
Runtime Talos execution: no
Live model audit: no
Version bump: no
```

The worktree was already dirty from the T334 release-ledger work when this
baseline began. The known local untracked mangled prompt-debug evidence
directory also remained present:

```text
UsersariszProjectsLOQloqj-clilocalmanual-testingtrue-pty-manual-20260520-r1artifactsprompt-debug/
```

## Sources Used

Internal project sources:

- `AGENTS.md` project doctrine supplied in the current thread.
- `docs/architecture/01-execution-discipline-and-local-trust.md`
- `docs/architecture/02-runtime-policy-ownership-map.md`
- `docs/architecture/08-capability-growth-guardrails.md`
- `work-cycle-docs/tickets/done/[T31-done-high] map-runtime-policy-ownership-before-extraction.md`
- `work-cycle-docs/tickets/done/[T126-done-high] architecture-quality-guardrails-and-refactoring-map.md`
- `work-cycle-docs/reports/audit-dependency-matrix-20260520.md`
- `work-cycle-docs/reports/beta-stabilization-backlog-reconciliation-20260520.md`

External references used for cross-check only:

- Martin Fowler, "Inversion of Control Containers and the Dependency Injection pattern":
  https://www.martinfowler.com/articles/injection.html
- ArchUnit user guide:
  https://www.archunit.org/userguide/html/000_Index.html
- OpenAI Codex security and agent-approval documentation:
  https://developers.openai.com/codex/security and
  https://developers.openai.com/codex/agent-approvals-security
- Gemini CLI tools documentation:
  https://www.geminicli.com/docs/reference/tools

External references were used as design checks, not as code sources. The useful
common lesson is narrow: serious local agent harnesses make permissions,
sandboxing, tool surfaces, and evidence explicit policy surfaces. They do not
justify adding a DI framework, broad plugin system, background autonomy, or
multi-agent runtime to Talos.

## Method

Five read-only static audit lanes were run in parallel:

- runtime orchestration and policy ownership
- verification, repair, static web, and outcome truthfulness
- package boundaries and dependency direction
- CLI, REPL, bootstrap, UI, and session state
- audit, release evidence, TalosBench, and report gates

No agent was instructed to edit files. The local static inventory then
cross-checked the agent findings with direct source searches and project
architecture documents.

## Inventory Snapshot

Largest production Java/Kotlin/Gradle/PowerShell pressure points, excluding
build outputs and local manual artifact roots:

| File | Lines | Architectural role |
|---|---:|---|
| `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java` | 5225 | turn orchestration, prompt shaping, retry, outcome integration |
| `src/main/java/dev/talos/runtime/verification/StaticTaskVerifier.java` | 2661 | verification framework, static web checks, source-derived checks |
| `src/main/java/dev/talos/runtime/toolcall/ToolCallRepromptStage.java` | 2564 | repair, reprompt, continuation, provider-control logic |
| `build.gradle.kts` | 1700 | test, evidence, quality, report, and candidate summary tasks |
| `src/main/java/dev/talos/cli/modes/ExecutionOutcome.java` | 1530 | outcome truth policy and final answer shaping |
| `src/main/java/dev/talos/runtime/task/TaskContractResolver.java` | 1258 | task intent, target extraction, phase/evidence implications |
| `src/main/java/dev/talos/runtime/TurnProcessor.java` | 1199 | tool execution, approval, permission, phase, path gates |
| `tools/manual-eval/run-talosbench.ps1` | 1300 | live/manual evaluation runner and evidence capture |
| `src/main/java/dev/talos/runtime/toolcall/ToolCallExecutionStage.java` | 1106 | tool execution stage and loop result handling |
| `src/main/java/dev/talos/core/llm/LlmClient.java` | 1093 | model transport/client behavior |

These sizes are not bugs by themselves. They become architecture findings where
they coincide with policy ownership collapse, package cycles, or release-risk
ordering.

## Dependency Direction Findings

### ARCH-001 - runtime/core depend on CLI

Severity: P1

Evidence:

- `src/main/java/dev/talos/runtime/TurnProcessor.java` imports
  `dev.talos.cli.modes.ModeController`, `dev.talos.cli.repl.Context`, and
  `dev.talos.cli.repl.Result`.
- `src/main/java/dev/talos/runtime/ToolCallLoop.java` imports
  `dev.talos.cli.repl.Context`.
- `src/main/java/dev/talos/runtime/toolcall/LoopState.java` imports
  `dev.talos.cli.repl.Context`.
- `src/main/java/dev/talos/runtime/CliApprovalGate.java` imports CLI UI
  renderers.
- `src/main/java/dev/talos/core/context/ConversationManager.java` imports
  `dev.talos.cli.repl.SessionMemory`.
- `src/main/java/dev/talos/core/index/IndexedWorkspaceSymbolChecker.java`
  imports a CLI mode interface.

Why it matters:

Runtime and core are not headless below the CLI. That contradicts the intended
direction in `docs/architecture/08-capability-growth-guardrails.md`. It also
makes programmatic API, test harness, and future non-terminal surfaces inherit
CLI REPL state and rendering concepts.

Fix direction:

Move the shared runtime records and ports currently housed under CLI into
runtime/core/spi packages, then let the CLI depend on those ports. Terminal
rendering stays in CLI adapters.

Required regression:

Add an architecture test or import scanner that fails new imports from
`dev.talos.runtime..` or `dev.talos.core..` into `dev.talos.cli..`.

### ARCH-002 - core/runtime/tools form cyclic ownership

Severity: P1

Evidence:

- `core -> runtime`: `RagService`, `DocumentExtractionService`,
  `DocumentExtractionPreflight`, `Indexer`, and related core classes import
  runtime context/policy classes.
- `runtime -> core/tools`: `TurnProcessor`, `StaticTaskVerifier`,
  `ToolSurfacePlanner`, and `ToolCallExecutionStage` import core and tools.
- `tools -> runtime`: `RunCommandTool`, `ToolRegistry`, `ReadFileTool`,
  `GrepTool`, `FileWriteTool`, `FileEditTool`, and related tools import
  runtime command, policy, and trace classes.
- `engine -> runtime`: `CompatChatClient` and `OllamaChatClient` import
  `SafeLogFormatter` from runtime policy.
- `spi -> core`: `EngineRegistry`, `CorpusStore`, and `ModelEngineProvider`
  import core config or metadata types.

Why it matters:

This makes `core`, `runtime`, and `tools` behave like one cyclic module. That
blocks clean dependency injection because the composition root cannot simply
provide lower-level services to upper-level policies; lower layers already know
about upper-layer runtime decisions.

Fix direction:

Define a small set of neutral contract packages before moving behavior:

- runtime policy and turn orchestration records
- tool API contracts separate from tool implementations
- core extraction/retrieval primitives that do not import runtime turn policy
- engine SPI config records that do not import broad core types

Required regression:

Introduce a package-boundary test with a baseline allowlist, then ratchet it so
new forbidden edges fail immediately.

## Policy Ownership Findings

### POL-001 - `AssistantTurnExecutor` is still the central policy warehouse

Severity: P1

Evidence:

`AssistantTurnExecutor` owns turn planning, prompt mutation, evidence handoff,
direct deterministic answers, static repair injection, retry policy, outcome
shaping, mutation truth policy, denied/invalid summaries, inspect retry, and
unsupported-document cleanup.

Why it matters:

It is too easy to add a new feature by dropping another phrase list, repair
branch, or final-answer patch into the executor. That is the exact failure mode
the earlier architecture docs warned about.

Fix direction:

First extraction candidates:

- `TurnPlanningService`
- `PromptAssemblyService`
- `ReadEvidenceHandoffController`
- `MutationRetryController`
- `OutcomeRenderingService`

Do not extract all at once. Start with pure behavior-preserving seams and keep
the executor as orchestrator.

### POL-002 - `TurnProcessor.executeTool` interleaves safety gates

Severity: P1

Evidence:

`TurnProcessor.executeTool` resolves aliases, tool surface, task-contract
fallback, path normalization, directory-listing policy, read-only mutation
denial, phase policy, placeholder guards, validators, command planning, scope
warning, permission decision, approval, checkpoint, and tool execution in one
method.

Why it matters:

This method carries approval, protected path, workspace escape, and checkpoint
ordering. A refactor that changes ordering can become a release blocker even if
unit tests for individual helpers pass.

Fix direction:

Extract `ToolExecutionPolicyPipeline` up to the approval gate while preserving
exact order:

1. hidden surface denial
2. task-contract read-only denial
3. phase denial
4. placeholder rejection
5. sandbox/path validation
6. forbidden/expected-target validation
7. command planning
8. permission decision
9. approval
10. checkpoint
11. tool execution

Required regression:

Add pipeline tests proving approval is not reached for phase denial, protected
mutation denial, workspace escape, hidden tool, wrong expected target, or
invalid command profile.

### POL-003 - tool surface decisions can drift across layers

Severity: P1

Evidence:

- `ToolSurfacePlanner` selects advertised tools.
- `AssistantTurnExecutor` applies native tool spec policy.
- `TurnProcessor` rejects calls outside the current surface.
- `ProviderRequestControlPolicy` separately decides provider tool choice.

Why it matters:

The model-visible surface, runtime execution surface, and provider controls
should derive from one current-turn plan. If they drift, Talos can advertise,
require, or execute different tool sets for the same turn.

Fix direction:

Make `CurrentTurnPlan` or a sibling immutable record the single source for
visible tools, executable tools, required provider controls, and blocked-tool
rationale.

## Verification, Repair, And Outcome Findings

### VRT-001 - `StaticTaskVerifier` is a verifier framework hidden in one class

Severity: P1

Evidence:

`StaticTaskVerifier` imports extraction, capability profiles, task
expectations, tracing, workspace operation plans, and alias policy. Its
verification path handles expected targets, mutated targets, exact edit
evidence, workspace operations, source-derived artifacts, and static web.

Why it matters:

Static web verification, workspace operation verification, document/source
truthfulness, and generic target verification have different ownership and test
needs. Keeping them in one class increases the chance that a small verifier
change weakens an unrelated release gate.

Fix direction:

Extract in this order:

1. `VerificationContext`
2. `TaskVerificationPipeline`
3. `WorkspaceOperationStaticVerifier`
4. `StaticWebSurfaceDetector`
5. `StaticWebFacts`
6. `StaticWebVerifier`
7. `SourceDerivedArtifactVerifier`

### VRT-002 - static web evidence obligation is too generic

Severity: P1

Evidence:

`EvidenceObligationVerifier` can satisfy `STATIC_WEB_DIAGNOSIS_REQUIRED` via
generic content inspection. The `read_file` path checks static-web targets, but
`grep` and `retrieve` can pass without equivalent static-web target validation.

Why it matters:

A successful grep/retrieve against unrelated content can satisfy a static-web
diagnosis obligation. That is a direct grounding gap.

Required regression:

Add a test proving successful `talos.grep` on `README.md` does not satisfy a
static-web diagnosis requirement. Require inspected target metadata or
static-web path evidence for grep/retrieve.

### VRT-003 - repair state is string-coupled

Severity: P1

Evidence:

`RepairPolicy` renders a magic text context beginning with
`[Static verification repair context]`. `ToolCallRepromptStage` detects it via
string prefix checks, and `RepairPolicy.fullRewriteTargetsFromRepairContext`
reparses rendered text.

Why it matters:

Repair behavior depends on prompt prose. A wording change can break full-rewrite
target extraction or repair routing.

Fix direction:

Carry a structured `RepairPlan` through loop state and trace. Render prose only
at the prompt boundary.

Required regression:

Changing repair instruction wording must not change full-write target
extraction.

### VRT-004 - outcome dominance uses primitive boolean precedence

Severity: P1

Evidence:

`OutcomeDominancePolicy.Facts` carries many booleans. `ExecutionOutcome` builds
those facts after several answer rewrites, then a precedence chain decides
which signal wins.

Why it matters:

False-success prevention depends on implicit boolean ordering. Adding one new
failure signal can accidentally weaken a stronger one.

Fix direction:

Replace primitive facts with ranked `OutcomeSignal` records carrying severity,
owner, and replacement policy. Keep existing table tests and expand dominance
combination coverage.

## CLI, REPL, And Composition Findings

### CLI-001 - `Context.Builder` has unsafe production-looking defaults

Severity: P1

Evidence:

`Context.Builder.build()` can create `NoOpApprovalGate`, `Sandbox(Path.of("."))`,
`LlmClient`, `RagService`, and other broad defaults. Production construction
currently routes through `TalosBootstrap`, but the type itself does not force
explicit trust-boundary dependencies.

Why it matters:

Any new caller can accidentally build a context with no approval gate and a
current-directory sandbox. That is not a theoretical hygiene issue; it is an
unacceptable default at a local trust boundary.

Fix direction:

Split production runtime context construction from test context factories.
Production construction should require explicit approval gate, sandbox, tool
registry, session memory, and phase state.

Required regression:

Architecture/static test rejecting `Context.builder(...).build()` outside tests
or explicit test factories.

### CLI-002 - CLI slash commands mutate outside the tool governance path

Severity: P1

Evidence:

`PromptDebugCommand`, `SetupCmd`, and `SessionCommand` write or delete local
files directly. T333 separately records a prompt-debug Windows absolute path
mangling bug.

Why it matters:

Direct user slash commands may legitimately mutate local state, but they still
need a common mutation/audit path. Today some mutations are tool-governed and
some are ad hoc file operations.

Fix direction:

Introduce `CliMutationService` or equivalent with operation type, target root,
overwrite behavior, path parsing, and evidence record.

Required regression:

`/prompt-debug save` quoted and unquoted Windows absolute paths must preserve
the requested path and must not create repo-relative `Usersarisz...` artifact
directories.

### CLI-003 - `TalosBootstrap` is an auditable but oversized composition root

Severity: P2

Evidence:

`TalosBootstrap.create()` wires config, tools, LLM, session store, approval,
rendering, turn loop, listeners, commands, and notices. `registerCommands()`
hard-codes slash command registration.

Why it matters:

A single composition root is better than hidden construction across the system,
but this one is becoming a god factory. It makes dependency injection harder to
review because every wiring change touches a high-blast-radius method.

Fix direction:

Split into small modules:

- `ToolModule`
- `SessionModule`
- `ApprovalModule`
- `UiModule`
- `SlashCommandModule`
- `TalosRuntimeGraph`

Keep one integration test for the final graph.

## Release Evidence And Audit Findings

### EVD-001 - candidate summaries can render missing results as pass-like

Severity: P1

Evidence:

The audit lane found coverage/e2e summary paths where `no-results` or missing
XML can still produce Markdown that reads as passing when failures/errors are
zero.

Why it matters:

A missing result lane is unknown or blocked, not pass. This is the same class
of failure Talos is designed to prevent in model answers: unsupported success.

Fix direction:

Any `no-results`, `summary-generation-failed`, missing XML/SARIF, or zero-test
candidate lane must be rendered as blocked/unknown and fail release-summary
generation when used as release evidence.

### EVD-002 - not every evidence summary has full candidate provenance

Severity: P1

Evidence:

Qodana summary has stronger branch/SHA/stale-result provenance than coverage,
e2e, and version summaries.

Why it matters:

Same `talosVersion` can exist across dirty local states. Reviewers need branch,
full SHA, dirty state, command identity, timestamp, and installed-product
identity where relevant.

Fix direction:

Add shared provenance records to all candidate summaries.

### EVD-003 - installed-product audits can use stale binaries

Severity: P1

Evidence:

TalosBench resolves explicit `-TalosPath`, environment, installed local app
path, then PATH. Its summary records path, but not enough executable identity:
no full version/commit/hash/install freshness gate.

Why it matters:

Live audit can silently run an old binary while the report appears current.

Fix direction:

Strict/live modes should capture executable path, hash, `talos --version`,
expected candidate version, and fail on mismatch.

## What Not To Do

Do not start by adding Spring, Guice, or another DI framework. Talos' problem is
not absence of a container. The problem is that several policy and evidence
boundaries are not yet explicit enough to be wired safely.

Do not perform a broad package move. Moving code without enforcing dependency
direction only preserves the same cycles under cleaner names.

Do not use DDD/BDD labels as architecture theater. The useful parts here are
ports, adapters, immutable runtime facts, focused policies, and executable
architecture tests.

Do not weaken `TurnProcessor` while extracting policy. Enforcement remains
central until the new policy pipeline has focused tests and equivalent trace
evidence.

Do not run broad live audits as proof of architecture cleanup until evidence
provenance, prompt-debug path handling, and installed-product identity gates
are reliable.

## Target Direction

The target is not a new framework. The target is stricter ownership:

```text
app/cli composition
  -> runtime turn orchestration
  -> runtime policy, verification, repair, outcome, trace
  -> tools API and tool implementations
  -> core extraction, retrieval, config, path/security primitives
  -> engine SPI/adapters
```

Important caveat: this diagram is a target direction, not a claim about the
current code. The current code has confirmed cycles that must be ratcheted down.

## Recommended Refactor Sequence

### Phase 0 - guardrails before movement

Create architecture boundary enforcement before extracting code.

Required work:

- Add package-boundary tests or a Gradle import scanner.
- Start with a baseline allowlist for current violations.
- Fail any new `runtime/core -> cli`, `engine -> runtime`, `spi -> core`, or
  `tools -> runtime` edge.
- Add size/fan-out reporting as a warning-only hygiene report.

Why first:

Without this, refactors can recreate the same cycles silently.

### Phase 1 - release evidence integrity

Fix evidence gates that can produce false or stale release claims.

Required work:

- Close T333 prompt-debug Windows path mangling.
- Treat missing coverage/e2e/qodana lanes as blocked, not passing.
- Add shared provenance blocks to candidate summaries.
- Add installed-product identity checks to TalosBench strict/live modes.

Why before large live audits:

Architecture work needs trustworthy evidence packets. Otherwise the audit
system can lie about which candidate was tested.

### Phase 2 - runtime and CLI boundary split

Break direct runtime/core dependency on CLI types.

Required work:

- Move or replace CLI-owned `Context`, `Result`, `SessionMemory`, and
  `WorkspaceSymbolChecker` dependencies with runtime/core ports.
- Keep terminal rendering in CLI adapters.
- Preserve public CLI behavior.

Required tests:

- Existing `AssistantTurnExecutorTest`, `ToolCallLoopTest`,
  `TurnProcessor*Test`, and session tests.
- New architecture test preventing lower-layer CLI imports.

### Phase 3 - tool execution policy pipeline

Extract policy ordering from `TurnProcessor.executeTool`.

Required work:

- Introduce `ToolExecutionPolicyPipeline`.
- Preserve denial, approval, checkpoint, and execution ordering exactly.
- Add constructor injection for `PermissionPolicy` while keeping existing
  constructors delegating to current behavior.

Required tests:

- Approval not reached for hidden tools, phase denial, read-only mutation,
  workspace escape, protected/forbidden paths, and invalid command profiles.

### Phase 4 - verification and repair structure

Split verification and repair state without broad behavior change.

Required work:

- Extract `WorkspaceOperationStaticVerifier`.
- Extract static web verification facts and verifier.
- Extract source-derived artifact verifier.
- Replace repair prose parsing with structured `RepairPlan`.

Required tests:

- Current `StaticTaskVerifierTest` remains green.
- New tests for static-web grep/retrieve target evidence.
- New test proving repair wording changes do not alter full-write target
  extraction.

### Phase 5 - outcome signals

Replace boolean outcome dominance with ranked signals.

Required work:

- Introduce `OutcomeSignal`.
- Keep existing user-visible output byte-compatible where intended.
- Preserve failure-dominant and privacy-dominant behavior.

Required tests:

- Table tests for dominance combinations.
- Existing `ExecutionOutcomeTest` and `OutcomeDominancePolicyTest`.

### Phase 6 - composition root decomposition

Only after the lower seams exist, split `TalosBootstrap` into modules.

Required work:

- `ToolModule`
- `SessionModule`
- `ApprovalModule`
- `UiModule`
- `SlashCommandModule`
- `TalosRuntimeGraph`

Required tests:

- Module contract tests.
- One integration graph test proving required tools, listeners, and commands
  are wired.

## Next Best Implementation Ticket

The next architecture-hygiene implementation ticket should be:

```text
T336 - Architecture boundary ratchet and package import scanner
```

Continuation status, 2026-05-21:

```text
T336 is implemented and closed as
work-cycle-docs/tickets/done/[T336-done-high] architecture-boundary-ratchet-and-import-scanner.md.
```

Continuation status, 2026-05-21:

```text
T337 is implemented and closed as
work-cycle-docs/tickets/done/[T337-done-medium] move-tool-alias-policy-to-tools-boundary.md.
The architecture-boundary baseline is reduced from 62 to 61 forbidden import edges.
```

Scope:

- no behavior change
- no package movement yet
- add source-level architecture tests/import scanner
- generate a baseline violation report
- fail new dependency-direction regressions

This is the smallest move that improves every later refactor.

Release-evidence note:

If the immediate goal shifts from code hygiene to release-audit readiness,
close T333 before broad audit execution. T333 is not the best architecture
first move, but it is a release-evidence integrity blocker.

## Verification For This Baseline

This report is static documentation. It does not require Talos runtime or model
execution.

Recommended local checks for this ticket:

```powershell
git diff --check
.\gradlew.bat validateReleaseLedger --no-daemon
```

No full Gradle `check` is required for this report because no runtime,
production, test, or build behavior is changed by T335 itself.
