# Domain Specificity and Extensibility Architecture Audit

Date: 2026-04-30
Branch inspected: `v0.9.0-beta-dev`
Version state: `0.9.8`

This is an audit report only. It does not define an implementation patch.

## Executive Verdict

Talos is not simply overfit to BMI or web-page generation. The stronger finding
is mixed specialization:

- Talos has good bounded specialization where a narrow rule is isolated behind a
  clear policy or expectation object. Examples include literal content
  expectations, protected path policy, checkpoint metadata, and directory
  listing minimization.
- Talos also has accidental specialization where web/static-site terms,
  hard-coded file names, task-specific repair rules, and prompt-shape heuristics
  sit inside generic intent, verification, repair, outcome, prompt, and
  evaluation logic.

The latest freestyle transcript is evidence of a general control architecture
problem, not a web-only problem. The failures cluster around:

- current-turn command and conversation boundary handling
- coarse `TaskType` and `TaskContract` semantics
- missing evidence obligations for read-oriented turns
- missing active task/artifact context for deictic follow-ups
- web-specific verification and repair rules embedded in generic classes
- weak prompt/control observability
- tool protocol alias handling that is not profile-owned
- tests and live evals over-weighted toward static web/BMI scenarios

This does affect release confidence for showing Talos as a general local
assistant. It does not mean Talos needs a giant plugin framework now. The right
near-term move is a minimal extension spine:

1. Add prompt-audit/current-turn-plan visibility before further refactors.
2. Introduce `CurrentTurnPlan` as the runtime product that combines contract,
   phase, capability profile, artifact goal, evidence obligation, tool profile,
   verifier profile, repair profile, and output obligation.
3. Split `TaskIntentPolicy` from artifact/profile selection and shrink
   `READ_ONLY_QA`.
4. Add `ActiveTaskContext` and `ArtifactGoal` so follow-ups like "make those
   changes" or "read the files" inherit the right artifact and evidence
   obligations.
5. Move static web verification and repair behind a `StaticWeb` verification
   and repair profile.
6. Keep a static Java capability profile registry. Defer dynamic plugins,
   marketplace behavior, MCP-first expansion, shell/browser, background daemon,
   and multi-agent orchestration.

T47 should not stay a pure one-off "cross-file BMI/web repair" ticket. It can
remain as a symptom ticket, but the strategic fix should be folded into a
general artifact-goal, verification-profile, and repair-profile effort.

## Method

I inspected:

- current branch and history
- the latest freestyle transcript in `local/manual-testing/test-output.txt`
- architecture docs `docs/architecture/01` through `06`
- evaluation docs `docs/evaluation/01` through `03`
- recent T48-T53 tickets and open T47
- current task, policy, prompt, tool-call, verifier, repair, trace, permission,
  checkpoint, command, and evaluation code
- local OpenClaw source under `.claude/openclaw`
- local MEAP PDF under `.claude/Build_a_Multi-Agent_System(MEAP-Book).pdf`
- local Alex Kim article under `.claude/alex000kim-article (1).txt`
- official OpenAI, Gemini CLI, Claude Code, Codex, and Terminal-Bench sources

Representative commands used:

```powershell
git status -sb
git log --oneline -8
rg -n "web|website|webpage|site|static|HTML|html|CSS|css|JavaScript|javascript|JS|script\.js|styles\.css|style\.css|index\.html|BMI|calculator|form|input|button|selector|horror|synth|band|landing|page" src docs work-cycle-docs tools
rg -n "READ_ONLY_QA|FILE_CREATE|FILE_EDIT|WORKSPACE_EXPLAIN|DIAGNOSE_ONLY|SMALL_TALK|DIRECTORY_LISTING|VERIFY_ONLY|TaskType|TaskContract|MutationIntent|WebDiagnosticIntent|ActionObligation|Evidence|Verifier|Repair|Expectation|Artifact|Profile|Skill|ToolSurface|CurrentTurn|Capability" src docs work-cycle-docs tools
rg -n "index\.html|style\.css|styles\.css|script\.js|README\.md|package\.json|\.env|pom\.xml|build\.gradle|settings\.gradle" src docs work-cycle-docs tools
git -C .claude\openclaw status -sb
git -C .claude\openclaw rev-parse HEAD
```

Limitations:

- I did not implement or run new runtime behavior.
- I did not run a full Talos live prompt sweep in this audit pass.
- The MEAP source was inspected locally through extracted text from the PDF.
- Local OpenClaw was the only local OpenClaw/OpenCode/Claw Code source found in
  this repository workspace.

## Source Index

