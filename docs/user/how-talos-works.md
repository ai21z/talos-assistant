# How Talos Works

This page answers: "What is the basic execution contract?"

## Current Support

Talos is not a general chatbot. It is a local workspace operator with governed
tools. The expected turn order is:

```text
inspect -> retrieve when useful -> ask before mutation -> apply approved action -> verify -> leave evidence
```

## Inspect Before Acting

The intended turn discipline is to inspect relevant local evidence before
making claims about a workspace.

Good prompts:

```text
Explain the project structure. Cite the files you inspect.
Find the files related to the failing test. Do not edit yet.
```

Weak prompt:

```text
Guess the architecture without reading files.
```

## Retrieve Before Guessing

For larger workspaces, Talos can use retrieval from the local index. Retrieval
is useful for broad questions, but it is not a substitute for direct reads when
exact file content matters.

## Ask Before Mutation

Writes, edits, destructive operations, and command execution are governed. Talos
must show an approval prompt before approved-risk operations proceed.

See [Approvals And Permissions](approvals-and-permissions.md).

## Verify Before Claiming Success

A valid Talos result does not treat "file edited" as proof that the task
worked. Verification comes from file reads, command output, test/build results,
or another available evidence source.

Good prompt:

```text
Fix this test and run the relevant check before saying it is fixed.
```

## Leave Evidence

Useful evidence commands:

```text
/status
/status --verbose
/last trace
```

Debug and prompt capture commands exist for development and audits, but normal
users start with `/status` and `/last trace`.

## Failure Is A Valid Outcome

A valid Talos result reports when it cannot inspect a file, cannot run a
command, cannot verify a claim, or needs approval that was denied.

A truthful failure is better than a polished unsupported answer.
