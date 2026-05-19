# [T328-open-high] Source evidence files must not be required mutation targets

## Status

Open.

## Severity

High. This blocks common source-to-output workflows and causes false blocked outcomes after successful mutations.

## Finding

Strict five-scenario T61-style audit rerun on 2026-05-19 found the same root bug in Office, web, and Python scenarios:

```text
Named source/evidence files were treated as expected mutation targets.
```

Examples:

```text
Create office-summary.md summarizing board-brief.pdf, client-notes.docx, and revenue.xlsx.
```

Talos treated the PDF/DOCX/XLSX sources as mutation targets and refused the workflow as unsupported binary creation.

```text
Create exactly index.html, style.css, and script.js according to site_brief.md.
```

Talos wrote the three requested outputs, then reported blocked because `site_brief.md` remained an expected target.

```text
Create dijkstra.py and test_dijkstra.py according to problem.md.
```

Talos treated `problem.md` as the expected target and rejected `dijkstra.py` as outside the expected target set.

## Evidence

```text
Branch: v0.9.0-beta-dev
Commit: ec69415
Version: 0.9.9
Audit root: local/manual-testing/t61-style-five-scenario-rerun-20260519-verify
Office transcript: audit-02-office-documents/TRANSCRIPT.txt
Web transcript: audit-03-web-synthwave/TRANSCRIPT.txt
Python transcript: audit-04-python-algorithm/TRANSCRIPT.txt
```

## Expected Invariant

Talos must distinguish:

```text
source evidence target: a file to inspect/read/use as input
mutation output target: a file to create/edit/delete/move/rename
```

Phrases such as:

```text
according to <file>
based on <file>
summarizing <file>
from <file>
using <file> as the brief/problem/source
```

should normally classify the named file as evidence, not a required mutation target.

## Recommended Fix

Refactor expected-target extraction so it returns typed target roles:

```java
enum TargetRole {
    SOURCE_EVIDENCE,
    MUTATION_OUTPUT,
    POSSIBLE_MUTATION_SUBJECT
}
```

The mutation allowlist should use only mutation-output and explicit mutation-subject targets. The evidence planner should use source-evidence targets for inspection obligations.

## Regression Tests

```text
TaskTargetExtractionTest.createMarkdownSummaryFromDocumentsSeparatesSourcesFromOutput
TaskTargetExtractionTest.createStaticSiteFromBriefDoesNotRequireBriefMutation
TaskTargetExtractionTest.createCodeAndTestsFromProblemStatementUsesRequestedOutputTargets
ToolCallExecutionStageTargetProgressTest.createdRequestedFilesSatisfyActionObligation
```

## Blockers

Need a code pass through task classification, expected-target extraction, and action-obligation progress tracking to identify the smallest typed-target seam.