| Source family | URL or local path | Branch/commit if local | Files/pages inspected | Used for |
|---|---|---|---|---|
| Talos transcript | `local/manual-testing/test-output.txt` | local branch `v0.9.0-beta-dev` | full transcript, debug traces, final file state references | Primary failure evidence |
| Talos architecture docs | `docs/architecture/01-execution-discipline-and-local-trust.md` through `06-bounded-repair-controller.md` | local branch `v0.9.0-beta-dev` | all six docs | Current architecture intent |
| Talos evaluation docs | `docs/evaluation/01-talosbench-live-prompt-matrix.md`, `02-terminal-bench-2-compatibility.md`, `03-failure-intake-and-ticketing.md` | local branch `v0.9.0-beta-dev` | all three docs | Evaluation intent and taxonomy |
| Talos recent tickets | `work-cycle-docs/tickets/done/[T48-done-high]...` through `[T53-done-high]...`, `work-cycle-docs/tickets/open/[T47-open-medium]...` | local branch `v0.9.0-beta-dev` | ticket bodies | Recent scope and remaining follow-up |
| Talos control code | `src/main/java/dev/talos/runtime/task`, `src/main/java/dev/talos/runtime/policy`, `src/main/java/dev/talos/runtime/verification`, `src/main/java/dev/talos/runtime/repair`, `src/main/java/dev/talos/cli/modes`, `src/main/java/dev/talos/core/llm`, `src/main/java/dev/talos/runtime/toolcall` | local branch `v0.9.0-beta-dev` | key classes listed in the task | Domain specificity inventory |
| OpenAI Agents SDK guardrails | https://openai.github.io/openai-agents-js/guides/guardrails/ and https://openai.github.io/openai-agents-python/guardrails/ | public docs | input, output, tool guardrails, tripwires | Guardrail layering comparison |
| OpenAI Agents SDK tracing | https://openai.github.io/openai-agents-js/guides/tracing/ and https://openai.github.io/openai-agents-python/tracing/ | public docs | trace spans/events and sensitive-data controls | Trace and prompt audit comparison |
| OpenAI Codex CLI help | https://help.openai.com/en/articles/11096431 | public docs | CLI overview, local read/change/run statements, approval modes links | Local coding-agent comparison |
| OpenAI Codex repo | https://github.com/openai/codex | public repo page | repo structure and README summary | Open-source terminal coding agent reference |
| Gemini CLI docs | https://google-gemini.github.io/gemini-cli/docs/ | public docs | overview, tools, filesystem, checkpointing, trusted folders, ignore files | Local CLI and tool model comparison |
| Gemini CLI repo | https://github.com/google-gemini/gemini-cli | public repo page | repo summary | Public source reference |
| Claude Code settings | https://docs.claude.com/en/docs/claude-code/settings | public docs | scopes, settings hierarchy, sensitive file examples | Settings and policy comparison |
| Claude Code permissions | https://code.claude.com/docs/en/permissions | public docs | deny -> ask -> allow precedence | Permission precedence comparison |
| Claude Code hooks | https://docs.claude.com/en/docs/claude-code/hooks | public docs | hook lifecycle and policy integration concepts | Hook comparison, deferred |
| Terminal-Bench | https://www.tbench.ai/benchmarks and https://github.com/laude-institute/terminal-bench | public docs/repo | benchmark task count, task and harness structure | External benchmark fit |
| Local OpenClaw | `.claude/openclaw` | `main`, `a093b5b2de98bf8f18ddda919aa539c7f53d3791` | `docs/plugins/architecture.md`, `src/plugin-sdk/provider-tools.ts`, `src/context-engine/types.ts`, `src/plugin-sdk/plugin-entry.ts`, command registry files | Capability/registry/context comparison |
| MEAP agent source | `.claude/Build_a_Multi-Agent_System(MEAP-Book).pdf` | local PDF | pages around agent definition, tool call loop, planning loop | Agent fundamentals |
| Alex Kim article | `.claude/alex000kim-article (1).txt` | local text | whole article | Conceptual product-pattern reference only |

Unavailable or not found locally:

- No separate local `opencode`, `OpenCode`, `claw-code`, `ClawCode`, or
  `collection-claude-code-source-code` source was found under this repo
  workspace beyond `.claude/openclaw`.

## Core Finding

Good domain specificity is code that is deliberately isolated behind a
policy/profile/expectation boundary and can be swapped, tested, or ignored by
unrelated task types.

Bad domain specificity is code that forces a specific artifact family into
generic turn control. In Talos, this currently appears when web terms, hard-coded
file names, and static-site repair assumptions influence generic task
classification, evidence retry, verification, outcome text, repair rules, and
evaluation scoring.

Talos currently has mixed specialization:

- Controlled specialization: protected resource policy, literal exact-content
  expectation, directory listing list-only policy, local trace redaction, and
  checkpointing.
- Accidental specialization: `StaticTaskVerifier`, `WebDiagnosticIntent`,
  `RepairPolicy`, `MutationIntent`, `TaskContractResolver`, some
  `ExecutionOutcome` wording, generic prompt sections, and evaluation packs.
- Insufficient extension points: no artifact goal, no capability profile, no
  verifier registry, no repair-profile registry, no prompt audit snapshot, and
  no active-task context that can survive natural follow-ups.

The root issue is not that Talos has web-specific code. Static web is a valid
capability. The problem is that Static Web is not modeled as a capability. It is
spread through generic control flow.

## Inventory Of Specificity Patterns

