---
wiki_schema: talos.wikiPage.v1
title: "Talos Wiki Evidence Registry"
kind: registry
status: active
last_verified_commit: "01431aa3a4ad4ac86bf0356a63d574aa2bfe1a07"
evidence_inputs:
  - type: ticket
    ref: "work-cycle-docs/tickets/done/[T810-done-high] living-wiki-operating-loop-and-close-gate.md"
    selector: "Evidence registry scope"
  - type: generated_report
    ref: "build/reports/talos/architecture-intelligence/current/data/run-manifest.json"
    selector: "active generated architecture report evidence"
min_confidence: INFERRED_REVIEW
confidence_histogram:
  UNKNOWN: 0
  INFERRED_REVIEW: 1
  DETERMINISTIC_STATIC: 2
  DETERMINISTIC_GENERATED: 0
  OBSERVED_RUNTIME: 0
  GATED: 0
---

# Talos Wiki Evidence Registry

This registry gives wiki claim blocks stable evidence IDs so pages do not
duplicate generated report paths. Active entries are limited to evidence that
the wiki close gate can regenerate.

```talos-wiki-evidence-registry
{
  "schema": "talos.wikiEvidenceRegistry.v1",
  "entries": [
    {
      "id": "architectureIntelligence.runManifest",
      "type": "generated_report",
      "trustTier": "GENERATED_REPORT",
      "path": "build/reports/talos/architecture-intelligence/current/data/run-manifest.json",
      "description": "Run identity, schema marker, report paths, JSON data paths, branch, commit, and Talos version for the generated architecture intelligence suite."
    },
    {
      "id": "architectureIntelligence.wave5Sequence",
      "type": "generated_report",
      "trustTier": "GENERATED_REPORT",
      "path": "build/reports/talos/architecture-intelligence/current/data/wave5-sequence-recommendations.json",
      "description": "Machine-readable Wave 5 priority-index model and recommended architecture work sequence."
    }
  ]
}
```

## Deferred Evidence

The quality summary reports are intentionally not active registry entries yet:

- `quality.versionSummary`
- `quality.coverageSummary`
- `quality.e2eSummary`
- `quality.qodanaSummary`

Those files are produced by `talosQualitySummaries`, which runs after the
candidate `check` step in the current candidate script. Register them only when
their producer is part of the same evidence-liveness gate that validates the
claims.

`architectureIntelligence.identityFreshness` is also deferred because
`build/reports/talos/wiki-lint/current/identity-freshness.json` is produced by
the wiki evidence lint itself. A lint output must not become active input for
the same lint.

## Consumers

- [CURRENT-STATE.md](CURRENT-STATE.md) uses registry IDs for generated
  architecture report claims.
- [WIKI-SCHEMA.md](WIKI-SCHEMA.md) defines the registry schema and source trust
  tiers.
