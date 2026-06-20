# Declarative Allow/Ask/Deny Permissions

Date: 2026-04-28
Status: T34 design
Parent architecture: `docs/architecture/01-execution-discipline-and-local-trust.md`
Related map: `docs/architecture/02-runtime-policy-ownership-map.md`

## Purpose

This document designs Talos's first declarative local permission layer.

The goal is not enterprise RBAC. The goal is a local, understandable
allow/ask/deny policy that makes tool execution safer before Talos grows more
dangerous capabilities. Permission decisions must be deterministic runtime
decisions, not model judgments or prompt-only instructions.

The permission layer answers:

- may this tool run in this phase?
- does the requested resource stay inside the workspace?
- is the resource protected or sensitive?
- should Talos allow, ask the user, or deny?
- can the user's "yes for this session" choice be remembered?
- what should be recorded in the local turn trace?

## Current State

Current permission behavior is split across several classes:

- `NativeToolSpecPolicy` chooses which tools the model can see for the current
  `TaskContract` and `ExecutionPhase`.
- `TurnProcessor` is the central enforcement gateway for tool execution.
- `TurnProcessor` blocks mutating tools for read-only task contracts.
- `PhasePolicy` blocks mutating tools outside `APPLY`.
- `Sandbox` blocks paths that escape the workspace and applies simple
  allow/deny prefixes from config.
- `ScopeGuard` warns when a mutating target appears off-scope for a web task.
- `ApprovalPolicy` returns `AUTO_APPROVE`, `ASK`, or `DENY`.
- `SessionApprovalPolicy` remembers in-workspace write approval for the current
  session and keeps sensitive targets asking.
- `ApprovalGate` is the user interaction seam.

This is a good foundation, but it is not yet a declarative permission model.
The next implementation should keep `TurnProcessor` as the enforcement gateway
and keep `ApprovalGate` as a UI prompt, while moving policy decision logic into
a typed permission decision object.

## Non-Goals

This design does not add:

- shell execution
- browser automation
- MCP tools
- cloud policy services
- remote telemetry
- enterprise RBAC
- roles, groups, tenants, or organization policy
- LLM-based permission classification
- checkpoint/restore behavior

Checkpointing is a later T36/T37 layer. Permissions should be designed so a
future checkpoint decision can run before approved mutation, but T34/T35 do not
implement checkpoint storage.

## Policy Shape

T35 should introduce a small runtime policy package:

```text
dev.talos.runtime.policy
```

Recommended v1 classes:

- `PermissionPolicy`
- `PermissionDecision`
- `PermissionAction`
- `PermissionReason`
- `PermissionRule`
- `PermissionConfig`
- `ProtectedPathPolicy`
- `ResourceDecision`

`PermissionAction` should be:

```text
ALLOW
ASK
DENY
```

`PermissionDecision` should contain:

- action
- reason code
- user-facing explanation
- tool name
- tool risk
- execution phase
- normalized relative path, when available
- resource classification
- whether approval can be remembered
- approval prompt details, when action is `ASK`
- trace-safe details

The model never sees the authority to override this decision. It may request a
tool call, but Talos decides whether the call is allowed, asks the user, or is
denied.

## Config Location

The v1 implementation should prefer the existing user-owned config path:

```text
%USERPROFILE%\.talos\config.yaml
~/.talos/config.yaml
```

Add a `permissions` block under the existing config file instead of creating a
second loader immediately. This keeps T35 small and reuses current config
loading.

Workspace-local permission files should not be trusted by default because a
workspace can be untrusted and model-editable. A later ticket may add an
explicit trusted-workspace opt-in, but project-local files must not silently
grant broader permissions than the user's global config.

If a future workspace-local file is added, it should be tighten-only by
default:

- it may add deny or ask rules
- it must not add allow rules unless the user explicitly marks the workspace as
  trusted outside the workspace itself

## Config Format

Use YAML-compatible data because Talos already loads YAML config.

Recommended v1 shape:

```yaml
permissions:
  defaults:
    read: allow
    write: ask
    destructive: ask

  remember:
    allow_session_for_write: true
    protected_paths_remember: false
    destructive_remember: false

  protected_paths:
    secret_paths:
      - ".env"
      - ".env.*"
      - "**/.env"
      - "**/.env.*"
      - "**/secrets/**"
      - "**/*secret*"
      - "**/*token*"
      - "**/*credential*"
      - "**/*.pem"
      - "**/*.key"
      - "**/*.p12"
      - "**/*.pfx"
      - "**/id_rsa"
      - "**/id_dsa"
      - "**/id_ecdsa"
      - "**/id_ed25519"
      - "**/.ssh/**"
      - "**/.aws/**"
      - "**/.azure/**"
      - "**/.config/gcloud/**"
    control_paths:
      - "**/.git/**"
      - "**/.github/workflows/**"
      - "**/.gnupg/**"

  rules:
    - effect: deny
      tools: ["talos.write_file", "talos.edit_file"]
      paths: ["**/.git/**"]
      reason: "Do not mutate Git internals."

    - effect: ask
      risks: ["READ_ONLY"]
      paths: ["**/*secret*", "**/*token*", "**/.env*"]
      reason: "Reading likely secrets requires explicit approval."

    - effect: allow
      tools: ["talos.read_file", "talos.grep", "talos.list_dir", "talos.retrieve"]
      phases: ["INSPECT", "VERIFY", "APPLY"]
      within_workspace: true
      reason: "Normal in-workspace reads are allowed."
```

Rules should be explicit and typed. Do not implement a giant untyped phrase or
glob dump. Invalid rule fields should fail closed for that rule and surface a
configuration warning.

## Decision Precedence

Permission precedence must be deterministic:

1. Hard runtime invariants.
2. Explicit deny rules.
3. Explicit ask rules.
4. Explicit allow rules.
5. Default policy.
6. Session remember, only when the decision remains remember-eligible.

In short:

```text
deny beats ask
ask beats allow
defaults are conservative
remember cannot override deny or protected ask
```

Hard runtime invariants are not ordinary user rules:

- unknown tools are denied
- malformed tool calls are rejected before approval
- paths escaping the workspace are denied
- task-contract read-only denial blocks mutating calls
- phase policy blocks tools that do not belong in the current phase
- forbidden targets from the current `TaskContract` are denied before approval

These invariants must stay in `TurnProcessor` or a policy object called by
`TurnProcessor`. User config must not weaken them.

## Defaults

Recommended defaults:

- `READ_ONLY` tools inside the workspace: `ALLOW`
- `READ_ONLY` tools targeting protected secret paths: `ASK`
- broad search/retrieve over a workspace: `ALLOW`, but protected paths should
  be skipped by default or require explicit approval before inclusion
- `WRITE` tools inside the workspace: `ASK`
- `WRITE` tools targeting protected paths: `ASK`, not remember-eligible
- `DESTRUCTIVE` tools: `ASK` by default, not remember-eligible
- paths outside workspace: `DENY`
- tools hidden by task contract or phase: `DENY`

This preserves Talos's current local-first ergonomics while preventing silent
secret reads and silent protected-path writes.

Windows trailing-dot and trailing-space path aliases are canonicalized before protected-path matching; this is not a complete Windows path-security proof.

## Protected Path Behavior

Protected paths should be classified into at least two groups.

### Secret-Like Paths

Examples:

- `.env`
- `.env.*`
- `**/.env`
- `**/.env.*`
- `**/secrets/**`
- `**/*secret*`
- `**/*token*`
- `**/*credential*`
- private key files such as `*.pem`, `*.key`, `*.p12`, `*.pfx`
- SSH key names such as `id_rsa`, `id_dsa`, `id_ecdsa`, `id_ed25519`
- cloud credential directories such as `.aws`, `.azure`, and `.config/gcloud`

Default action:

- specific `read_file`: `ASK`
- broad `grep`/`retrieve`: skip by default, or `ASK` only when the user
  explicitly asks to include protected files
- `write_file`/`edit_file`: `ASK`, not remember-eligible

### Control-Plane Paths

Examples:

- `.git/**`
- `.github/workflows/**`
- `.gnupg/**`

Default action:

- `read_file`: `ALLOW` unless user config says otherwise
- `write_file`/`edit_file`: `ASK`, not remember-eligible
- destructive operations, if added later: `ASK` or `DENY` by default, decided
  in the destructive-tool ticket

This preserves the existing `SessionApprovalPolicy` behavior where sensitive
paths still ask even after a session-level remember choice.

## Workspace And Path Normalization

Path handling must be Windows-first:

- normalize separators to `/` for matching
- resolve relative paths against the workspace
- reject workspace escapes before approval
- compare case-insensitively on Windows
- resolve symlinks where possible through the sandbox
- never allow a config rule to permit an escaped path