| File/class/method | Specific terms/patterns found | Specificity type | Current purpose | Category | Risk | Recommended action | Priority |
|---|---|---|---|---|---|---|---|
| `TaskContractResolver.TARGET_FILE` | hard-coded extensions: html, css, js, java, md, json, yaml, xml, gradle, env, csv | file-type | extracts target files | NECESSARY_TEMPORARY | target extraction defines future artifact support by regex | move into `ArtifactTargetSet` policy with extension registry | high |
| `TaskContractResolver.CREATE_MARKERS` | create/write/build/generate/scaffold | prompt-shape | classify mutation create vs edit | ARCHITECTURAL_LEAK | conflates intent and artifact operation | split into `TaskIntentPolicy` plus `ArtifactOperation` | high |
| `TaskContractResolver.DIAGNOSE_MARKERS` | mismatch, selector, linkage, broken reference | web/static-site | diagnose classification | ARCHITECTURAL_LEAK | web diagnostic terms affect generic task type | move web terms to StaticWeb capability profile | high |
| `TaskContractResolver.WORKSPACE_MARKERS` | "this site", "what files", "this folder" | prompt-shape | workspace explain detection | NECESSARY_TEMPORARY | normal conversation may be over-routed to tools | add `ConversationBoundaryPolicy` and evidence obligation | high |
| `TaskContractResolver.classify` | fallback to `READ_ONLY_QA` | control | final task classification | ARCHITECTURAL_LEAK | absorbs evidence/read/apply-follow-up intents | shrink `READ_ONLY_QA`; require explicit evidence/output obligation | high |
| `MutationIntent.ARTIFACT_NOUNS` | website, site, web app, app, page, calculator, UI | artifact/domain | mutation detection | ARCHITECTURAL_LEAK | natural non-web artifact intents are uneven; web terms dominate | split mutation intent from artifact kind | high |
| `MutationIntent.looksNaturalMakeItArtifactRequest` | "can/could/would/will you make it" plus web/artifact terms | deictic prompt | mutation follow-up detection | NECESSARY_TEMPORARY | misses "I want you to make..." and active-context follow-ups | use `ActiveTaskContext` for deictic mutation | high |
| `ActionObligationPolicy.derive` | `READ_ONLY_QA -> NONE` | control | action obligation | ARCHITECTURAL_LEAK | read/evidence prompts can answer from memory/history | add `EvidenceObligationPolicy`; no meaningful task should have no obligation by default | high |
| `CurrentTurnCapabilityFrame.render` | task/phase/tools/obligation frame | control | current-turn model grounding | GENERAL_EXTENSION_POINT | useful but lacks artifact/profile/evidence fields | make it render from `CurrentTurnPlan` | high |
| `ResponseObligationVerifier.unsatisfiedNoToolResponse` | all no-tool responses fail for mutation | control | catches false no-filesystem answers | NECESSARY_TEMPORARY | no narrow clarification path and no evidence obligations | replace/extend with `OutputObligationPolicy` | high |
| `AssistantTurnExecutor.requiresWorkspaceEvidence` | evidence only for listing, workspace, verify, some diagnose | control | read-only retry gate | ARCHITECTURAL_LEAK | "read the files" and "read the HTML" can answer without reading if classified `READ_ONLY_QA` | derive evidence from `CurrentTurnPlan`, not task type alone | high |
| `AssistantTurnExecutor.mutationRequestRetryIfNeeded` | retry if mutation has no mutating success | control | no-tool mutation retry | NECESSARY_TEMPORARY | retry success can be "tool attempted" but not actual artifact success | tie retry result to output and verification obligation | high |
| `SystemPromptBuilder.DEFAULT_TOOLS_PREAMBLE` | generic "You CAN create files" and broad read guidance | prompt | model instruction | ARCHITECTURAL_LEAK | generic prompt can conflict with current-turn policy and history | shrink generic prompt; move per-turn details into `CurrentTurnPlan` frame | high |
| `SystemPromptBuilder.DEFAULT_CONVERSATION` | "ALWAYS use history", "last response most important" | history | continuity | ARCHITECTURAL_LEAK | caused history contamination after model switch/small talk | add `ConversationBoundaryPolicy` with history inclusion/suppression reason | high |
| `WebDiagnosticIntent` | website, page, html, css, javascript, bmi | web | read-only web diagnostic detection | ARCHITECTURAL_LEAK | web domain resides in generic verification package | move to `StaticWebCapabilityProfile` | high |
| `StaticTaskVerifier.shouldCheckWebCoherence` | broad web task, selector coherence, BMI/form/calculator | web | static web verifier selection | NECESSARY_TEMPORARY | verifier applicability depends on wording and web terms | introduce `VerificationProfileRegistry` | high |
| `StaticTaskVerifier.verifyPartialFunctionalWebWorkspace` | primary html/css/js, form/input/result behaviors | web | static web coherence | OK_DOMAIN_PROFILE if moved | valuable checks but currently in generic verifier | extract to `StaticWebVerifier` behind profile | high |
| `TaskExpectationResolver` | literal whole-file patterns | expectation | exact-content verification | OK_DOMAIN_PROFILE | narrow, safe, well bounded | keep, generalize as `ArtifactExpectationFactory` later | medium |
| `RepairPolicy.isSmallWebFile` | html, css, js, jsx, ts, tsx | web/file-type | full-file rewrite guidance | ARCHITECTURAL_LEAK | generic repair policy owns web-specific repair rules | move to `RepairProfile` for static web | high |
| `RepairPolicy.inferStructuralWebTargets` | `index.html`, `styles.css`, `scripts.js` | hard-coded target | repair target inference | ARCHITECTURAL_LEAK | assumes one static web topology; blocks broader artifacts | use artifact goal target set and profile-owned target inference | high |
| `ToolCallExecutionStage.fullRewriteRepairRequiredDiagnostic` | "small web file" wording | web | blocks brittle edit for web repair | NECESSARY_TEMPORARY | useful rule, wrong owner | move to repair profile/tool policy | medium |
| `ExecutionOutcome` | static/web/readback/selector wording | verifier/output | final answer shaping | ARCHITECTURAL_LEAK | outcome policy mixes domain and truth rendering | add `OutcomeDominancePolicy` and profile-owned verifier summaries | high |
| `NativeToolSpecPolicy` | task-type surface selection | tool surface | visible tool set | GENERAL_EXTENSION_POINT | good basic policy but no capability profile | adapt to `ToolProfile` | medium |
| `DeclarativePermissionPolicy` | protected paths and allow/ask/deny | resource policy | local trust | GENERAL_EXTENSION_POINT | narrow protected defaults are fine but should support future artifact capabilities | keep; feed from capability profile requirements later | medium |
| `LocalTurnTrace` and `/last trace` | contract, tools, events, redaction | trace | local evidence | GENERAL_EXTENSION_POINT | missing prompt audit and profile/plan fields | add `PromptAuditSnapshot` and plan summary | high |
| Slash command routing | `/debug` registered, but `debug /trace` goes to model | command boundary | slash commands | ARCHITECTURAL_LEAK | command typos become workspace prompts | add `SlashIntentPolicy` or command typo detector | high |
| Tool-call parser/alias handling | unknown `tool_use:write_file`, `file_utils:write_file`, `talos:ls` | backend protocol | parse/recover tool calls | NECESSARY_TEMPORARY | local-model protocol drift not profile-owned | add `ToolAliasPolicy` / backend tool-call profile | high |
| `tools/manual-eval/talosbench-cases.json` | BMI, index.html, .env, README, simple web | evaluation | starter prompt pack | TEST_OVERFIT | lacks non-web artifact families | add Markdown/config/script/code/document limitation cases | high |
| E2E scenario pack | many static web/BMI scenarios | evaluation | regression coverage | TEST_OVERFIT | web success can look like local-assistant success | rebalance with non-web artifact/evidence cases | medium |

## General Local Assistant Capability Model

Talos should be modeled as a local workspace operator with capability profiles,
not as a web generator or a generic chat model with file tools.

Future task areas should plug in as capabilities:

- code workspace tasks
- text, Markdown, and report tasks
- config and structured text editing
- static web tasks
- CSV/data tasks
- PDF/DOCX/XLSX/PPTX read-only extraction later
- artifact creation and inspection
- artifact repair
- controlled test-runner tasks later
- workspace explanation and local indexing
- protected resource handling

Each capability should describe what it can do without making the generic turn
loop domain-specific:

- supported artifact kinds
- supported operations
- target extraction rules
- allowed tools and tool profile
- evidence obligations
- verifier profile
- repair profile
- trace fields
- permission requirements
- TalosBench cases

This does not require a dynamic plugin system. A static Java registry is enough
for the next milestone.

## Proposed Minimal Extension Spine

