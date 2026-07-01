# T143 - Broader Product Workflow Audit

Severity: medium
Status: done

## Problem

Talos now has a broader backend-neutral product surface: managed llama.cpp,
runtime-owned tool surfaces, workspace operation tools, batch workspace apply,
static web verification/repair, protected-read postconditions, and bounded
Gradle command profiles.

Before adding more tools or broadening command profiles, we need a two-model
product/workflow audit that tests these capabilities together in realistic
developer-workspace tasks.

## Scope

- Rebuild/install Talos from `v0.9.0-beta-dev`.
- Run a clean managed llama.cpp audit with:
  - Qwen coder 14B.
  - GPT-OSS 20B.
- Use fresh manual-testing and manual-workspaces directories.
- Use separate workspaces and isolated Talos homes per model.
- Capture prompts, transcripts, runner logs, traces, and prompt-debug artifacts.
- Exercise existing product workflows:
  - workspace inspection and grounded read-only answer;
  - Markdown artifact creation;
  - folder creation;
  - path copy, move, and rename;
  - batch workspace apply;
  - static web bug repair with verification;
  - bounded Gradle command execution through existing V1 profiles;
  - unsupported binary document honesty;
  - protected `.env` read behavior;
  - unsupported delete capability containment.

## Acceptance

- Findings distinguish runtime bug, model weakness, product gap, and correct
  containment.
- Findings identify whether current workspace-operation and command surfaces
  are ready for broader workflow use.
- Any new implementation work is split into follow-up tickets rather than
  patched inside the audit ticket.
- No broad command profile expansion is performed.
- No full T61-style audit is started from this ticket.

## Non-Goals

- No new tools.
- No command-profile expansion.
- No delete-path implementation.
- No generic shell support.
- No architecture refactor unless a blocker is found and ticketed separately.

## Verification

- `.\gradlew.bat --no-daemon build installDist`
- Focused two-model managed llama.cpp audit artifacts.
- Findings report with go/no-go recommendation for broader workflow use.

## Result

Completed the product workflow audit with managed llama.cpp:

- `local/manual-testing/llama-cpp-product-workflow-audit-20260505-120139/`
- `local/manual-testing/llama-cpp-product-workflow-audit-20260505-120139/FINDINGS-LLAMA-CPP-PRODUCT-WORKFLOW-AUDIT.md`

The existing Gradle command profile path passed again on both models.
Unsupported binary document handling and unsupported delete containment also
worked safely.

The broader workspace-operation surface is not ready for a larger T61-style
audit yet. The audit produced follow-up tickets:

- T144 - Negated Protected Path Evidence Obligation.
- T145 - Directory Create Expected-Target Scope.
- T146 - Workspace Operation Verification For Organize And Batch Tools.
- T147 - Explicit Batch Workspace Apply Intent Classification.
- T148 - Protected Read Success After Failed Path Variant.

The next implementation batch should start with T144 and T145 before rerunning
this same product workflow audit.
