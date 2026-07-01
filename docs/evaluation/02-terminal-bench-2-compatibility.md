# Terminal-Bench 2 Compatibility For Talos

Status: design and classification guidance only.

Date: 2026-04-29

This document defines how Talos should evaluate Terminal-Bench 2 without
treating it as a direct release gate before Talos has a controlled terminal or
test-runner capability.

References used for this review:

- Terminal-Bench 2 registry:
  https://www.harborframework.com/registry/terminal-bench/2.0
- Harbor Terminal-Bench run guide:
  https://www.harborframework.com/docs/tutorials/running-terminal-bench
- Harbor eval documentation:
  https://harborframework.com/docs/run-jobs/run-evals
- Terminal-Bench repository:
  https://github.com/harbor-framework/terminal-bench
- Terminal-Bench paper:
  https://arxiv.org/abs/2601.11868

## 1. What Terminal-Bench 2 Measures

Terminal-Bench 2 measures agent performance on hard, realistic tasks in
computer terminal environments. The benchmark is built around agents that can
operate in a terminal sandbox, inspect the environment, run commands, edit
artifacts, and complete tasks that are verified by task-specific tests.

The public Terminal-Bench materials describe the benchmark as a dataset plus an
execution harness for real terminal environments. Tasks include an English
instruction, a test script or verifier, and a reference/oracle solution. Harbor
is the official harness for running Terminal-Bench 2.0, and Harbor datasets are
collections of tasks containing an instruction, environment, and test script.

The Terminal-Bench 2 registry exposes task names such as:

- `build-cython-ext`
- `compile-compcert`
- `configure-git-webserver`
- `fix-code-vulnerability`
- `large-scale-text-editing`
- `log-summary-date-ranges`
- `nginx-request-logging`
- `pypi-server`
- `sqlite-db-truncate`
- `write-compressor`

This task set is useful precisely because many tasks require more than writing
text. They often require command execution, dependency setup, compilation,
test execution, service configuration, dataset processing, or terminal-level
debugging.

## 2. Why It Is Useful

Terminal-Bench 2 is useful external pressure for Talos because it tests
multi-step work under objective verification. It can reveal gaps in:

- long-horizon task planning
- multi-file workspace reasoning
- edit quality
- debugging after failed verification
- preserving state across a task
- handling task instructions grounded in a real environment
- producing artifacts that satisfy tests instead of just plausible prose

Terminal-Bench results should be interpreted as model-agent results, not model
results alone. The agent harness matters: tool surface, sandboxing, command
execution, trace capture, retry behavior, and verification policy all change
performance.

For Talos, Terminal-Bench can provide roadmap signal for future controlled test
execution and terminal work. It should not replace TalosBench, which tests
Talos-specific local trust promises such as protected-path policy,
checkpoint/restore, trace redaction, action obligations, and truthful outcomes.

## 3. Why It Is Not A Direct Talos Release Gate Yet

Talos is currently a local-first workspace operator with controlled file tools,
permissions, approval, checkpoint/restore, trace, and verification. Talos does
not yet expose a general shell, package manager, browser, network service
runner, Docker control, or arbitrary test execution as a first-class capability.

Many Terminal-Bench 2 tasks require terminal capabilities outside Talos's
current supported tool surface. Examples from task names alone show likely
requirements such as compiling code, building native extensions, configuring
servers, running databases, processing media, recovering archives, training
models, or running project-specific tests.

Therefore:

- A failure on a task that requires shell commands is not automatically a Talos
  product bug.
- A task that needs verifier tests cannot become a hard Talos release gate
  until Talos has a controlled test runner and command policy.
- A task can still be useful as a research signal if it exposes a future
  capability need.

The current hard local release gate remains TalosBench plus deterministic unit
and JSON e2e coverage.

## 4. Task Classification Labels

Classify every Terminal-Bench task before running Talos against it.