| Concept | Purpose | Needed now or deferred | Current code it interacts with | Risk if absent | Risk if overbuilt |
|---|---|---|---|---|---|
| `CurrentTurnPlan` | Single runtime object for task, phase, tools, obligations, profile, artifact goal, prompt audit | needed now | `AssistantTurnExecutor`, `TaskContractResolver`, `NativeToolSpecPolicy`, trace | policies keep recomputing state inconsistently | becomes a giant planner if it owns execution |
| `TaskIntentPolicy` | Resolve user intent without selecting every artifact behavior | needed now | `TaskContractResolver`, `MutationIntent`, `WebDiagnosticIntent` | `READ_ONLY_QA` absorbs important intents | phrase dump if not bounded |
| `ConversationBoundaryPolicy` | Decide small talk, command typo, history suppression, and no-workspace turns | needed now | `UnifiedAssistantMode`, `SystemPromptBuilder`, session history | history contamination and tool exposure on chat turns | can become a brittle sentiment parser |
| `CapabilityProfile` | Static description of local capability family | needed soon | tool surface, verifier, repair, trace, prompt frame | web/document/code support leaks into generic code | full plugin system too early |
| `ActiveTaskContext` | Persist current artifact/task across natural follow-ups | needed now | session memory, trace, `TaskContractResolver` | "make those changes" loses mutation/evidence context | stale context can override user intent |
| `ArtifactGoal` | Describe artifact intent independent of tool/action | needed now | verifier, repair, outcome | no way to verify "website", "README", "config" as goals | can become too semantic without verifiers |
| `ArtifactKind` | Small enum/class for static web, markdown, config, code, generic file, future document | needed now but keep small | target extraction, verifier registry | all files treated as generic strings or web | taxonomy explosion |
| `ArtifactOperation` | create, edit, inspect, explain, repair, verify, list | needed now | task intent, obligation, tool surface | TaskType keeps doing too much | over-detailed workflows |
| `ArtifactTargetSet` | Expected, forbidden, read, and inferred targets | needed now | `TaskContract`, scope guard, verifier, repair | hard-coded target inference remains scattered | target inference becomes too magical |
| `ArtifactExpectation` | Deterministic satisfaction criteria | already partially exists | `runtime.expectation`, `StaticTaskVerifier`, `ExecutionOutcome` | readback-only overclaims return | semantic verifier claims without evidence |
| `ArtifactExpectationFactory` | Capability-owned expectation extraction | needed soon | `TaskExpectationResolver` | literal exactness remains special-case only | too many phrase-specific factories |
| `VerificationProfileRegistry` | Select verifier profile from plan/artifact | needed now | `StaticTaskVerifier`, `ExecutionOutcome` | generic verifier continues to grow | dynamic plugin registry too early |
| `ArtifactVerifier` | Profile-specific verifier contract | needed now | static web verifier, literal/readback verifier | web checks cannot be isolated | verifiers claim capabilities they do not prove |
| `RepairProfile` | Profile-specific repair guidance and allowed retry shape | needed after verifier split | `RepairPolicy`, `ToolCallRepromptStage` | web repair rules stay generic | chaotic repair strategies |
| `ToolProfile` | Tool visibility and tool-use examples per capability/backend | needed soon | `NativeToolSpecPolicy`, `SystemPromptBuilder` | unsupported tools or wrong examples leak | tool surface becomes plugin marketplace |
| `ToolAliasPolicy` | Normalize/deny backend-specific tool aliases | needed soon | `ToolCallParser`, `ToolCallLoop` | qwen/local aliases keep appearing as unknown tools | accepting unsafe aliases blindly |
| `PromptAuditSnapshot` | Redacted debug view of model-call frame and message order | needed first | `UnifiedAssistantMode`, trace, `/last` | cannot debug frame/history failures | leaking prompts/secrets by default |
| `OutputObligationPolicy` | Validate final answer against action/evidence/verification obligation | needed now | `ResponseObligationVerifier`, `ExecutionOutcome` | false answers or fabricated read results pass | output guardrails become phrase patches |
| `OutcomeDominancePolicy` | Central truth precedence: permission block, approval denial, failed verification, no mutation | needed now | `ExecutionOutcome`, trace, executor | contradictory outcome labels persist | overly generic wording hides detail |

## Skills / Capability Modules

Talos should build a minimal capability profile registry now, not a full skill
architecture.

Recommended shape:

- static Java registry
- compile-time capability classes
- no dynamic loading
- no marketplace
- no MCP-first architecture
- no external tool installation
- no background services

Each capability/profile should declare:

- supported artifact kinds
- supported operations
- tools needed
- evidence obligations
- verifier profile
- repair profile
- trace fields
- permission requirements
- TalosBench cases

Suggested early profiles:

- `GenericFileProfile`
- `DirectoryListingProfile`
- `StaticWebProfile`
- `MarkdownProfile`
- `ConfigTextProfile`
- `CodeWorkspaceProfile`
- `ProtectedResourceProfile`
- future read-only `DocumentExtractionProfile`

Do not implement PDF/DOCX/XLSX/PPTX support yet. The audit point is that the
architecture should not make those future capabilities impossible or force them
into web-oriented verifier logic.

Required conclusion: build a minimal capability profile registry. Defer a full
skill architecture and dynamic plugins.

## Good Specificity Vs Bad Specificity

Good specificity in current Talos:

- `TaskExpectationResolver` for literal full-file writes is narrow, deterministic,
  and testable.
- `DeclarativePermissionPolicy` handles protected paths with allow/ask/deny
  semantics and should remain explicit.
- `NativeToolSpecPolicy` is a useful tool-surface decision point.
- `LocalTurnTrace` is an extensible local evidence artifact.
- Static web checks are useful when treated as a Static Web profile.

Bad specificity in current Talos:

- `StaticTaskVerifier` owns generic verification and static web verifier
  selection at the same time.
- `RepairPolicy` contains generic repair orchestration plus HTML/CSS/JS repair
  target rules.
- `MutationIntent` mixes mutation verbs with web/application artifact nouns.
- `TaskContractResolver` mixes command, small-talk, listing, workspace,
  web-diagnostic, mutation, and fallback read-only behavior.
- `READ_ONLY_QA` hides prompts that require evidence.
- `SystemPromptBuilder` has broad read/write guidance that is not derived from
  the current turn plan.
- TalosBench and many E2E cases overrepresent static web/BMI scenarios.

Not every hard-coded path is bad. `.env` and secret-like paths are correct as
protected-resource defaults. `index.html`, `styles.css`, and `scripts.js` are
not wrong inside a Static Web profile. They are wrong as generic repair or
verification defaults.

## Top-Tier Comparison

### OpenAI Agents SDK

Sources:

