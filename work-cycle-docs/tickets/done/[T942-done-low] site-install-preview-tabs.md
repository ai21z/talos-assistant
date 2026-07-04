# [T942-done-low] Site install preview should be a compact Windows/Linux tab card

Status: done
Priority: low

## Evidence Summary

- Source: Owner review of the live Cloudflare staging hero install card
- Date: 2026-07-03
- Talos version / commit: 0.10.8 /
  3f5263ef0c319a9046eb4d7ef54518f7247cb318
- Branch: `v0.9.0-beta-dev`
- Evidence:
  - Staging preview `https://site-staging.taloslocal.pages.dev` is live.
  - The current hero setup card renders a multi-line command block and a dense
    explanatory paragraph.
  - Owner requested a simpler install card with two tabs: Windows and Linux.

## Classification

Primary taxonomy bucket: `PUBLIC_SITE`

Secondary buckets:

- `INSTALL_SURFACE`
- `UX_POLISH`
- `RELEASE_HYGIENE`

Blocker level: pre-public-site polish

Why this level:

```text
The current card is truthful but too dense for the first viewport. The public
site should preview the planned one-command install lanes without implying that
the commands are live before release assets exist.
```

## Goal

```text
Replace the dense planned-install card with a compact Windows/Linux tab preview:
one command per platform, a short truth note that commands go live only after
release assets are published, and a link to installation docs.
```

## Non-Goals

- Do not claim the install commands are live.
- Do not add copy buttons while commands are still planned.
- Do not bind `taloslocal.com` or deploy staging from this ticket without owner
  review.
- Do not publish a release, tag, package, winget manifest, or Linux installer.

## Acceptance Criteria

- Hero setup card keeps `planned public beta`.
- Hero setup card has exactly two visible install lane tabs: Windows and Linux.
- Windows tab shows only the planned one-command Windows install:
  `winget install --id TalosLocal.Talos -e`.
- Linux tab shows only the planned one-command Linux install:
  `curl -fsSL https://taloslocal.com/install.sh | sh`.
- The card explains that install commands go live only when the first GitHub
  Release assets are published.
- The card states that the setup wizard guides model and llama.cpp
  configuration.
- The card keeps the installation docs link.
- The card has no `data-copy` affordance or other fake live-install CTA.
- Site build/deploy-surface checks still reject `codex`, `claude`, and
  `copilot` in `site/dist`.

## Tests / Evidence

Required red/green:

```powershell
npm test --prefix site
npm run build --prefix site
npm run test:deploy-surface --prefix site
```

Verification evidence:

- 2026-07-03: `npm test --prefix site` failed before implementation because
  the compact Linux tab command was absent.
- 2026-07-03: `npm test --prefix site` passed after implementation.
- 2026-07-03: `npm run build --prefix site` passed.
- 2026-07-03: `npm run test:deploy-surface --prefix site` passed.
- 2026-07-03: local browser smoke against `http://127.0.0.1:4174/`
  verified Windows tab, Linux tab, and no `data-copy`.
- 2026-07-04: T943 updated the planned Windows ID in this card to
  `TalosLocal.Talos`; site static tests passed again.

Required manual/local preview:

```powershell
npm run dev --prefix site -- --host 127.0.0.1
```

## Implementation Notes

- Reuse the existing visual language from the terminal tab controls where
  practical.
- Keep the copy short. Detailed post-install workflow belongs in docs and the
  setup wizard, not the hero card.
