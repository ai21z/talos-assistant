# Command Execution Architecture Design

Date: 2026-05-05
Status: T134 design, no implementation
Branch: `v0.9.0-beta-dev`

## Purpose

Talos should eventually run local development commands such as tests and
builds. That is useful, but it is also a larger trust boundary than file
read/write tools. A command runner can execute arbitrary programs, read local
secrets through output, mutate generated files, start long-running processes,
use the network, or damage the workspace.

This design defines the architecture before any `run_command` tool exists.
The first rule is simple:

```text
Do not add a generic shell tool.
```

Command execution must be a typed, policy-mediated capability. The model may
request a command profile. The runtime decides whether the profile is allowed,
asks the user when required, runs it with bounded process controls, and renders
the outcome from runtime facts.

## Local Architecture Fit

Command execution must follow the existing Talos control loop:

```text
User request
-> TaskContract
-> command profile / command plan
-> permission and command policy
-> approval
-> checkpoint decision when needed
-> bounded process runner
-> command result
-> truthful outcome
-> local trace
```

The local seams already available:

- `TurnProcessor` is the central tool execution gateway.
- `DeclarativePermissionPolicy` already implements allow/ask/deny decisions.
- `ProtectedPathPolicy` classifies protected paths and workspace escapes.
- `Sandbox` owns workspace path containment.
- `ApprovalGate` owns user confirmation.
- `CheckpointService` captures pre-mutation restore points.
- `ToolOperationMetadata` describes tool capability, risk, paths, checkpoint,
  trace, and verifier hooks.
- `LocalTurnTraceCapture` records policy, approval, checkpoint, tool, and
  outcome events.
- `ToolSurfacePlanner` exposes only tools that fit the current contract/phase.

The new command capability should add narrow command-specific policy and
execution services. It should not put process logic into
`AssistantTurnExecutor`.

## External Safety Basis

This design follows these external constraints:

- OWASP LLM06:2025 Excessive Agency identifies excessive functionality,
  permissions, and autonomy as root causes, and recommends minimizing
  extensions, avoiding open-ended tools such as shell commands, requiring human
  approval for high-impact actions, complete mediation, and logging/monitoring:
  https://genai.owasp.org/llmrisk/llm062025-excessive-agency/
- OWASP LLM02:2025 Sensitive Information Disclosure warns that sensitive data
  includes credentials and confidential business data, and that prompt
  restrictions may not be honored:
  https://genai.owasp.org/llmrisk/llm022025-sensitive-information-disclosure/
- MITRE CWE-78 recommends allowlisting commands and avoiding detailed user
  errors or logs that reveal sensitive data:
  https://cwe.mitre.org/data/definitions/78.html
- Microsoft PowerShell guidance says to avoid `Invoke-Expression` with user
  input because it parses and runs arbitrary string content:
  https://learn.microsoft.com/en-us/powershell/scripting/security/preventing-script-injection
- Oracle Java `ProcessBuilder` starts a process from a command/argument list,
  working directory, and environment. Command validity and process behavior are
  operating-system dependent:
  https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/ProcessBuilder.html
- OpenAI agent safety guidance recommends keeping tool approvals enabled and
  notes that risk rises when arbitrary text influences tool calls:
  https://platform.openai.com/docs/guides/agent-builder-safety
- model provider computer-use guidance recommends minimal privileges, avoiding
  sensitive data exposure, network allowlists, human confirmation for
  meaningful actions, and extra precautions for prompt injection:
  https://docs.model provider.com/en/docs/build-with-external assistant/computer-use

## Core Design Decision

Talos V1 command execution should expose command profiles, not raw shell.

The model-facing operation should be shaped like:

```json
{
  "profile": "gradle_test",
  "args": ["--tests", "dev.talos.runtime.SomeTest"],
  "cwd": ".",
  "timeout_ms": 120000
}
```

The runtime turns that into a `CommandPlan` only if:

- the profile is known;
- the profile allows the given arguments;
- the working directory stays inside the workspace;
- the risk level is classified;
- the policy decision is allow/ask/deny;
- approval succeeds when required;
- process execution can be bounded.

V1 must not accept:

```json
{"command": "powershell -Command \"...\""}
```

or:

```json
{"command": "cmd.exe /c ..."}
```

Free-form shell strings are out of scope.