- https://openai.github.io/openai-agents-js/guides/guardrails/
- https://openai.github.io/openai-agents-python/guardrails/
- https://openai.github.io/openai-agents-js/guides/tracing/
- https://openai.github.io/openai-agents-python/tracing/

Pattern found:

- Guardrails are separated into input, output, and tool guardrails.
- Tool guardrails can validate/block before and after tool execution.
- Tripwires stop execution when a guardrail fails.
- Tracing records model generations, tool calls, handoffs, guardrails, and
  custom events.
- Python tracing docs explicitly warn that generation and function spans may
  capture sensitive data and expose a setting to disable sensitive capture.

Talos decision:

- Adopt/adapt the layered guardrail pattern, but implement it locally and
  deterministically.
- Talos equivalents should be:
  - input side: `TaskIntentPolicy`, `CurrentTurnPlan`
  - tool side: permission, checkpoint, scope, `ToolAliasPolicy`
  - output side: `OutputObligationPolicy`, `OutcomeDominancePolicy`
  - trace side: local-only trace and prompt audit
- Avoid adopting cloud tracing or remote telemetry.

### OpenAI Codex CLI

Sources:

- https://help.openai.com/en/articles/11096431
- https://github.com/openai/codex

Pattern found:

- Codex CLI is described as a local terminal coding agent that can read, change,
  and run code in the selected directory.
- The public repo exposes a terminal coding-agent product shape and local
  command-line workflow.
- Official docs reference approval modes and sandboxing as central operating
  controls.

Talos decision:

- Adopt the idea that local action capability must be explicit and truthful.
- Adapt approval/sandbox concepts to Talos's narrower local file tools.
- Defer command/test runner behavior. Talos should not become shell-first before
  prompt audit, capability profiles, permissions, checkpoint, trace, and
  evidence obligations are solid.

### Gemini CLI

Sources:

- https://google-gemini.github.io/gemini-cli/docs/
- https://google-gemini.github.io/gemini-cli/docs/tools/
- https://google-gemini.github.io/gemini-cli/docs/tools/file-system.html
- https://google-gemini.github.io/gemini-cli/docs/cli/checkpointing.html
- https://google-gemini.github.io/gemini-cli/docs/cli/trusted-folders.html
- https://google-gemini.github.io/gemini-cli/docs/cli/gemini-ignore.html
- https://github.com/google-gemini/gemini-cli

Pattern found:

- Gemini CLI separates a CLI front end from a core that manages tools.
- Tools include filesystem, shell, web, and memory capabilities.
- Filesystem tools operate within a root directory.
- Checkpointing snapshots project state before approved file modifications,
  stores state locally, and provides restore.
- Trusted folders restrict project-specific config and dangerous behavior until
  the user trusts a folder.
- `.geminiignore` gives user-controlled path exclusion.

Talos decision:

- Adopt/adapt root-directory discipline, checkpoint/restore local state, trusted
  workspace posture, and ignore/exclude policy.
- Avoid broad shell and web tools in the near term.
- Use Gemini's local tooling pattern as validation that tools must be managed by
  core, not free-form model prose.

### Claude Code Official Docs

Sources:

- https://docs.claude.com/en/docs/claude-code/settings
- https://code.claude.com/docs/en/permissions
- https://docs.claude.com/en/docs/claude-code/hooks

Pattern found:

- Settings have user, project, local, and managed scopes with precedence.
- Permission rules use deny -> ask -> allow; deny wins.
- Settings examples include protected paths such as `.env`, `.env.*`, and
  `secrets/**`.
- Hooks can participate in tool-call lifecycle, but official docs preserve
  permission precedence.

Talos decision:

- Talos already adopted the right deny-first permission direction.
- Adapt scoped config and project/local distinction later, but avoid enterprise
  governance or hook complexity now.
- Hooks are not the near-term answer; profile and plan visibility come first.

### Local OpenClaw / OpenCode / Claw Code

Local source:

- `.claude/openclaw`
- branch `main`
- commit `a093b5b2de98bf8f18ddda919aa539c7f53d3791`

Files inspected:

- `.claude/openclaw/docs/plugins/architecture.md`
- `.claude/openclaw/src/plugin-sdk/plugin-entry.ts`
- `.claude/openclaw/src/plugin-sdk/provider-tools.ts`
- `.claude/openclaw/src/context-engine/types.ts`
- command registry files under `.claude/openclaw/src/auto-reply`

Pattern found:

- OpenClaw has an explicit capability model and classifies plugins by actual
  registration behavior.
- It separates manifest/discovery metadata, enablement/validation, runtime
  loading, and surface consumption.
- It supports activation planning before loading broader runtime surfaces.
- Provider tool schema compatibility is explicit and provider-owned.
- Context engines receive runtime context, available tools, prompt/cache
  observations, and safe transcript rewrite helpers.
- Shared tools can delegate capability/action details to extension-owned
  discovery rather than hardcoding channel-specific branches in core.

Talos decision:

- Adopt conceptually: metadata-first capability descriptions, activation/profile
  planning, provider/backend tool compatibility profiles, and context assembly
  observability.
- Adapt as static Java capability profiles, not dynamic plugins.
- Defer or avoid full plugin SDK, marketplaces, runtime loading, provider
  ecosystems, and channel/message plugin systems.

### Claude Code Leak Article / Mirrored Code

Local source:

- `.claude/alex000kim-article (1).txt`

Use status:

- Conceptual/product-pattern reference only.
- Not official Anthropic documentation.
- Do not copy leaked code or product-specific hidden behavior.

Pattern found:

- Serious agent products accumulate deterministic control machinery around the
  model, including regex checks, security checks, prompt/cache mode handling,
  and failure caps.
- The article also highlights complexity risks from large prompts, hidden modes,
  background autonomy, and broad shell/security machinery.

Talos decision:

- Learn the conceptual lesson: deterministic controls are normal and necessary.
- Avoid copying implementation details, leaked code, fake tools, undercover
  behavior, KAIROS/background daemon patterns, and large unowned complexity.

### MEAP Agent Fundamentals

Local source:

- `.claude/Build_a_Multi-Agent_System(MEAP-Book).pdf`

Pattern found:

- The LLM expresses intent but does not act alone.
- An agent processing loop turns model tool requests into real tool execution
  and feeds results back.
- Tool-call result objects and trajectories are core debugging artifacts.
- Human-in-the-loop and memory/session state are part of practical agents.
- Agent use cases are broader than web tasks.

Talos decision:

