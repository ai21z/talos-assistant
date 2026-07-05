# CI/CD

The main CI workflow runs on `main` and `v0.9.0-beta-dev`. It verifies Java 21 build and test behavior before release staging.

CI is a guardrail, not a release decision by itself. A green workflow means the automated checks passed for that SHA. Release readiness still requires staged artifacts, installed-product smoke, manual PTY evidence, and any model audit lanes named by the release scope.

Release staging is manual. It uses `workflow_dispatch` with an exact `target_sha` and version. The workflow checks out the requested SHA, rejects dirty staging, builds Windows and Linux QA staging artifacts, writes manifests and checksums, validates SBOM files, and attaches GitHub attestations.

No tag, push, pull-request, or release event may publish public artifacts.

Site staging is also manual. It builds the Vite site, runs static and deploy-surface checks, and deploys the named Cloudflare Pages preview project only.

## Workflow boundaries

- Normal CI should fail fast on tests, lint contracts, architecture guards, and portability checks.
- Release staging should build QA artifacts and attach provenance metadata, but not publish release assets.
- Site staging should deploy the website preview only after static checks pass.
- Public release publication should remain a separate intentional step.
