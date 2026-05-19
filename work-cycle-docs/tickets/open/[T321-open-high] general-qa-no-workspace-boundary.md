# T321 - General QA No-Workspace Boundary

Severity: High

Status: Open

Source: Five scenario big audit, 2026-05-19

## Problem

Ordinary non-workspace questions can still expose workspace tools and trigger retrieval/indexing.

The live audit reproduced this with a general science prompt that explicitly said not to inspect the workspace. Talos classified it as workspace inspection and called retrieval.

## Evidence

Local transcript:

```text
local/manual-testing/five-scenario-audit-20260519-221645/20260519-221816/five-chat-general-boundary.txt
```

Static audit evidence:

- `ConversationBoundaryPolicy` only handles a narrow set of direct chat and no-workspace phrases.
- `TaskContractResolver` falls through to `READ_ONLY_QA` or `DIAGNOSE_ONLY` for many general questions.
- `ToolSurfacePlanner` exposes read/retrieve tools for `READ_ONLY_QA`.

## Expected Behavior

Prompts such as these should be direct answer / no tools:

```text
Explain photosynthesis simply. Do not inspect this workspace.
Explain quantum entanglement simply.
What is a binary tree?
I am overwhelmed at work; help me make a 30-minute plan without reading local files.
```

Expected invariants:

- no native tools
- no prompt tools
- no workspace manifest or README excerpt
- no RAG indexing
- no retrieval
- no workspace-derived active task context
- final answer does not cite or imply workspace inspection

## Regression Tests

Add task-contract and prompt-construction tests:

```text
generalScienceQuestionWithNoWorkspaceUsesDirectAnswerOnly
workAdviceWithoutFilesUsesDirectAnswerOnly
generalDataStructureQuestionUsesDirectAnswerOnly
noWorkspaceGeneralQaSuppressesRetrieveTool
priorWorkspaceHistorySuppressedWhenUserSaysJustChat
```

## Fix Direction

Add a deterministic `GENERAL_QA` contract or extend direct-answer classification so ordinary non-workspace knowledge/work/life/science prompts do not enter the workspace tool loop.

Do not make every unknown prompt direct-answer. Workspace questions must still inspect evidence.

