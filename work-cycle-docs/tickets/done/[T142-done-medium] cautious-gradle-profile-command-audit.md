# T142 - Cautious Gradle Profile Command Audit

Severity: medium
Status: done

## Problem

After T139-T141, Talos command execution has a working bounded Gradle profile
path. Before any broader command-profile expansion or larger T61-style audit, the
existing V1 Gradle command surface needs a cautious two-model audit.

## Scope

- Rebuild/install Talos.
- Run a focused clean audit with Qwen coder 14B and GPT-OSS 20B through managed
  llama.cpp.
- Use fresh manual-testing and manual-workspaces directories.
- Exercise the existing V1 Gradle profiles:
  - `gradle_test`
  - `gradle_check`
  - `gradle_build`
  - `gradle_install_dist`
  - `gradle_e2e_test`
- Probe policy boundaries:
  - disallowed Gradle args such as `clean`;
  - network-like Gradle args such as `--scan`;
  - non-Gradle diagnostic profile denial in V1.
- Save prompts, outputs, runner logs, traces, prompt debug captures, and findings.

## Acceptance

- Each Gradle V1 profile is either executed successfully with approval or a
  runtime-owned failure is reported.
- Rejected command requests are denied before approval.
- Findings distinguish runtime bug vs model weakness.
- Findings decide whether the existing Gradle command surface is ready for a
  broader audit.

## Non-Goals

- No broad command profile expansion.
- No diagnostic profile enablement.
- No raw shell support.
- No new implementation unless the audit exposes a real blocker.

## Verification

- Focused two-model audit artifacts.
- Findings report with go/no-go recommendation.

## Result

Completed the cautious two-model audit with managed llama.cpp:

- `local/manual-testing/llama-cpp-gradle-profile-audit-20260505-114441/`
- `local/manual-testing/llama-cpp-gradle-profile-audit-20260505-114441/FINDINGS-LLAMA-CPP-GRADLE-PROFILE-AUDIT.md`

Both Qwen coder 14B and GPT-OSS 20B executed all five existing Gradle V1
profiles with approval:

- `gradle_test`
- `gradle_check`
- `gradle_build`
- `gradle_install_dist`
- `gradle_e2e_test`

Both models denied the boundary probes before approval:

- `clean` as a destructive Gradle argument.
- `--scan` as a network-like Gradle argument.
- `git_status` because non-Gradle diagnostic profiles are not exposed through
  the current V1 command profile surface.

No runtime blocker was found. Qwen repeated the denied `git_status` call three
times in one turn, but every repeated call was contained before approval. This is
recorded as a possible future repeated-denial budget improvement, not a blocker
for the current Gradle command surface.