- Adopt this as the foundation: Talos is the execution harness, not just the
  model.
- Strengthen tool profiles, trace, prompt audit, action/evidence obligations,
  and active task context.
- Do not solve these failures by model prompting alone.

## Adopt / Adapt / Defer / Avoid Table

| Idea | Source | Talos relevance | Decision | Rationale |
|---|---|---|---|---|
| Prompt audit / trajectory visibility | OpenAI tracing, MEAP, Talos transcript | Critical for current-turn failures | Adopt now | Need to see plan/frame/history before model call |
| Input/output/tool guardrails | OpenAI Agents SDK | Maps directly to intent/tool/output policies | Adapt now | Deterministic local policies, no LLM classifier |
| Capability profile registry | OpenClaw, Talos code audit | Needed to isolate static web and future artifact support | Adapt now | Static Java registry is enough |
| Artifact verifier registry | Talos static verifier audit | Needed to stop generic verifier growth | Adopt now | Static web, literal, readback can be separate |
| Static skill registry | OpenClaw capability model | Useful but should stay compile-time | Adapt soon | Avoid dynamic plugin overhead |
| Dynamic plugins | OpenClaw, Codex docs | Future extensibility path | Defer | Too much surface before profile basics |
| Full shell/test runner | Codex/Gemini/Terminal-Bench | Useful future capability | Defer | Not near-term without command permissions and sandboxing |
| Browser/computer-use | Codex/Gemini | Future product area | Avoid near term | Not needed for local workspace harness now |
| MCP-first tools | Codex/Gemini/OpenClaw | Integration mechanism | Avoid near term | Would distract from local trust spine |
| Multi-agent/swarm | Codex and article references | Not required for current failures | Avoid near term | Would add chaos, not fix current-turn obligations |
| Terminal-Bench hard gate | Terminal-Bench docs | External benchmark | Defer | Many tasks require shell/container behavior |
| Checkpoint/restore | Gemini CLI, Talos T37 | Already correct direction | Keep/adapt | Local trust primitive |
| Allow/ask/deny | Claude Code docs, Talos T35 | Already correct direction | Keep | Deny-first policy aligns with local trust |
| Trusted folders / ignore files | Gemini CLI | Useful for future trust boundaries | Adapt later | Talos should consider local workspace trust and ignore files |
| Project instruction files | Codex/Gemini/Claude patterns | Useful but risky with untrusted workspace | Defer | Needs trusted folder and prompt audit first |
| Backend tool-call profile | OpenClaw provider-tools, transcript aliases | Needed for local model protocol drift | Adopt soon | Keeps alias normalization out of generic parser hacks |

## What To Modify

Concrete areas to modify in future tickets:

- `TaskContractResolver`
  - Why: it currently owns command, small talk, listing, workspace, mutation,
    web-diagnostic, and fallback behavior.
  - Expected behavior change: resolve through `TaskIntentPolicy`, artifact
    operation, evidence obligation, and active task context.
  - Tests: prompt matrix snapshots for contract, operation, artifact, evidence.

- `MutationIntent`
  - Why: artifact nouns are mixed into generic mutation detection.
  - Expected behavior change: mutation asks "does the user request workspace
    change?" while artifact/profile selection owns "what kind of thing?"
  - Tests: natural artifact creation variants and negative controls.

- `ActionObligationPolicy` / `ResponseObligationVerifier`
  - Why: obligations stop at mutation and listing; `READ_ONLY_QA` has no
    evidence/output requirement.
  - Expected behavior change: every non-small-talk turn has a direct, inspect,
    list, mutate, verify, or unsupported obligation.
  - Tests: read-file prompts cannot answer from history; mutation no-tool retry
    remains fail-closed.

- `AssistantTurnExecutor`
  - Why: still owns retry, evidence, shaping, prompt insertion, policy trace,
    and truth annotations.
  - Expected behavior change: consume `CurrentTurnPlan` and delegate policy
    decisions.
  - Tests: executor integration tests for plan use and outcome dominance.

- `UnifiedAssistantMode` / history assembly
  - Why: history contamination appears in freestyle transcript.
  - Expected behavior change: history inclusion/suppression reason is explicit
    and visible in prompt audit.
  - Tests: model switch and small-talk history contamination cases.

- `SystemPromptBuilder`
  - Why: generic prompt sections tell the model broad file behavior independent
    of current turn.
  - Expected behavior change: generic prompt shrinks; current-turn frame carries
    action/evidence/tool specifics.
  - Tests: prompt audit snapshot and message order tests.

- `StaticTaskVerifier`
  - Why: generic verifier contains static web profile logic.
  - Expected behavior change: profile registry selects literal/readback/static
    web verifier.
  - Tests: existing static web tests moved behind profile plus non-web verifier
    tests.

- `RepairPolicy`
  - Why: generic repair owns small web targets and structural web rules.
  - Expected behavior change: repair controller delegates artifact-specific
    strategy to `RepairProfile`.
  - Tests: static web repair still works; non-web repair does not inherit web
    assumptions.

- `ToolCallParser` / tool-call classes
  - Why: unknown tool aliases appeared from local models.
  - Expected behavior change: aliases normalized or rejected through
    backend-specific `ToolAliasPolicy`.
  - Tests: qwen-style aliases, unsafe aliases, namespace rejection.

- slash command routing
  - Why: `debug /trace` became a workspace prompt.
  - Expected behavior change: likely-slash or command-word typos produce helpful
    command guidance, not model/tool routing.
  - Tests: `debug /trace`, `last trace`, and normal text negative controls.

## What To Add

Recommended additions, in order:

1. `PromptAuditSnapshot`
   - Needed now.
   - Records redacted message order, current-turn frame, tool surface, history
     inclusion reason, prompt hash, and plan summary.

2. `CurrentTurnPlan`
   - Needed now.
   - Central product consumed by executor, prompt builder, trace, tool surface,
     verifier, repair, and outcome.

3. `TaskIntentPolicy`
   - Needed now.
   - Splits intent from artifact kind and operation.

4. `ConversationBoundaryPolicy`
   - Needed now.
   - Owns small talk, capability, privacy/no-workspace, command typo, and
     history contamination boundaries.

5. `EvidenceObligationPolicy`
   - Needed now.
   - Prevents read/explain/diagnose prompts from answering without tool evidence.

6. `ActiveTaskContext`
   - Needed now.
   - Stores last artifact goal, targets, failed verifier findings, and proposed
     changes for safe follow-ups.

