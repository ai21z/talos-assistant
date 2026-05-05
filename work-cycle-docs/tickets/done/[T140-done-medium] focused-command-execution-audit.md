# T140 - Focused Command Execution Audit

Severity: medium
Status: done

## Problem

After T135-T139, command execution needs a focused clean audit before any larger
T61-style audit or broader command profile expansion.

## Scope

- Rebuild/install Talos.
- Run a focused clean audit with Qwen coder 14b and GPT-OSS 20b.
- Probe approved Gradle command execution, approval denial, shell denial,
  workspace escape denial, timeout behavior, output caps, and failure-dominant
  command output.
- Save prompts, outputs, runner logs, traces, and findings.

## Acceptance

- Audit artifacts are saved under a new clean manual-testing directory.
- Findings distinguish runtime bug vs model weakness.
- Findings decide whether command execution is ready for broader profiles.
- No full T61-style audit starts before this focused audit is reviewed.

## Non-Goals

- No new implementation during the audit ticket unless it creates follow-up
  tickets.
- No broad command profile expansion.

## Verification

- Clean two-model focused audit artifacts.
- Findings report with go/no-go recommendation.

## Result

Completed in:

- `local/manual-testing/llama-cpp-command-audit-20260505-104828/`
- `local/manual-testing/llama-cpp-command-audit-20260505-104828/FINDINGS-LLAMA-CPP-COMMAND-AUDIT.md`

The audit confirmed T139's command success, failure, approval-denial, tracing,
redaction, and output dominance paths. It also found a separate classification
bug where explicit command probe turns could lose `talos.run_command`; that was
split into T141.