Glob matching should run against workspace-relative normalized paths. Absolute
home paths should not appear in trace output by default.

## Interaction With `ApprovalPolicy`

T35 should not abruptly delete `ApprovalPolicy`. A compatible path is:

1. Introduce `PermissionPolicy` and `PermissionDecision`.
2. Implement an adapter that preserves current `SessionApprovalPolicy`
   behavior.
3. Gradually move session remember and protected path logic into the new
   permission policy.
4. Keep `ApprovalPolicy` as a compatibility seam until callers no longer need
   it.

`SessionApprovalPolicy` currently guarantees:

- read-only tools auto-approve
- destructive tools never auto-approve
- remembered in-workspace writes may auto-approve
- out-of-workspace writes always ask
- `.env`, `.git`, `.github`, `.ssh`, and `.gnupg` style sensitive targets
  still ask even after remember

T35 must preserve these behaviors unless the ticket explicitly changes them
with tests.

## Interaction With `ApprovalGate`

`ApprovalGate` remains the prompt/UI seam. It should not become the policy
engine.

Permission flow:

```text
PermissionPolicy decides ALLOW/ASK/DENY
-> ALLOW executes without asking
-> ASK calls ApprovalGate.approveFull(...)
-> DENY returns a structured tool denial
```

`ApprovalResponse.APPROVED_REMEMBER` should only update session remember when
`PermissionDecision.rememberEligible` is true.

Protected paths, destructive tools, and scope-warning escalations should be
not remember-eligible by default.

## Interaction With `TurnProcessor`

`TurnProcessor` remains the enforcement gateway.

Recommended T35 ordering inside `executeTool`:

1. Validate `session`, `ctx`, and tool existence.
2. Resolve the active `TaskContract`.
3. Record trace-safe tool attempt.
4. Enforce task-contract mutation denial.
5. Enforce phase policy.
6. Reject template placeholders and malformed required arguments.
7. Resolve and sandbox-check path parameters.
8. Classify resources through `ResourcePolicy`.
9. Ask `PermissionPolicy` for `PermissionDecision`.
10. If `DENY`, return a structured denial before approval.
11. If `ASK`, call `ApprovalGate`.
12. If approved and remember-eligible, update session remember.
13. Execute the tool.
14. Record trace-safe result.

No approval prompt should appear for malformed calls, workspace escapes, phase
denials, task-contract denials, or explicit deny rules.

## Interaction With Phase Policy

Phase policy remains a hard boundary:

- `INSPECT` and `VERIFY` allow read/search/retrieve only
- `APPLY` may allow mutation if the task contract permits it
- `RESPOND` allows no tools

Permission config must not allow mutating tools in `INSPECT`, `VERIFY`, or
`RESPOND`. A permission rule may be stricter than phase policy, but never
looser.

## Interaction With Tool Surface

`NativeToolSpecPolicy` decides what tools are visible to the model. Permission
policy decides whether an attempted call can execute.

Both layers are required:

- tool surface prevents unnecessary tempting tools from being shown
- permission enforcement blocks drift, malformed calls, or policy violations
  even when the model emits a hidden or blocked tool call

T35 may optionally pass permission context into tool-surface selection later,
but execution enforcement must not depend on tool visibility alone.

## Broad Read Tools

Broad read tools need careful handling because they can reveal protected
content without naming a protected path.

V1 should treat them as follows:

- `list_dir`: may show filenames in normal directories, but should ask before
  enumerating protected directories such as `.ssh` or `secrets`
- `grep`: should skip protected paths by default and report that protected
  paths were skipped; explicit protected search should ask
- `retrieve`: should not index or retrieve protected paths by default; if the
  index already contains protected content, that is a separate indexing policy
  ticket
- `read_file`: specific protected targets should ask

This avoids surprising file-content leaks while keeping ordinary workspace
inspection usable.

## Trace Requirements

Permission decisions should write trace-safe events to the local turn trace:

- decision action
- reason code
- tool name
- phase
- risk
- redacted relative path
- protected-path classification
- approval required/granted/denied
- remember applied or refused

Trace must not store full file contents, full write payloads, or raw secrets by
default.

Suggested reason codes:

- `TOOL_UNKNOWN`
- `TASK_CONTRACT_READ_ONLY`
- `PHASE_DENIED`
- `WORKSPACE_ESCAPE`
- `PROTECTED_PATH_ASK`
- `CONFIG_DENY`
- `CONFIG_ASK`
- `CONFIG_ALLOW`
- `DEFAULT_READ_ALLOW`
- `DEFAULT_WRITE_ASK`
- `SESSION_REMEMBER_ALLOW`
- `APPROVAL_GRANTED`
- `APPROVAL_DENIED`

## Test Matrix For T35

### Unit Tests

`PermissionConfigTest`

- parses defaults
- parses deny/ask/allow rules
- rejects invalid effects
- handles missing config with safe defaults

`ProtectedPathPolicyTest`

- matches `.env`, `.env.local`, nested `.env`
- matches `secrets/`, `secret`, `token`, `credential`
- matches private key names and extensions
- matches `.ssh`, `.aws`, `.azure`, `.config/gcloud`
- handles Windows slashes and case normalization
- does not over-trigger on normal files such as `environment.md`

`PermissionPolicyTest`

- deny beats ask
- ask beats allow
- read inside workspace defaults to allow
- read protected path defaults to ask
- write inside workspace defaults to ask
- write protected path asks and is not remember-eligible
- destructive never auto-allows
- session remember allows only safe in-workspace writes
- session remember does not apply to protected paths
- workspace escape is denied

`TurnProcessorPermissionPolicyTest`

- explicit deny returns before `ApprovalGate`
- protected read calls `ApprovalGate`
- protected write calls `ApprovalGate` and cannot be remembered
- remembered safe write bypasses gate
- phase-denied mutation does not reach `ApprovalGate`
- task-contract read-only denied mutation does not reach `ApprovalGate`
- malformed write args do not reach `ApprovalGate`

### E2E Scenarios

Add deterministic JSON scenarios for:

- deny rule blocks write before approval
- ask rule prompts for protected read
- session remember auto-allows normal write but not `.env`
- read-only workspace prompt still exposes no mutating tools
- privacy-negated small talk still uses no tools

### Manual Checks

Manual installed Talos checks for T35 should include:

- normal `read_file` of `README.md`
- `read_file` of `.env` asks before reading
- write to normal file asks once and can remember
- subsequent normal write auto-allows if remembered
- write to `.env` still asks after remember
- denied path rule blocks without approval prompt
- task-contract read-only denial still blocks mutation without approval prompt

## Migration Plan For T35

T35 should be incremental:

1. Add the typed policy classes and default config model.
2. Add protected path classification.
3. Add a permission-policy adapter preserving `SessionApprovalPolicy` behavior.
4. Wire `TurnProcessor` through the new decision object for mutating tools.
5. Extend read-only protected-path handling only where the tool path is
   specific and bounded, such as `read_file`.
6. Leave broad search/index protected-content policy to a follow-up if it
   requires larger tool changes.
7. Record permission decisions in local trace.

This avoids a broad rewrite while establishing the allow/ask/deny foundation.

## Risks

- Protected path matching can over-trigger on normal source files.
- Broad search tools can still leak protected content unless they skip or ask.
- A workspace-local config file can be malicious if trusted automatically.
- Too much prompting can make Talos feel unusable.
- Too little prompting can leak secrets or mutate sensitive files silently.
- Permission code can duplicate sandbox or phase policy if boundaries are not
  clear.
- Session remember can become dangerous if protected paths are rememberable.

## Open Questions

- Should protected `read_file` ask in T35, or should read-sensitive handling be
  a separate ticket after mutating permission MVP?
- Should `grep` skip protected paths by default in T35, or should that live in
  indexing/resource policy?
- Should permission config support per-workspace trusted overlays in v1, or
  should all v1 policy live in user config only?
- Should `.github/workflows/**` be ask-only or deny-by-default for mutation?
- Should trace include user-facing approval prompt text or only reason codes?
- How should `/policy` display effective permission rules without showing
  sensitive absolute paths?

## T35 Acceptance Summary

T35 should be considered complete only when:

- allow/ask/deny decisions are typed
- deny-first precedence is tested
- protected path defaults are tested
- `TurnProcessor` remains the enforcement gateway
- `ApprovalGate` remains the prompt seam
- existing session remember behavior is preserved or intentionally tightened
- read-only privacy and small-talk boundaries still pass
- workspace escapes remain denied before approval
- local trace captures permission decisions without raw sensitive content