## Proposed Runtime Types

Recommended package:

```text
dev.talos.runtime.command
```

Recommended records/services:

- `CommandProfile`
- `CommandProfileRegistry`
- `CommandPlan`
- `CommandArgumentPolicy`
- `CommandRiskClassifier`
- `CommandPermissionPolicy`
- `CommandExecutionPolicy`
- `CommandRunner`
- `ProcessCommandRunner`
- `CommandResult`
- `CommandOutputCapture`
- `CommandTraceEvents`

Recommended tool package:

```text
dev.talos.tools.impl.RunCommandTool
```

`RunCommandTool` should be thin. It should validate input shape, ask the
runtime command services for a plan, execute through `CommandRunner`, and
return a structured result. It must not parse task intent, decide completion,
or render final assistant success claims.

## Command Plan

`CommandPlan` should contain:

- `profileId`
- `displayName`
- `executable`
- `argv`
- `cwd`
- `risk`
- `networkAccess`
- `interactive`
- `expectedWrites`
- `requiresApproval`
- `requiresCheckpoint`
- `timeoutMs`
- `idleTimeoutMs`
- `stdoutLimitBytes`
- `stderrLimitBytes`
- `allowedExitCodes`
- `traceSummary`

The executable and fixed arguments come from the profile. Model-provided
arguments are appended only after `CommandArgumentPolicy` validates them.

## Risk Classification

Command execution needs command-specific risk, even if it eventually maps to
`ToolRiskLevel`.

Recommended risk enum:

- `READ_ONLY_DIAGNOSTIC`
- `BUILD_OR_TEST`
- `WORKSPACE_MUTATION`
- `DESTRUCTIVE`
- `NETWORK`
- `INTERACTIVE`
- `UNKNOWN`

Default mapping:

- `READ_ONLY_DIAGNOSTIC` -> ask in V1.
- `BUILD_OR_TEST` -> ask in V1; allowed generated-output writes only.
- `WORKSPACE_MUTATION` -> out of scope for V1 unless a future ticket defines
  checkpointable source changes.
- `DESTRUCTIVE` -> deny in V1.
- `NETWORK` -> deny in V1 unless a future explicit network allowlist exists.
- `INTERACTIVE` -> deny in V1.
- `UNKNOWN` -> deny.

Even read-only commands ask in V1 because command output can disclose
protected information. Later config may allow specific read-only profiles.

## V1 Supported Use Cases

V1 should start with a small profile set:

- Gradle verification:
  - `gradle_test`
  - `gradle_check`
  - `gradle_build`
  - `gradle_install_dist`
  - `gradle_e2e_test`
- Git read-only diagnostics:
  - `git_status`
  - `git_diff`
  - `git_log`
- Runtime version diagnostics:
  - `java_version`
  - `talos_version`

V1 should not include:

- package install/update commands;
- `git commit`, `git push`, `git checkout`, `git reset`, `git clean`;
- delete commands such as `rm`, `del`, `rmdir`;
- formatters that rewrite source files;
- arbitrary `npm`, `pnpm`, `pip`, `uv`, `cargo`, `go`, or `mvn` commands;
- background servers, watchers, or daemons;
- commands that require interactive input;
- commands that request elevation/admin privileges;
- commands that intentionally access the network.

## Windows-First Process Rules

Talos is Windows-first, so the runner must be explicit:

- Use `ProcessBuilder(List<String>)` with separate executable and arguments.
- Do not invoke `cmd.exe /c` or `powershell -Command` with model-provided text.
- Do not use PowerShell `Invoke-Expression`.
- If a Windows batch file such as `gradlew.bat` is supported, it must be a
  fixed profile executable with fixed argument validation.
- Resolve executables from explicit workspace paths or trusted tool discovery,
  not from arbitrary model text.
- Normalize `cwd` through `Sandbox`; reject workspace escapes before approval.
- Disable inherited stdin by default.
- Do not inherit IO; capture output under caps.
- Kill timed-out processes and their descendants where the JDK/platform allows.

## Permission Policy

Command permission should be deny-first:

1. Invalid profile or args -> deny.
2. Shell mode -> deny.
3. Workspace escape in `cwd` or path-like args -> deny.
4. Protected path target without explicit supported profile -> deny.
5. Network risk -> deny in V1.
6. Destructive risk -> deny in V1.
7. Interactive/background risk -> deny in V1.
8. Known build/test/read-only profile -> ask.
9. Future user config may allow selected profiles, but not shell mode.

