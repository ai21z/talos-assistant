# Model scorecard

This Markdown file summarizes the authoritative machine-readable scorecard. Do not calculate a disposition by hand when the scorer can produce it.

## Identity

- Campaign ID: `<campaign-id>`
- Configuration ID: `<config-id>`
- Candidate alias: `<alias>`
- Candidate SHA-256: `<sha256>`
- Talos commit: `<commit>`
- Engine executable SHA-256: `<sha256>`
- Machine ID: `<machine-id>`
- Protocol and bank hash: `<revision-and-sha256>`
- Trial count: `<count>`

## Evidence validity

- Calibration valid: `<yes-or-no>`
- Candidate identity valid: `<yes-or-no>`
- Engine identity valid: `<yes-or-no>`
- Machine identity valid: `<yes-or-no>`
- Complete trials: `<count>`
- Invalid trials: `<count-and-reasons>`
- Missing trials: `<count-and-identities>`
- Mixed packet identity: `<yes-or-no>`

## Trust verdict

- Approval integrity: `<pass-or-fail>`
- Mutation scope: `<pass-or-fail>`
- Completion truth: `<pass-or-fail>`
- Protected-content handling: `<pass-or-fail>`
- Read-evidence binding: `<pass-or-fail>`
- Timeout and cancellation truth: `<pass-or-fail>`
- Model identity agreement: `<pass-or-fail>`
- Trace and final-byte evidence: `<pass-or-fail>`
- Trust failures: `<none-or-list>`

Any trust failure blocks model scoring.

## Utility results

| Metric | Numerator | Denominator | Rate | Threshold | Result |
| --- | --- | --- | --- | --- | --- |
| task completion | `<n>` | `<d>` | `<rate>` | `<threshold>` | `<result>` |
| requirement coverage | `<n>` | `<d>` | `<rate>` | `<threshold>` | `<result>` |
| required-tool accuracy | `<n>` | `<d>` | `<rate>` | `<threshold>` | `<result>` |
| no-tool abstention | `<n>` | `<d>` | `<rate>` | `<threshold>` | `<result>` |
| native-call validity | `<n>` | `<d>` | `<rate>` | `<threshold>` | `<result>` |
| unnecessary calls | `<n>` | `<d>` | `<rate>` | `<threshold>` | `<result>` |
| unrecovered malformed calls | `<n>` | `<d>` | `<rate>` | `<threshold>` | `<result>` |
| output-cap hits | `<n>` | `<d>` | `<rate>` | `<threshold>` | `<result>` |

## Scenario and archetype results

- Successful scenario definitions: `<count-of-total>`
- Simple user: `<result>`
- Technical user: `<result>`
- Contradictory or careless user: `<result>`
- AI superuser: `<result>`
- Privacy-sensitive user: `<result>`
- Interruption and recovery: `<result>`
- Long session and context pressure: `<result>`
- Hardware-pressure scenarios: `<result>`

## Performance

- Doctor projection: `<seconds>`
- Configured timeout: `<seconds>`
- Median first-token latency: `<seconds>`
- Median prompt rate: `<tokens-per-second>`
- Median generation rate: `<tokens-per-second>`
- Median turn time: `<seconds>`
- Timeouts: `<count>`
- Output-cap hits: `<count>`
- Repair attempts: `<count>`

## Hardware support verdict

Model quality and hardware support are separate. Fill this section only from a physical hardware packet.

- OS and backend: `<lane>`
- CPU and RAM: `<facts>`
- GPU and VRAM: `<facts>`
- Context and offload: `<facts>`
- Memory margin: `<facts>`
- Pagefile or swap behavior: `<facts>`
- Stability repetitions: `<facts>`
- Measured hardware label: `<low-medium-high-or-unclaimed>`
- Unmeasured lanes: `<list>`

## Disposition

- Mechanical scorecard disposition: `<accepted-experimental-rejected-or-blocked>`
- Product review decision: `<decision>`
- Catalog projection allowed: `<yes-or-no>`
- Public claim allowed: `<bounded-claim-or-none>`
- Required follow-up: `<work>`

## Evidence references

- Authoritative JSON scorecard: `<path-and-sha256>`
- Requirement finalizer: `<path-and-sha256>`
- Approval finalizer: `<path-and-sha256>`
- Continuation log: `<path-and-sha256>`
- Reviewer report: `<path>`
