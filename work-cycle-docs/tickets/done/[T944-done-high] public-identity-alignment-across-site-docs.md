# [T944-done-high] Public identity alignment across site and docs

Status: done
Priority: high

## Summary

Align every current public-facing Talos identity surface with the owner's
current public identity:

- name: `Aris Zounarakis`
- email: `aris@zounarakis.com`

This is a pre-public-artifact truth fix. Public site, public docs, README, and
public install documentation must not publish the old name/email after this
ticket closes.

## Evidence

Current source still contains the old identity:

- `site/index.html:503`, `site/index.html:507`, `site/index.html:510`
  render the footer signature as `Vissarion Zounarakis` and
  `vissarion@zounarakis.com`.
- `site/docs.html:136`, `site/docs.html:140`, `site/docs.html:143`
  render the same old footer identity on the docs page.
- `docs/user/installation.md:93` lists planned publisher as
  `Vissarion Zounarakis`.
- `docs/public-installation.md:53` lists planned publisher as
  `Vissarion Zounarakis`.
- `README.md:367` describes the planned publisher as
  `Vissarion Zounarakis`.
- Rendered local `site/dist` after `npm run build --prefix site` also contained
  the old footer identity in `index.html`, `docs.html`, and the docs bundle.

The current `site/test/deploy-surface.test.js` only rejects assistant/tool
provenance markers in built output. It does not guard owner identity drift.

## Why It Matters

The public release artifacts will point users to the site, README, and install
docs. If those surfaces disagree about the publisher/author identity, the first
public beta looks uncurated and creates avoidable trust friction.

This is not a git-author rewrite ticket. It is only about current public-facing
text and generated deploy output.

## Implementation Direction

- Update current public-facing identity strings to `Aris Zounarakis` and
  `aris@zounarakis.com`.
- Keep the GitHub account/repo identity unchanged unless the owner separately
  decides otherwise.
- Preserve package identity decisions already made by T943:
  `TalosLocal.Talos` for planned winget ID, `talos-cli` as friendly
  package/moniker, and `talos` as the CLI command.
- Add a public-surface test that fails when the old name or old email appears
  in the current site/docs/README/public-install surfaces.
- Extend deploy-surface or site static tests so the built `site/dist` cannot
  reintroduce the old name or old email.

## Acceptance Criteria

- `site/index.html` and `site/docs.html` footer signatures use
  `Aris Zounarakis` and `aris@zounarakis.com`.
- `README.md`, `docs/public-installation.md`, and
  `docs/user/installation.md` use the current public publisher identity.
- `npm run build --prefix site` produces no old identity string in `site/dist`.
- Focused tests fail before the identity update and pass after it.
- No release artifact, tag, GitHub Release, draft release, or winget
  publication is created by this ticket.

## Verification

Required focused checks:

```powershell
npm test --prefix site
npm run build --prefix site
npm run test:deploy-surface --prefix site
rg -n "Vissarion|vissarion@" site docs README.md
```

Run `git diff --check` before closeout.

## Completion Evidence

Completed on `v0.9.0-beta-dev` for the 0.10.8 public-artifact preparation
batch.

- Public site footers now use `Aris Zounarakis` and
  `aris@zounarakis.com`.
- `README.md`, `docs/public-installation.md`, and
  `docs/user/installation.md` now use `Aris Zounarakis` as the planned
  publisher.
- Release packaging metadata in `build.gradle.kts` now uses
  `Aris Zounarakis` for jpackage `--vendor` values, and
  `PublicInstallPackagingContractTest` pins the same publisher identity.
- Static site tests now fail if the old owner identity appears on the current
  public site/docs/README/install surfaces.
- Deploy-surface tests now fail if built `site/dist` reintroduces the old
  owner identity.
- The tracked T926 implementation plan's owner-identity check was refreshed to
  the current owner identity because it is an operational public repo document,
  not immutable evidence.

## Release Gate Impact

Blocks public release artifact publication until fixed or explicitly waived by
the owner. The blocker is public-truth quality, not runtime safety.
