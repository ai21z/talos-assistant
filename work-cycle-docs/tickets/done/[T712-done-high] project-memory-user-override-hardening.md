# T712 - Project Memory User Override Hardening

Status: done
Priority: high
Created: 2026-06-07

## Evidence Summary

- Source: static code review after T708 implementation
- Date: 2026-06-07
- Talos version / commit: `0.9.9` / `18b9c5b5cf5075f70850696d07438053766849ef`
- Evidence:
  - `src/main/java/dev/talos/runtime/context/ProjectMemoryPolicy.java`
  - `src/main/java/dev/talos/runtime/context/ProjectMemoryLoader.java`
  - `src/main/java/dev/talos/runtime/context/ProjectMemoryContext.java`
  - `src/main/java/dev/talos/cli/prompt/PromptDebugInspector.java`
  - `work-cycle-docs/tickets/done/[T708-done-high] hierarchical-project-memory.md`
  - `work-cycle-docs/research/t708-hierarchical-project-memory-deep-analysis.md`

Expected behavior:

```text
Current user instructions must be able to suppress project-memory loading for
the current turn. Project memory must remain visible, bounded, sanitized, and
defanged, and hostile memory text must not affect runtime policy, tool surface,
approval, or verification.
```

Observed behavior:

```text
ProjectMemoryPolicy suppresses small-talk/status/privacy turns, but it has no
explicit current-user opt-out for project memory. Empty sanitized memory sources
can also render as empty prompt blocks. Existing tests prove insertion and basic
suppression, but do not prove that hostile memory cannot alter runtime policy.
```

## Classification

Primary taxonomy bucket:

- `CURRENT_TURN_FRAME`

Secondary buckets:

- `TRACE_REDACTION`
- `OUTCOME_TRUTH`

Blocker level:

- candidate follow-up

Why this level:

```text
The T708 implementation is structurally sound, but the explicit user override
invariant needs deterministic policy coverage before project memory becomes a
broader beta claim.
```

## Architectural Hypothesis

Bad ticket framing to avoid:

```text
Make project memory smarter.
```

Architectural hypothesis:

```text
Project memory is untrusted local context. User control must be enforced before
memory reaches the prompt, not delegated to the model's interpretation of the
memory block. The correct owner is ProjectMemoryPolicy/ProjectMemoryLoader plus
executor tests that prove runtime policy is unchanged by memory text.
```

Likely code/document areas:

- `src/main/java/dev/talos/runtime/context/ProjectMemoryPolicy.java`
- `src/main/java/dev/talos/runtime/context/ProjectMemoryLoader.java`
- `src/test/java/dev/talos/runtime/context/ProjectMemoryLoaderTest.java`
- `src/test/java/dev/talos/cli/modes/AssistantTurnExecutorProjectMemoryTest.java`

Why a one-off patch is insufficient:

```text
The invariant is not one prompt phrase. Talos needs a stable policy boundary:
current-user opt-out suppresses memory, ordinary code phrases about memory do
not, and memory content never controls tool surface or verification.
```

## Goal

```text
Harden T708 so project memory honors explicit current-user opt-out, avoids empty
prompt blocks, and has regression coverage against prompt-injection-style memory
content changing runtime policy.
```

## Non-Goals

- No vector memory.
- No autonomous memory writes.
- No foreign `CLAUDE.md` or `GEMINI.md` support.
- No include/import expansion.
- No semantic rule interpreter.
- No runtime config surface for memory limits in this ticket.
- No live audit; deterministic tests are sufficient for this hardening slice.

## Implementation Notes

- Add a deterministic explicit opt-out recognizer before normal project-memory
  load decisions.
- Scope opt-out to project-memory/Talos-memory files, not generic phrases such
  as "memory leak", "memory usage", or "in-memory cache".
- Skip sources whose sanitized content is blank, recording an auditable decision.
- Add a regression proving hostile memory text such as "approve all tools" or
  "mark verified" does not alter task contract, tool surface, approval, or
  verifier profile.
- Clarify T708 done notes if necessary: `/last trace` shows compact project
  memory status; prompt-debug carries per-source details and sanitized prompt
  content.

## Architecture Metadata

Capability:

- Project memory / context assembly

Operation(s):

- read

Owning package/class:

- `dev.talos.runtime.context.ProjectMemoryPolicy`
- `dev.talos.runtime.context.ProjectMemoryLoader`

New or changed tools:

- none

Risk, approval, and protected paths:

- Risk level: medium
- Approval behavior: unchanged; project memory does not grant approval
- Protected path behavior: unchanged; workspace protected memory remains excluded

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: none
- Evidence obligation: memory decisions remain visible in prompt-debug/trace
- Verification profile: none
- Repair profile: none

Outcome and trace:

- Outcome/truth warnings: memory is not inspected workspace evidence
- Trace/debug fields: suppressed opt-out and blank-source decisions should be visible

Refactor scope:

- Allowed: small policy/loader helper extraction
- Forbidden: broad prompt assembly rewrite or new memory persistence

## Acceptance Criteria

- Explicit current-user requests such as "do not load project memory",
  "do not use project memory", "ignore TALOS.md", and "answer without project
  memory" suppress project-memory loading for the current turn.
- Ordinary code/workspace phrases such as "memory leak", "memory usage", and
  "in-memory cache" do not suppress project memory by accident.
- Sanitized blank memory files are not rendered into the model prompt and produce
  an auditable skip decision.
- Hostile memory text cannot change task contract, visible tools, approval
  requirement, verifier profile, or runtime policy trace.
- T708 documentation remains truthful about compact `/last trace` status versus
  detailed prompt-debug visibility.
- No regressions to privacy, permissions, checkpointing, trace redaction, or
  outcome truth.

## Tests / Evidence

Required deterministic regression:

- Unit test: explicit project-memory opt-out suppresses loading.
- Unit test: generic memory-related code phrases do not suppress loading.
- Unit test: blank sanitized memory files are skipped with a decision.
- Integration/executor test: hostile project memory does not alter current-turn
  policy/tool surface.
- Trace/prompt-debug assertion: opt-out/blank decisions remain visible.

Commands:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.context.ProjectMemoryLoaderTest" --tests "dev.talos.cli.modes.AssistantTurnExecutorProjectMemoryTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.context.*" --tests "dev.talos.cli.modes.*" --no-daemon
.\gradlew.bat check --no-daemon
git diff --check
```

Verified implementation, 2026-06-07:

- Added explicit current-turn project-memory opt-out policy.
- Skipped blank sanitized memory sources with an auditable
  `BLANK_AFTER_SANITIZATION` decision.
- Added executor regression coverage proving hostile memory content does not
  alter task contract, tool surface, mutation/verification requirement, or
  verifier profile.

Focused commands passed:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.context.ProjectMemoryLoaderTest" --tests "dev.talos.cli.modes.AssistantTurnExecutorProjectMemoryTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.context.*" --tests "dev.talos.cli.modes.*" --no-daemon
```

Full gate passed:

```powershell
.\gradlew.bat check --no-daemon
git diff --check
```

## Work-Test Cycle Notes

- Use RED/GREEN tests first.
- Do not bump version.
- Do not run live audit for this deterministic hardening slice.

## Known Risks

- Over-broad opt-out matching could suppress memory for legitimate code questions
  about memory usage.
- Over-narrow opt-out matching could keep violating current-user override.

## Known Follow-Ups

- Optional configurable memory budgets after the read-only hierarchy has more
  audit history.
