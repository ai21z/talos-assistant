# T697 - External Frontend Framework Asset Coherence

Status: open
Severity: high

## Problem

Recent static-web work correctly tightened Tailwind-specific behavior, but the
underlying product problem is broader: when the user asks for a frontend
framework or CDN/runtime path, Talos must distinguish a valid remote runtime,
a local/generated build artifact, and a placeholder or unsupported local asset.

The current implementation has strong Tailwind-specific checks:

- `StaticWebTailwindCoherenceVerifier`
- Tailwind forbidden-artifact extraction in `TaskContractResolver`
- Tailwind repair-target filtering in `RepairPolicy`
- remote static-asset handling in `StaticWebRemoteAssetVerifier`

That is useful, but it is still a family-specific lane. The next static-web
architecture step should generalize the concept so Bootstrap, Alpine, HTMX,
React CDN prototypes, and other explicit external/static frontend assets are
handled by the same runtime/build/CDN coherence model instead of by adding
another one-off verifier for every library.

## Evidence

- The Qwen `test02-10` final site used a remote Tailwind CSS href:
  `https://cdn.jsdelivr.net/npm/tailwindcss@2.2.19/dist/tailwind.min.css`.
  The static verifier treated Tailwind utility classes as lacking an accepted
  Tailwind runtime/build path. That was honest for the current Tailwind rule,
  but it also shows the need to define framework runtime acceptance explicitly.
- `src/main/java/dev/talos/runtime/verification/StaticWebTailwindCoherenceVerifier.java`
  is Tailwind-specific.
- `src/main/java/dev/talos/runtime/verification/StaticWebRemoteAssetVerifier.java`
  is remote-asset-specific.
- `src/main/java/dev/talos/runtime/task/TaskContractResolver.java` currently
  contains Tailwind local-artifact target extraction.
- `src/main/java/dev/talos/runtime/repair/RepairPolicy.java` has
  Tailwind-coherence repair targeting.
- Existing tests under `StaticTaskVerifierTest`, `RepairPolicyTest`, and
  `TaskContractResolverTest` cover Tailwind cases but not a generic external
  framework taxonomy.

## Architecture Metadata

- Capability ownership: `runtime.verification`, `runtime.task`, and
  `runtime.repair`.
- Operation type: static-web creation/rewrite/repair involving remote or local
  frontend framework assets.
- Risk: high; invalid framework artifacts can produce a visually broken site
  while static verification reports only generic local-file success.
- Approval behavior: unchanged.
- Protected path behavior: unchanged.
- Checkpoint behavior: unchanged.
- Evidence obligation: verifier output must distinguish remote limitation,
  accepted runtime, accepted generated/build artifact, and unsupported local
  placeholder.
- Verification profile: `STATIC_WEB`.
- Repair profile: framework coherence repair maps to writable site files and
  never to forbidden or remote-derived local artifacts.
- Outcome/trace changes: static-web verification problems should name the
  framework/asset class, not just a raw missing filename.
- Allowed refactor scope: introduce a small frontend asset/framework
  classifier and adapt Tailwind checks to use it; do not add visual proof or a
  bundler.

## Acceptance

- Remote URLs are never converted into local missing-file obligations or local
  repair targets merely by basename.
- Supported framework runtime paths are represented explicitly, for example
  Tailwind Play/browser CDN when accepted for local demo use.
- Local/generated framework CSS is accepted only when there is real linked CSS
  or build evidence, not placeholder directives or empty files.
- Unsupported local framework artifacts such as `tailwind.css` or
  `tailwind.min.css` remain forbidden/failed unless the user explicitly asks
  for a build-backed local artifact and the workspace contains build evidence.
- At least one non-Tailwind framework fixture is covered so the design is not
  Tailwind-only by construction.
- Existing Tailwind tests continue to pass.

## Regression Tests

- Static verifier: valid remote runtime accepted with limitation wording.
- Static verifier: remote framework URL not treated as local missing file.
- Static verifier: invalid local framework placeholder fails.
- Repair policy: framework coherence problems target `index.html`, linked local
  CSS, linked local JS, and expected site files, not remote basenames.
- Task resolver: "no local framework artifact" creates forbidden local artifact
  constraints only for the named framework/local artifact class, not for normal
  `style.css`.

## Non-Goals

- No browser visual-quality proof.
- No automatic dependency installation or bundler execution.
- No claim that remote CDN use is production-ready; local demo acceptance should
  still surface an appropriate limitation.

