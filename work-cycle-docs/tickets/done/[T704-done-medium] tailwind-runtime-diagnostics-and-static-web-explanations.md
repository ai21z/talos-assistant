# T704 - Tailwind Runtime Diagnostics And Static-Web Explanations

Status: done
Priority: medium
Created: 2026-06-06

## Problem

The `test02-12` audit confirmed that remote Tailwind stylesheet links are no longer treated as missing local files, but the diagnostic wording remains imprecise. A page with:

```html
<link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/tailwindcss@2.2.19/dist/tailwind.min.css">
```

was reported with wording equivalent to "Tailwind utility classes are used but no Tailwind CDN, generated CSS, or Tailwind build configuration was found." That is directionally correct as a failure, but misleading: a remote Tailwind CSS asset existed; it was just not an accepted Tailwind browser runtime or local build path.

The explanation-only response later repeated the same imprecision.

## Code Evidence

- `StaticWebTailwindCoherenceVerifier` accepts Tailwind browser runtime only through accepted script runtime paths, including `cdn.tailwindcss.com` and `@tailwindcss/browser`: `src/main/java/dev/talos/runtime/verification/StaticWebTailwindCoherenceVerifier.java`.
- The verifier intentionally does not accept arbitrary remote `tailwind.min.css` stylesheet hrefs as a complete Tailwind runtime.
- Explanation-only paths can surface verifier wording without sharpening the distinction between unsupported remote stylesheet and absent runtime.

## Acceptance Criteria

- Remote Tailwind stylesheet hrefs are reported as remote stylesheet assets that are not accepted Tailwind browser runtime/build evidence.
- The wording must not imply no Tailwind URL existed when an unsupported remote Tailwind stylesheet was present.
- Explanation-only static-web diagnostic answers should use the latest structured verifier state and preserve this distinction.
- Existing valid Play CDN and generated CSS cases remain valid.

## Test Plan

- Add or update `StaticTaskVerifierTest` to assert precise wording for remote Tailwind stylesheet hrefs.
- Add an explanation/status rendering test if the deterministic answer path emits this diagnostic.

## External Basis

- Tailwind documents Play CDN as a browser/runtime development path.
- Tailwind CLI documents build-generated CSS as a separate path.

## Completion Evidence

- Extended `StaticTaskVerifierTest.remoteTailwindCssHrefIsNotTreatedAsMissingLocalStylesheet()` to assert unsupported remote Tailwind stylesheet wording and reject the old `no Tailwind CDN` phrasing.
- Updated `StaticWebTailwindCoherenceVerifier` to detect remote Tailwind stylesheet links and report them as unsupported runtime/build evidence without accepting them as Tailwind runtime.
- Existing valid Play CDN and generated CSS verifier cases remained green in the affected verification suite.
- Verified with focused and affected-area Gradle test runs on 2026-06-06.