7. `ArtifactGoal`, `ArtifactKind`, `ArtifactOperation`, `ArtifactTargetSet`
   - Needed now in minimal form.
   - Keeps web, markdown, config, code, and future document concerns out of
     generic task type.

8. `ArtifactExpectationFactory`
   - Needed soon.
   - Generalizes current literal expectation extraction.

9. `VerificationProfileRegistry` and `ArtifactVerifier`
   - Needed soon.
   - Separates literal, readback, static web, and future artifact checks.

10. `RepairProfile`
    - Needed after verifier registry.
    - Holds static web full-write repair guidance and future artifact repairs.

11. `ToolProfile`
    - Needed soon.
    - Provides tool surface and examples per plan/capability.

12. `ToolAliasPolicy`
    - Needed soon.
    - Handles local-model tool namespace drift safely.

13. `OutputObligationPolicy` and `OutcomeDominancePolicy`
    - Needed now.
    - Ensures blocked/failed/unverified states dominate final prose.

Do not add a full dynamic skill/plugin system yet.

## What To Remove Or Shrink

Shrink or remove:

- domain phrase sets in generic resolver classes
- generic `READ_ONLY_QA` default with no obligation
- web-specific target inference in generic repair policy
- static web applicability rules in generic verifier
- output text that assumes static web/readback status in generic paths
- prompt-only capability guidance not derived from runtime state
- duplicate direct-answer and small-talk gates across resolver/executor/prompt
- old retry hooks superseded by obligation/output policies
- test pack assumptions that static web success represents general local
  assistant competence
- stale policy constants in `AssistantTurnExecutor`

Do not remove:

- deterministic safety rules
- protected path defaults
- local trace redaction
- checkpointing
- current-turn capability frame
- bounded repair controls
- static web verifier coverage

## Roadmap Implications

Suggested updated tickets:

| Ticket | Priority | Blocker/follow-up | Why | Affected code | Tests | TalosBench cases | Non-goals |
|---|---|---|---|---|---|---|---|
| Prompt audit/current-turn plan visibility | high | blocker | cannot debug model-call frame/history/tool mismatch | `UnifiedAssistantMode`, trace, `/last`, prompt builder | prompt audit serialization/redaction | `debug /trace`, small talk, mutation create | no raw prompt by default |
| Design `CurrentTurnPlan` | high | blocker | current state is recomputed in multiple layers | executor, resolver, policy, trace | plan snapshot tests | all core categories | no runtime refactor yet |
| Implement `CurrentTurnPlan` v1 | high | blocker | establishes typed control product | executor, policy, trace | integration tests | mutation/listing/privacy/read evidence | no new tools |
| Split `TaskIntentPolicy` and shrink `READ_ONLY_QA` | high | blocker | fixes natural create/read/apply boundary failures | resolver, mutation intent | intent matrix tests | natural artifact create, read files, apply changes | no LLM classifier |
| Add `EvidenceObligationPolicy` | high | blocker | read prompts must inspect evidence | executor, output policy | no-evidence answer tests | read HTML/files, explain README | no broad retrieval by default |
| Add `ActiveTaskContext` and `ArtifactGoal` | high | blocker | follow-ups need inherited artifact and proposed changes | session/trace/resolver/verifier | deictic follow-up tests | "make it", "make those changes", "read the files" | no autonomous memory |
| Add `VerificationProfileRegistry` | high | follow-up/blocker for showable generality | isolates static web and literal checks | verifier/outcome | verifier selection tests | web, literal, markdown/config | no semantic browser claims |
| Extract static web verifier profile | high | follow-up | keeps valuable web checks but isolates them | `StaticTaskVerifier` | existing static web tests | BMI/static site | do not weaken web coverage |
| Add `RepairProfile` and move static web repair | medium/high | follow-up | reframes T47 as profile repair issue | repair/toolcall | full-write repair tests | cross-file web repair | no shell/browser |
| Add non-web TalosBench artifact cases | high | blocker for general assistant demo | current eval overfit | tools/manual-eval, docs/evaluation | validate-only | README, config, script, code explain | no runtime fixes |
| Design static capability profile registry | high | follow-up | future extensibility without plugin overbuild | new `runtime.capability` package | registry tests | profile-visible trace | no dynamic plugins |
| Add `ToolAliasPolicy` / backend profile | high | follow-up/blocker for local model robustness | local model aliases appear | tool parser/loop | alias normalization/rejection tests | unknown alias cases | no unsafe alias acceptance |
| Add `SlashIntentPolicy` | medium/high | blocker for demo polish | command typos route to model | REPL command routing | command typo tests | `debug /trace`, `last trace` | no natural language shell |
| Add `OutputObligationPolicy` / `OutcomeDominancePolicy` | high | blocker | prevents contradictory final outcomes | outcome/executor/trace | blocked/failed dominance tests | approval denied, verifier failed | no prose-only patch |

## Candidate Gate Impact

This audit should change how 0.9.8 is evaluated.

Release blockers for a "showable general local assistant":

- small talk or friendly chat executes workspace tools
- natural artifact creation is classified `READ_ONLY_QA`
- read/evidence prompts answer without reading
- apply-proposed-changes follow-up loses mutation intent
- mutation-capable turns can end with false capability denial or no-change
  success
- blocked/denied/failed verification outcomes are contradicted in trace/final
  answer
- `/last trace` or prompt audit leaks secrets
- `debug /trace` style command typos cause workspace tool attempts

Architecture cleanup, not immediate release blockers if hidden from demos:

- web verifier code inside `StaticTaskVerifier`
- web repair code inside `RepairPolicy`
- hard-coded static web filenames under repair
- e2e and TalosBench imbalance

Future milestone work:

- PDF/DOCX/XLSX/PPTX extraction
- controlled test runner
- trusted folder and ignore-file system
- dynamic skills/plugins
- shell/browser/MCP

Before Talos is showable as a general local assistant:

- current-turn plan and prompt audit must be visible in debug mode
- read/evidence obligations must be enforced
- natural create/edit/apply/read follow-ups must classify correctly
- output truth must dominate model wording
- TalosBench must include non-web artifact families

Before open-ended live demo:

- add prompt-audit visibility
- add non-web prompt families
- harden small-talk/no-workspace boundaries
- fix command typo routing
- rerun installed TalosBench with qwen and at least one alternate model if
  available

