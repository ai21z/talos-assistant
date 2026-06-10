# Release Gate Ledger (GATES.json) — Schema v1

T749. Every release packet report gains a machine-checkable verdict layer:
a `*GATES.json` file beside the prose report under `work-cycle-docs/reports/`.
The prose explains; the ledger is what tooling (and future Talos gates) checks.
`dev.talos.docs.GatesLedgerTest` validates every ledger in the repo.

## Rules

- `sha` MUST be captured from `git rev-parse` (tooling), never typed by hand —
  the 0.10.1 packet shipped a hand-extended invalid SHA that survived until a
  forensic pass.
- One ledger per packet; filename `<packet-report-name>-GATES.json` or
  `GATES.json` inside a packet-named directory.
- A gate whose evidence has been superseded keeps its row with status
  `SUPERSEDED` and a pointer in `notes` — rows are append-only history.

## Schema

```json
{
  "schema": "talos.releaseGates.v1",
  "packet": "<packet id, e.g. current-0.10.1-release-packet-20260610-090049>",
  "branch": "<git branch>",
  "sha": "<40-hex commit sha from git rev-parse>",
  "generated": "<ISO-8601 timestamp>",
  "gates": [
    {
      "name": "<gate identifier>",
      "lane": "SAFE_REDIRECTED_STDIN | SYNC_APPROVAL | SYNC_APPROVAL_WORKSPACE_OPS | TRUE_PTY_MANUAL | CAPABILITY_PRIVATE_MODE | DETERMINISTIC | STATIC_ANALYSIS",
      "status": "PASS | FAIL_REVIEW_REQUIRED | MANUAL_REQUIRED | NOT_RUN | SUPERSEDED",
      "evidencePath": "<repo-relative or local/ path to the evidence root>",
      "model": "<optional model identity>",
      "notes": "<optional caveats - qualified passes, rescue counts, staleness>",
      "expires": "<optional ISO date after which the row must be re-proven>"
    }
  ]
}
```

Required top-level fields: `schema`, `packet`, `branch`, `sha`, `generated`,
`gates` (non-empty). Required per gate: `name`, `lane`, `status`,
`evidencePath`. Status and lane values are closed vocabularies (above).
