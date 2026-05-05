# T141 - Explicit Command Intent Classification

Severity: high
Status: done

## Problem

The focused llama.cpp command audit showed that explicit command probe turns can be
misclassified as `WORKSPACE_EXPLAIN` or `READ_ONLY_QA` when they include wording
like "probe", "report the runtime result", or "do not edit files".

That removes `talos.run_command` from the visible tool surface even when the user
explicitly asks for `talos.run_command`, `profile gradle_test`, `args_json`,
`cwd`, or `timeout_ms`.

## Scope

- Treat explicit command execution intent as a verification-command task.
- Keep mutation disabled.
- Expose `talos.run_command` for explicit command requests even when the user says
  not to edit files.
- Keep ordinary read-only advisory questions read-only.

## Acceptance

- `TaskContractResolver` classifies explicit `talos.run_command` / Gradle profile
  probe requests as `VERIFY_ONLY`.
- The command verification surface includes `talos.run_command` for those turns.
- Focused tests cover raw-shell denial, cwd escape, timeout, and output-cap probe
  wording from the audit.
- Existing read-only/no-edit classification tests still pass.

## Non-Goals

- No broader command profile expansion.
- No raw shell support.
- No command execution without approval.
- No model-specific prompt wording patch.

## Verification

- Focused resolver and tool-surface tests.
- `./gradlew.bat --no-daemon build installDist`.
- Focused command re-audit after implementation.

## Result

Implemented in `TaskContractResolver` with focused tests in:

- `src/test/java/dev/talos/runtime/task/TaskContractResolverTest.java`
- `src/test/java/dev/talos/runtime/toolcall/ToolSurfacePlannerTest.java`

Re-audit artifacts:

- `local/manual-testing/llama-cpp-command-reaudit-20260505-110222/`
- `local/manual-testing/llama-cpp-command-reaudit-20260505-110222/FINDINGS-LLAMA-CPP-COMMAND-REAUDIT.md`

The re-audit confirmed explicit command probe turns keep `talos.run_command`
visible for Qwen and GPT-OSS. Cwd escape, timeout, and output-cap/redaction
runtime paths were exercised on both models. GPT-OSS also exercised raw-shell
denial directly; Qwen used valid command calls on that adversarial prompt, which
is recorded as a model-compliance caveat rather than a remaining classifier bug.