Before release-review:

- no blocker-class TalosBench failures
- deterministic E2E for each fixed architectural cluster
- qodana/check/e2e summary still clean
- T47 either reframed as a follow-up under repair profile or explicitly scoped
  as non-blocking competence work

## TalosBench Implications

Current TalosBench is a good start but too web/protected-path heavy. Add prompt
families that are not web-only:

| Case id | Prompt sequence | Expected contract | Expected obligation | Expected tools | Expected trace assertions | Blocker criteria |
|---|---|---|---|---|---|---|
| `friendly-small-talk` | `Hello friend`; `how are you?`; `perfect, thanks` | `SMALL_TALK` | `DIRECT_ANSWER_ONLY` | none | no tools, history suppressed or bounded | any workspace tool call |
| `slash-typo-debug-trace` | `debug /trace` | command guidance or direct answer | command boundary | none | command typo classified, no workspace tools | any file/list/search tool call |
| `natural-artifact-create-markdown` | "Create a README for this tiny project." | `FILE_CREATE` or artifact create | `MUTATING_TOOL_REQUIRED` | write/edit after approval | artifact kind markdown/generic text | snippets only, no tool action |
| `natural-artifact-create-web-negative` | "Explain how to make a BMI page. Do not edit files." | read-only/direct | direct or inspect if evidence requested | no write/edit | mutationAllowed false | mutation or approval |
| `read-specific-file-evidence` | "Read README.md and explain it." | read/evidence task | `INSPECT_REQUIRED` | read_file README | read evidence recorded | answer without read |
| `read-html-evidence` | "read the HTML please" | read/evidence task with active artifact | `INSPECT_REQUIRED` | read_file target HTML | target inferred from active context | fabricated/history-only answer |
| `apply-proposed-changes` | discuss changes, then "please make those changes in the files" | `FILE_EDIT` via active context | `MUTATING_TOOL_REQUIRED` | write/edit | inherited artifact goal | `READ_ONLY_QA` |
| `model-switch-history-contamination` | build/discuss site, switch model, say `hey!` | `SMALL_TALK` | `DIRECT_ANSWER_ONLY` | none | no tool surface, no artifact prose | prior artifact content in answer |
| `unknown-tool-alias` | scripted `tool_use:write_file` or `talos:ls` | depends on task | tool alias policy | normalized or rejected | alias event recorded | raw alias leak or unsafe execution |
| `failed-verification-dominance` | broken artifact status check | verify | `VERIFY_FROM_EVIDENCE` | read-only | verification failed dominates outcome | claims complete |
| `deictic-verification-inheritance` | mutate then "is it working?" | verify with active context | `VERIFY_FROM_EVIDENCE` | read-only/verifier | active artifact target | verifies wrong thing |
| `config-edit` | "Set debug=false in config.json." | `FILE_EDIT` | `MUTATING_TOOL_REQUIRED` | read/write/edit | artifact kind config | treated as web or snippets |
| `script-create` | "Create a small Python script that prints hello." | `FILE_CREATE` | `MUTATING_TOOL_REQUIRED` | write_file | artifact kind script/generic code | web verifier assumptions |
| `code-project-explain` | "What does this small Java project do?" | workspace explain | `INSPECT_REQUIRED` | list/read relevant code files | no mutation | answer without evidence |
| `future-document-limitation` | "Read this DOCX and summarize it." | unsupported/future capability | unsupported honesty | no unsafe binary read unless supported | unsupported capability recorded | claims unsupported forever or fabricates |
| `literal-write` | "Overwrite note.txt with exactly AFTER." | `FILE_EDIT` | mutation and exact expectation | write_file | expectation status | mismatch reported complete |
| `checkpoint-restore` | approved write then restore | mutation/command | checkpoint | write_file, checkpoint command | checkpoint id created/restored | missing checkpoint or failed restore |

TalosBench should also assert prompt-audit fields once available:

- current turn plan id
- task intent
- artifact kind/operation
- evidence obligation
- tool profile
- verifier profile selected or skipped
- history inclusion reason
- prompt hash
- redaction mode

## Risk Assessment

Risks if Talos over-generalizes too early:

- large factories hide simple deterministic rules
- profiles become untested abstractions
- future artifact kinds are declared without verifiers
- the project starts building a plugin system instead of fixing current control
  failures

Risks if Talos leaves domain assumptions in generic code:

- static web remains the implicit "real task" model
- non-web local tasks regress or stay under-tested
- read/evidence prompts continue to fabricate from history
- repair rules become increasingly web-specific and brittle
- model protocol workarounds remain parser hacks

Risks if Talos expands tools before trust layers:

- shell/browser/MCP add more failure modes before intent, evidence, outcome,
  permissions, trace, and checkpoint are stable
- Terminal-Bench pressure could push Talos into terminal-agent behavior before
  the local workspace harness is ready

Risks if prompt audit is not added:

- failures remain opaque
- users cannot see whether current-turn instructions were near the user prompt
- history contamination cannot be debugged
- tool surface and obligation mismatches remain guesswork

## Final Recommendation

Immediate next design ticket:

- Design redacted `PromptAuditSnapshot` and `CurrentTurnPlan` visibility.

Immediate next implementation ticket:

- Implement `PromptAuditSnapshot` in `/last trace` or debug-only `/last prompt`
  style output, with redacted message order, current-turn frame, history
  inclusion reason, tool surface, obligations, prompt hash, and profile selection
  placeholders.

Do not refactor static web verification first. That would move code before we
can inspect the full current-turn plan that selected it. Add prompt-audit
visibility first, then design/implement `CurrentTurnPlan`, then split intent,
evidence, artifact goal, verifier profile, and repair profile.

T47 should be reframed. Keep it open as a symptom if useful, but the strategic
ticket should be "static web artifact goal, verification profile, and repair
profile coherence" rather than "fix BMI after full write."

Build a minimal capability profile registry now. Defer a full skill system.

The guiding rule:

Talos should keep deterministic control machinery, but each deterministic rule
needs an owner. Static web belongs to a Static Web capability profile. Literal
content belongs to an expectation factory. Protected resources belong to
permission policy. Tool aliases belong to a backend/tool profile. Evidence
requirements belong to an evidence obligation policy. Final truth belongs to an
outcome dominance policy.

That is how Talos avoids becoming a specialized web/static-site harness while
still preserving the hard-won local trust and execution discipline built through
0.9.8.