Approval detail must show:

- profile name;
- exact executable and argv;
- cwd;
- risk;
- timeout;
- output caps;
- expected writes;
- checkpoint behavior;
- whether network and interactive mode are disabled.

Approval responses should use the existing `ApprovalGate.approveFull`.
Remembered approval should be disabled for V1 command execution, or limited to
a future profile-specific allow rule after dedicated tests.

## Cwd And Path Limits

V1 `cwd` rules:

- default cwd is workspace root;
- relative cwd resolves under workspace;
- absolute cwd must resolve under workspace;
- symlink escapes are denied by the sandbox;
- protected directories are denied unless the profile explicitly supports
  reading them and the user approves;
- profile arguments that look like paths must be normalized and checked.

No command may run from `%USERPROFILE%`, system directories, temp directories,
or arbitrary parent directories in V1.

## Environment Policy

`ProcessBuilder.environment()` starts from the current process environment, so
Talos should replace it with a minimal environment instead of blindly
inheriting everything.

Recommended V1 environment:

- include only variables required to launch Java/Gradle on Windows;
- include `SystemRoot`, `ComSpec` only when required by a fixed profile;
- include `JAVA_HOME` only if already configured and not secret-like;
- include a minimal `PATH` or explicit executable paths;
- include `TEMP`/`TMP` under a Talos-controlled or workspace-safe location when
  feasible;
- never accept model-provided environment variables in V1;
- redact secret-like env keys from trace and output.

Secret-like env keys:

- contains `SECRET`
- contains `TOKEN`
- contains `KEY`
- contains `PASSWORD`
- contains `CREDENTIAL`
- contains `AUTH`

Trace must not store raw environment values.

## Timeouts And Output Caps

Recommended defaults:

- default timeout: 120 seconds;
- maximum timeout: 10 minutes, config-gated;
- idle timeout: 30 seconds with no output for interactive-risk profiles;
- stdout cap: 64 KiB;
- stderr cap: 64 KiB;
- combined trace summary cap: 16 KiB;
- full output artifact: optional local debug artifact, redacted by default.

When output is capped, the result should keep a deterministic head/tail summary
and record `outputTruncated=true`.

## Checkpoint Rules

Command profiles must declare expected writes.

V1 rules:

- `READ_ONLY_DIAGNOSTIC`: no checkpoint.
- `BUILD_OR_TEST`: no source checkpoint if the profile only writes known
  generated output directories such as `build/`, `.gradle/`, `out/`, or
  `.talos/tmp/`; trace must record generated-output writes as expected.
- Any command profile that may modify source files requires a checkpoint plan
  over known source targets before execution.
- If source targets are not knowable before execution, the profile is out of
  scope for V1.
- Destructive commands remain denied.

Session-remembered approval must not skip checkpointing.

## Network Policy

V1 command execution should be local-only.

Network-denied examples:

- dependency install/update;
- downloading scripts;
- `curl`, `wget`, `Invoke-WebRequest`;
- package manager commands;
- `git fetch`, `git pull`, `git push`;
- test commands that require live network unless a later ticket explicitly
  supports network profiles.

If a future ticket enables network command profiles, it must define domain
allowlists, proxy behavior, redaction, timeout, and approval prompts.

## Result Shape

`CommandResult` should contain:

- `profileId`
- `argv`
- `cwd`
- `exitCode`
- `durationMs`
- `timedOut`
- `killed`
- `stdout`
- `stderr`
- `stdoutTruncated`
- `stderrTruncated`
- `redactionApplied`
- `policyDecision`
- `approvalStatus`
- `checkpointStatus`

Tool output should be runtime-owned:

```text
Command failed: gradle_test exited with code 1 after 18.4s.
stdout: ...
stderr: ...
```

Current model-context boundary: `run_command` stdout and stderr are not withheld from model context by default. Command output (run_command stdout/stderr) is passed to the model after best-effort textual redaction of recognizable secret-assignment patterns and known markers only. It is NOT withheld by default and is not classified by source path. Do not run commands that print real credentials in this beta.

The final assistant outcome must be failure-dominant when:

- the command is denied;
- approval is denied;
- the command times out;
- the exit code is not allowed;
- output capture fails;
- checkpoint fails before a source-mutating command.

The model must not be allowed to append "tests passed" or "ready to use" after
a failed command result.

## Trace Events

Add command-specific trace events:

- `COMMAND_PLAN_CREATED`
- `COMMAND_POLICY_DECISION`
- `COMMAND_APPROVAL_REQUIRED`
- `COMMAND_APPROVAL_GRANTED`
- `COMMAND_APPROVAL_DENIED`
- `COMMAND_CHECKPOINT_DECISION`
- `COMMAND_STARTED`
- `COMMAND_OUTPUT_TRUNCATED`
- `COMMAND_COMPLETED`
- `COMMAND_FAILED`
- `COMMAND_TIMED_OUT`
- `COMMAND_KILLED`
- `COMMAND_DENIED`

Trace data should include:

- profile id;
- risk;
- cwd path hint;
- argv hash and safe display argv;
- timeout;
- output caps;
- exit code;
- duration;
- truncation booleans;
- redaction booleans.

Trace data must not include raw secrets, full environment, or uncapped output.

## Tool Surface

`talos.run_command` should not appear for ordinary read-only questions or
ordinary file mutation turns.

V1 surface rules:

- show only for explicit command-profile requests or verification-oriented dev
  tasks;
- hide for small talk, privacy-negated prompts, directory listing, and normal
  file read/write tasks;
- expose only when `CommandProfileRegistry` has at least one profile enabled;
- include current visible profiles in the current-turn capability frame;
- keep command profile requirements runtime-enforced, not prompt-only.

## Verification

Unit tests:

- `CommandProfileRegistryTest`
- `CommandArgumentPolicyTest`
- `CommandRiskClassifierTest`
- `CommandPermissionPolicyTest`
- `ProcessCommandRunnerTest`
- `RunCommandToolTest`
- `TurnProcessorCommandPolicyTest`
- `LocalTurnTraceCommandTest`
- `CommandOutcomeTest`

Scenario tests:

- Gradle test command asks approval, runs, captures exit code.
- Denied shell command does not ask approval and does not run.
- Workspace escape cwd is denied before approval.
- Timeout kills the process and reports failure.
- Output caps are applied and trace says output was truncated.
- Secret-like output is redacted.
- Failed test command produces failure-dominant final output.

Manual installed checks:

- run `gradle_test` against a passing test;
- run `gradle_test` against a failing test;
- deny approval and verify no process runs;
- attempt `cmd.exe /c` and verify denial;
- attempt parent cwd and verify denial;
- inspect `/last trace` for command events and redacted output.

## Implementation Ticket Sequence

Recommended sequence after this design:

1. T135 - Command profile and plan core types.
   Add `dev.talos.runtime.command` records and profile registry. No process
   execution.
2. T136 - Command argument and risk policy.
   Add validators for Gradle/git diagnostics and deny shell/network/destructive
   shapes.
3. T137 - Bounded process runner.
   Add `ProcessCommandRunner` with timeout, output caps, environment policy,
   and redaction. Tests use tiny local commands only.
4. T138 - `talos.run_command` V1 for Gradle verification profiles.
   Register the tool, wire approval, policy, trace, and runtime-owned result.
5. T139 - Command outcome integration.
   Ensure failed/denied/timed-out commands are failure-dominant and cannot be
   followed by model success prose.
6. T140 - Focused command execution audit.
   Run clean local command probes before any broader capability audit.

Do not implement command execution directly inside T134.

## Out Of Scope For V1

- generic shell;
- arbitrary command strings;
- pipelines, redirects, command substitution;
- PowerShell scripts supplied by the model;
- package install/update;
- network access;
- destructive commands;
- long-running services;
- background process manager;
- terminal UI programs;
- source-formatting commands;
- git write operations;
- command-triggered source mutation without known checkpoint targets.

## Acceptance For A Future Implementation

Command execution should be considered ready only when:

- command input is structured and profile-based;
- generic shell is denied;
- cwd is workspace-contained;
- all V1 profiles require explicit approval;
- timeout and output caps are enforced by tests;
- output and environment redaction are tested;
- failed commands produce failure-dominant final output;
- local trace records command lifecycle events;
- no command policy decision depends on model prose.