| Label | Meaning | Candidate criteria | Release impact |
| --- | --- | --- | --- |
| `SUPPORTED_NOW` | Talos can attempt the task with its current local file tools and verification model. | The task can be completed by reading, searching, editing, writing, and static/readback verification only. It does not require shell commands, package installs, service startup, Docker, browser, network access, or executing tests. | Failure can be a candidate blocker if it violates Talos invariants. |
| `PARTIALLY_SUPPORTED` | Talos can do a meaningful file-editing slice, but the official task requires unsupported execution or verification. | The task has readable files and editable artifacts, but final success depends on commands, tests, compilation, or runtime behavior. | Failure is usually a follow-up unless Talos breaks a supported invariant while attempting the file slice. |
| `UNSUPPORTED_TOOL_SURFACE` | The task requires capabilities Talos intentionally does not expose yet. | Requires shell, Docker, package manager, long-running server, browser, external network, binary tooling, GPU/model runtime, privileged system access, or verifier execution. | Not a release blocker. File as future capability signal only if strategically relevant. |
| `RESEARCH_SIGNAL` | The task is not appropriate for current Talos execution but provides useful design pressure. | It reveals future needs such as controlled test running, command permissions, stdout/stderr redaction, or sandboxing. | Roadmap input only. |

Classification checklist:

- Does the task require running any command?
- Does it require executing a test suite or verifier?
- Does it require building, compiling, or installing dependencies?
- Does it require Docker, containers, or a sidecar service?
- Does it require a long-running process or server?
- Does it require network, browser, image/video, GPU, or system-level access?
- Does success depend on stdout/stderr inspection?
- Can the meaningful task be reduced to workspace read/write/edit only?
- Can Talos verify the result with existing static, expectation, readback, or
  scenario evidence?

Likely `SUPPORTED_NOW` candidates are rare and should be confirmed by reading
the actual task, not inferred from the name. Possible candidates to inspect
first include text or source-transformation tasks such as
`large-scale-text-editing`, `filter-js-from-html`, `break-filter-js-from-html`,
`log-summary-date-ranges`, and `regex-log`. Even these may become
`PARTIALLY_SUPPORTED` if their official verifier requires command execution.

Tasks such as `build-cython-ext`, `compile-compcert`, `configure-git-webserver`,
`pypi-server`, `sqlite-with-gcov`, `torch-pipeline-parallelism`, or
`video-processing` should be presumed `UNSUPPORTED_TOOL_SURFACE` until Talos has
a controlled command/test runner.

## 5. How To Run It If Installed

Terminal-Bench 2 should be run through Harbor when available. Do not add a Talos
Terminal-Bench integration in this milestone.

Recommended exploratory process:

1. Install Harbor according to upstream docs.
2. Confirm Docker is installed and running.
3. Run the official oracle first to verify the local Harbor and Docker setup:

   ```powershell
   harbor run -d terminal-bench/terminal-bench-2 -a oracle
   ```

4. Classify tasks before running Talos.
5. Select a tiny subset marked `SUPPORTED_NOW` or `PARTIALLY_SUPPORTED`.
6. Run only those tasks with the experimental Talos adapter or manual workflow
   available at that time.
7. Store raw logs locally and commit only redacted summaries.

The Harbor docs also show registry-style runs such as:

```powershell
harbor run -d terminal-bench/terminal-bench-2 -m "<model>" -a "<agent>"
```

Those commands are documentation for future external evaluation. They are not
part of the current Talos candidate loop.

## 6. How To Record Results

Create a redacted summary for every Terminal-Bench exploration. Raw logs should
stay under ignored local paths such as:

```text
local/manual-testing/terminal-bench/<timestamp>/
```

Tracked summaries can live under:

```text
docs/evaluation/terminal-bench-runs/
```

Recommended summary table:

| Field | Purpose |
| --- | --- |
| Task id | Terminal-Bench task name. |
| Domain | Software, data, security, ML, systems, text processing, etc. |
| Classification | `SUPPORTED_NOW`, `PARTIALLY_SUPPORTED`, `UNSUPPORTED_TOOL_SURFACE`, or `RESEARCH_SIGNAL`. |
| Classification reason | Short explanation tied to Talos's current tool surface. |
| Unsupported requirements | Shell, tests, Docker, services, browser, network, binaries, etc. |
| Model/agent | Talos version, model, and adapter/manual workflow used. |
| Transcript/log path | Local path only; do not commit raw logs. |
| Trace id/path | Talos trace id if the run used installed Talos. |
| Outcome | Pass, fail, unsupported, partial, or not run. |
| Talos invariant result | Whether TaskContract, tools, permission, checkpoint, trace, verification, and outcome truth behaved correctly. |
| Ticket action | None, deterministic e2e, architecture ticket, future milestone, or unsupported. |

Do not claim a benchmark score until the task selection and unsupported-task
handling are documented.

## 7. How To Convert Failures Into Talos Tickets

Use the TalosBench taxonomy from
`docs/evaluation/01-talosbench-live-prompt-matrix.md`.

Failure handling rules:

- `SUPPORTED_NOW` failure:
  - Treat as a possible Talos defect.
  - Capture transcript, `/last trace`, file diffs, and expected invariants.
  - Convert to a deterministic unit/e2e regression where possible.
  - Create one architecture-level ticket for the failure cluster, not one ticket
    per prompt or task.

- `PARTIALLY_SUPPORTED` failure:
  - Split the supported file-tool behavior from unsupported command/test
    behavior.
  - File a Talos bug only if Talos violates a supported invariant such as
    permission, checkpointing, trace redaction, or truthful outcome.
  - File future capability work if the blocker is controlled test execution.

- `UNSUPPORTED_TOOL_SURFACE` failure:
  - Do not treat as a release blocker.
  - Record which missing capability blocked the task.
  - Fold repeated missing capabilities into future design tickets.

- `RESEARCH_SIGNAL` finding:
  - Record as roadmap evidence.
  - Do not create implementation work unless it supports an approved milestone.

Ticket titles should name the architectural bucket, not the external benchmark
task. For example:

- Good: `design-controlled-test-runner-policy`
- Good: `redact-command-output-in-local-trace`
- Bad: `fix build-cython-ext`

Every ticket created from Terminal-Bench evidence should include:

- the classification label
- why the task is or is not inside Talos's current tool surface
- transcript/log location
- Talos trace summary
- deterministic regression plan
- non-goals that prevent shell/browser/MCP expansion by accident

## 8. Requirements Before Making It A Hard Gate

Terminal-Bench 2 should become a hard Talos release gate only after Talos has
the infrastructure to run terminal tasks safely and inspectably.

Required foundations:

- Controlled test runner:
  - explicit command allowlist
  - timeouts and resource limits
  - deterministic workspace-only execution
  - clear distinction between test commands and arbitrary shell

- Shell policy:
  - no general shell by default
  - command categories and risk levels
  - deny-first protected paths and protected commands
  - no privilege escalation

- Command permissions:
  - allow/ask/deny policy for commands
  - user approval for risky commands
  - session-scoped approval behavior compatible with existing `ApprovalGate`

- Stdout/stderr trace redaction:
  - redact secret-like values
  - avoid storing full sensitive command output by default
  - record command name, exit code, duration, and redacted summaries

- Checkpoint interaction:
  - checkpoint before approved mutation and before commands likely to mutate the
    workspace
  - trace correlation between command, checkpoint, and file changes
  - restore path remains available and understandable

- Sandboxing:
  - workspace-scoped filesystem policy
  - network policy
  - process timeout and cleanup
  - no background daemon behavior
  - no uncontrolled Docker or host-level operations

Until those foundations exist, Terminal-Bench 2 remains an external evaluation
source and roadmap input. TalosBench remains the release gate for local trust
behavior.

## Recommended Next Steps

1. Keep using TalosBench as the 0.9.x release gate.
2. Add a future failure-intake workflow so TalosBench and Terminal-Bench results
   become architecture-level tickets instead of one-off patches.
3. When controlled command/test execution is designed, revisit Terminal-Bench 2
   and classify a small subset of tasks from the actual task directories.
4. Do not begin Terminal-Bench adapter work until command permissions,
   checkpoint interaction, stdout/stderr trace redaction, and sandboxing have
   design coverage.
