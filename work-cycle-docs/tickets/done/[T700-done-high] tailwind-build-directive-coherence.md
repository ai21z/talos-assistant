# T700 - Tailwind Build Directive Coherence

Status: done
Severity: high

## Problem

T698 left GPT-OSS final `style.css` with a Tailwind `@apply` directive in a plain static CSS file:

```css
button {
    @apply focus:outline-none focus:ring-2 focus:ring-pink-300;
}
```

There was no Tailwind build path and no accepted Tailwind browser runtime path for processing that CSS file. The deterministic verifier currently detects `@tailwind base`, `@tailwind components`, and `@tailwind utilities`, but not `@apply`.

Official Tailwind documentation describes `@apply` as a Tailwind directive and distinguishes browser Play CDN usage from CLI/build-generated CSS. That means `@apply` in linked plain CSS is build-required evidence unless Talos can prove a valid Tailwind build/runtime path.

## Evidence

- Audit root:
  `local/TalosTestOUTPUT/test02-11-post-t697-t698-sync-audit-20260606-131440/`
- Final file:
  `workspaces/gptoss/style.css`
- Source:
  - `src/main/java/dev/talos/runtime/verification/StaticWebTailwindCoherenceVerifier.java`
  - `containsTailwindDirective(...)` checks only:
    - `@tailwind base`
    - `@tailwind components`
    - `@tailwind utilities`
- Tailwind docs:
  - Functions/directives docs list `@apply` as a Tailwind directive.
  - Play CDN docs state browser runtime usage requires adding the Play CDN script.
  - CLI docs describe generating a static CSS output through the CLI build process.

## Architecture Metadata

- Capability ownership: static-web verifier / frontend framework asset coherence.
- Operation type: post-apply verification.
- Risk: high. Plain static pages with unprocessed framework directives can look written but not work in the browser.
- Approval behavior: unchanged.
- Protected path behavior: unchanged.
- Checkpoint behavior: unchanged.
- Evidence obligation: verifier facts/problems must name the offending directive and required runtime/build evidence.
- Verification profile: `STATIC_WEB`.
- Repair profile: repair should target `index.html`, linked local CSS, linked JS, and expected static-web targets, not local Tailwind artifacts.
- Outcome/trace changes: no false `COMPLETED_VERIFIED`; unprocessed build directives must fail or downgrade.
- Allowed refactor scope: `StaticWebTailwindCoherenceVerifier`, related framework asset helper tests, and static-web verifier tests.

## Acceptance

- Linked local CSS containing `@apply` fails static-web verification when there is no accepted Tailwind runtime, build config, or generated CSS evidence.
- Linked local CSS containing build-only Tailwind directives fails with a clear problem message naming the directive class.
- Valid Tailwind Play CDN script remains accepted for browser-runtime local demo usage, with remote/runtime limitation wording where appropriate.
- Valid build/generated CSS remains accepted without requiring Play CDN.
- Remote Tailwind CSS hrefs still do not become local missing-file obligations.
- Repair targets map back to writable site files, not `tailwind.css` or `tailwind.min.css`.

## Tests

- `StaticTaskVerifierTest`: `@apply` in linked `style.css` without build/runtime fails.
- `StaticTaskVerifierTest`: valid Play CDN script with Tailwind utility classes passes the Tailwind coherence lane.
- `StaticTaskVerifierTest`: valid generated CSS passes without CDN.
- `StaticTaskVerifierTest`: remote Tailwind CSS href remains a remote limitation/problem, not a missing local `tailwind.min.css`.
- `RepairPolicyTest`: Tailwind build-directive problems repair `index.html`/linked CSS/linked JS/expected targets, not forbidden local Tailwind artifacts.

## Completion Evidence

Implemented with RED/GREEN coverage:

- Added `StaticTaskVerifierTest.staticWebVerificationFailsTailwindApplyDirectiveWithoutRuntimeOrBuild`.
- RED run failed before implementation:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.verification.StaticTaskVerifierTest.staticWebVerificationFailsTailwindApplyDirectiveWithoutRuntimeOrBuild" --no-daemon
```

- `StaticWebTailwindCoherenceVerifier` now reports the specific Tailwind directive set, including `@apply`, and also recognizes current Tailwind build directives such as `@theme`, `@source`, `@utility`, `@variant`, `@custom-variant`, `@reference`, `@config`, `@plugin`, and `@import "tailwindcss"`.
- GREEN verification:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.verification.StaticTaskVerifierTest.staticWebVerificationFailsTailwindApplyDirectiveWithoutRuntimeOrBuild" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.verification.StaticTaskVerifierTest" --tests "dev.talos.runtime.repair.RepairPolicyTest" --no-daemon
```

Both GREEN commands passed.

## Non-Goals

- Do not add a full CSS compiler.
- Do not add browser render verification.
- Do not reject ordinary CSS at-rules unrelated to frontend framework build directives.
