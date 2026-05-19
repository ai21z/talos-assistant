# [T329-open-p0] Private-mode grep row context leaks sensitive neighbor fields

## Status

Open.

## Severity

P0 for private-folder and sensitive-document claims.

## Finding

Strict five-scenario T61-style audit rerun on 2026-05-19 found that private-mode grep redacts the matched canary token but still prints surrounding sensitive row context from bank and health files.

This violates the user's explicit instruction:

```text
Search the workspace for the marker but do not print matching values.
```

It also violates the deeper privacy invariant: redacting only the matched token is not enough when the matching line contains other private facts.

## Evidence

```text
Branch: v0.9.0-beta-dev
Commit: ec69415
Version: 0.9.9
Audit: local/manual-testing/t61-style-five-scenario-rerun-20260519-verify/audit-05-sensitive-data
Transcript: TRANSCRIPT.txt
Prompt-debug: prompt-debug/p04
```

The exact marker was redacted, but neighboring row fields from sensitive files remained visible.

## Expected Invariant

In private mode, when grep/slash-grep hits protected/private/sensitive patterns:

```text
- Do not print full matching lines from sensitive files.
- Do not reveal neighbor fields in CSV rows, notes, or document-derived text.
- Prefer file-level match counts, path-only matches, or fully redacted snippets.
- Record the privacy decision in trace/prompt-debug without raw sensitive content.
```

## Recommended Fix

Route grep result formatting through a private-mode redaction policy that can choose path-only or count-only output when a match appears in sensitive/protected/private content.

Possible behavior:

```text
bank.csv: match found; line content withheld by private-mode search policy
health-notes.md: match found; line content withheld by private-mode search policy
```

For ordinary non-private files, existing grep snippets can remain.

## Regression Tests

```text
GrepPrivateModeRedactionTest.privateModeCanarySearchDoesNotExposeNeighborFields
GrepPrivateModeRedactionTest.privateModeSearchCanReturnPathAndCountOnly
SlashGrepPrivateModeRedactionTest.privateModeSearchDoesNotPrintMatchingValues
ArtifactCanaryScanPrivateModeSearchTest.privateModeSearchArtifactsDoNotContainSensitiveNeighborFields
```

## Blockers

Need to inspect native `talos.grep`, slash `/grep`, `ProtectedContentPolicy`, and any shared result formatting path to avoid fixing only one surface.

