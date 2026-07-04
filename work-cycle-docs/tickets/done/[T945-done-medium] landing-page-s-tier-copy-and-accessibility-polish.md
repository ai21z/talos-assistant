# [T945-done-medium] Landing page S-tier copy and accessibility polish

Status: done
Priority: medium

## Summary

Polish the rendered landing page before public artifact publication so the
front door matches the quality of the release engineering. The current page is
strong, distinctive, and honest, but it still has evidence-backed polish gaps:
generic H1 copy, low-contrast status accents, and a header that can be made
more decisive while preserving the current visual identity.

## Evidence

Rendered local review at `http://127.0.0.1:4176/` on branch
`v0.9.0-beta-dev`, commit `478c2399fac43678533415665810de19d963c5a8`,
version `0.10.8` showed:

- The H1 at `site/index.html:132` is
  `Local-first CLI operator for your workspace.` It is accurate but generic
  compared with Talos' sharper falsifiable claim.
- The better Talos claim already exists adjacent to the H1:
  "Inspects before acting. Asks before mutation. Verifies before claiming
  success."
- `site/src/styles.css:116` defines a translucent sticky header. The current
  header is not broken, but a slightly more opaque/header-solid scrolled state
  would reduce visual competition with content behind it.
- Contrast inspection found low ratios around:
  - deny/refuse chips: `site/index.html:370`, `site/index.html:376`,
    styled by `site/src/styles.css:783`.
  - terminal rail text: `site/src/styles.css:645`.

The rendered mobile page at `390x844` had no horizontal overflow and the
install tabs fit, so this ticket is polish, not a mobile rescue.

## Implementation Direction

- Replace the H1 with a Talos-specific claim that stays honest before artifacts
  are live. Candidate direction:
  `The local CLI that verifies before it claims success.`
- Keep the existing subhead discipline, or lightly retune it to preserve the
  inspect/approval/trace message without overclaiming every possible turn.
- Make header treatment more robust on scroll or generally more opaque without
  hiding the current design language.
- Raise low-contrast red/bronze accents to meet practical readability while
  preserving the palette.
- Do not add fake install/download CTAs. The planned install card must continue
  to state that commands go live only when GitHub Release assets exist.

## Acceptance Criteria

- The landing H1 is product-specific and grounded in Talos' real trust thesis.
- Public copy remains bounded: no claim that every action/turn is verified, no
  claim that public install commands are already live, and no unsupported
  browser/cloud/autonomy language.
- Deny/refuse chips and terminal rail accents are more readable against the
  dark background.
- Header visual treatment no longer competes with scrolled content.
- Desktop and mobile rendered screenshots remain coherent at minimum
  `1440x900` and `390x844`.

## Verification

Required focused checks:

```powershell
npm test --prefix site
npm run build --prefix site
npm run test:deploy-surface --prefix site
```

Also perform a local rendered check on a dedicated preview port, not a reused
unrelated server:

```powershell
npm run preview --prefix site -- --host 127.0.0.1 --port <free-port>
```

Capture or inspect:

- desktop hero
- desktop scrolled header
- mobile hero
- install tab section

Run `git diff --check` before closeout.

Completion evidence:

- Replaced the generic landing H1 with the bounded trust-thesis claim:
  "The local CLI that verifies before it claims success."
- Updated landing-page metadata to use the same trust-thesis framing without
  claiming public install availability, cloud/browser capability, or unbounded
  verification.
- Made the sticky header more opaque and separated from scrolled content.
- Raised warning/deny and terminal rail contrast; added static contrast/header
  contract coverage.
- Fixed a rendered scrolled-page washout found during T945 visual verification
  by making the WebGL smoke canvas transparent and clearing it to transparent
  before every frame.
- Added regression coverage for the H1, header opacity/shadow, accent
  readability, and transparent smoke canvas.
- Verified with `npm test --prefix site`,
  `npm run build --prefix site`, and
  `npm run test:deploy-surface --prefix site`.
- Performed a dedicated rendered preview check on
  `http://127.0.0.1:4185/`, with desktop `1440x900`, scrolled desktop header,
  mobile `390x844`, and mobile install-tab screenshots under
  `%TEMP%\talos-t945-render\`.

## Release Gate Impact

Owner-defined pre-public-artifact quality gate. This is not a runtime safety
blocker, but it should be resolved before presenting the first public artifact
as the product front door.
