# TalosBench Summary Template

Use this template when a TalosBench run needs a tracked, redacted summary.
Raw transcripts belong under `local/manual-testing/talosbench/` and should not
be committed by default.

## Run Metadata

- Date:
- Talos version:
- Branch:
- Commit:
- Model:
- Runner:
- Cases file:
- Transcript root:

## Results

| Case id | Status | Category | Blocker? | Transcript path | Notes |
| --- | --- | --- | --- | --- | --- |
| example-case | PASS | capability/onboarding | no | local/manual-testing/talosbench/... | Redacted summary only. |

## Blockers

- None recorded.

## Follow-Ups

- None recorded.

## Architecture Buckets

Map failures to the T49 taxonomy:

- `INTENT_BOUNDARY`
- `CURRENT_TURN_FRAME`
- `TOOL_SURFACE`
- `ACTION_OBLIGATION`
- `PERMISSION`
- `CHECKPOINT`
- `VERIFICATION`
- `OUTCOME_TRUTH`
- `TRACE_REDACTION`
- `REPAIR_CONTROL`
- `MODEL_COMPETENCE`
- `UNSUPPORTED_CAPABILITY`
- `AUDIT_DESIGN`

Use `AUDIT_DESIGN` when the fixture, prompt order, reset discipline, approval
script, or transcript capture made the result ambiguous. Do not convert an
audit-design failure into a product-runtime blocker unless a clean rerun
reproduces the behavior.

## Candidate Recommendation

State one:

- proceed to candidate closeout
- fix blockers before candidate closeout
- continue manual investigation
- unsupported benchmark signal only
