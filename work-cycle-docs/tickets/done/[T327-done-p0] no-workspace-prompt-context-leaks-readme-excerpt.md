# [T327-open-p0] No-workspace prompt context leaks README excerpt

## Status

Done.

## Severity

P0 release blocker for simple-user/privacy claims.

## Finding

Strict five-scenario T61-style audit rerun on 2026-05-19 showed that no-workspace/general prompts can still receive workspace context in the provider body. A README excerpt containing a deliberate workspace canary was included in prompt-debug/provider-body artifacts during a general chat/science workflow.

This is not just a model over-inspection problem. The leak is introduced by runtime prompt construction before any tool call.

## Evidence

```text
Branch: v0.9.0-beta-dev
Commit: ec69415
Version: 0.9.9
Audit: local/manual-testing/t61-style-five-scenario-rerun-20260519-verify/audit-01-chat-general
Artifacts: prompt-debug/p05 provider body and transcript
```

The prompt asked for general/non-workspace behavior. Prompt-debug evidence still included workspace file structure and README excerpt.

## Expected Invariant

For no-workspace/general/direct-answer turns:

```text
- Do not inject README excerpts.
- Do not inject workspace file structure.
- Do not inject RAG snippets.
- Do not inject workspace memory.
- Do not expose workspace read/retrieve tools unless the user asks about workspace facts.
- Do not include workspace canaries in provider-body or prompt-debug artifacts.
```

## Recommended Fix

Add an explicit no-workspace/general prompt-minimization path. Task classification should treat explicit "do not inspect/read/use this workspace" language as a hard constraint unless the prompt asks a workspace-fact question.

Prompt assembly should be gated by the task contract:

```text
general/no-workspace -> minimal system + user prompt, no workspace context
workspace factual -> workspace context allowed according to policy
workspace mutation -> workspace context and tool surface allowed according to policy
```

## Regression Tests

```text
NoWorkspacePromptMinimizationTest.generalKnowledgeDoesNotInjectWorkspaceReadmeExcerpt
NoWorkspacePromptMinimizationTest.explicitDoNotInspectWorkspaceSuppressesWorkspaceContext
TaskClassifierNoWorkspaceIntentTest.generalScienceDoNotInspectWorkspaceUsesNoTools
```

Fixtures should include a README canary and assert:

```text
- no tool calls
- no retrieval
- provider body lacks the README canary
- prompt-debug markdown lacks the README canary
```

## Blockers

Need to locate the prompt assembly path that injects workspace file structure/README excerpt independently of tool calls.

## Resolution

Implemented before ticket reconciliation on 2026-05-20.

Evidence:

```powershell
.\gradlew.bat test --tests "dev.talos.cli.modes.UnifiedAssistantModeTest.explicitNoWorkspaceGeneralKnowledgePromptDoesNotInjectWorkspaceManifest" --tests "dev.talos.core.llm.SystemPromptBuilderWorkspaceManifestTest.noWorkspaceNoManifest" --no-daemon
```

The focused regression confirms explicit no-workspace/general prompts avoid workspace manifest injection.
