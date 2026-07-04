# [T949-done-medium] Site E2E preview isolation

Status: done
Priority: medium

## Summary

Make local site E2E tests prove they are running against the Talos site, not an
unrelated process already listening on the configured preview port.

## Evidence

Current Playwright config:

- `site/playwright.config.js:10` sets `baseURL` to
  `http://127.0.0.1:4173`.
- `site/playwright.config.js:14-15` starts Vite preview on port `4173`.
- `site/playwright.config.js:16` uses `reuseExistingServer: !process.env.CI`.

Observed local failure mode during rendered-site review:

- Port `4173` was already serving a different personal-site preview with title
  `Aris Zounarakis | Software Engineer in Barcelona`.
- Because local Playwright may reuse an existing server, a local `npm run
  test:e2e --prefix site` run can become contaminated by a non-Talos page
  unless the operator manually notices the port collision.

This ticket is about QA evidence reliability. It is not a product runtime bug.

## Implementation Direction

Choose one of these safe patterns:

- Do not reuse an existing local server for this project's E2E tests; always
  start the configured preview server.
- Or keep reuse but add a preflight identity check that fails fast unless the
  server at `baseURL` is the Talos site built from this repo.
- Or use a project-specific configurable port and test-only identity endpoint
  or page marker.

The test should fail on a wrong app at the configured URL before running the
rest of the visual/UX suite.

## Acceptance Criteria

- Local `npm run test:e2e --prefix site` cannot accidentally pass or produce
  misleading failures against an unrelated server on `127.0.0.1:4173`.
- The guard verifies an unmistakable Talos marker, such as the title plus a
  known public page marker, before relying on page assertions.
- CI behavior remains deterministic.
- Existing E2E coverage for no horizontal overflow, mobile header/nav, install
  tabs, reduced motion, and docs routing remains intact.

## Verification

Required focused checks:

```powershell
npm run build --prefix site
npm run test:e2e --prefix site
```

Also verify the negative case locally by starting or pointing the configured
base URL at a non-Talos page and confirming the suite fails before giving any
false confidence.

Run `git diff --check` before closeout.

## Completion Evidence

- Added a red static contract test in `site/test/site.test.js`; it failed first
  because `site/index.html` lacked `data-talos-site="landing"`.
- Added page identity markers:
  - `site/index.html`: `data-talos-site="landing"`
  - `site/docs.html`: `data-talos-site="docs"`
- Changed `site/playwright.config.js` to:
  - default to isolated local preview port `41739`;
  - allow override with `TALOS_SITE_E2E_PORT`;
  - allow deliberate negative probes with `TALOS_SITE_E2E_BASE_URL` plus
    `TALOS_SITE_E2E_SKIP_WEBSERVER=1`;
  - run Vite preview with `--strictPort`;
  - set `reuseExistingServer: false`.
- Added `expectTalosPage` / `gotoTalos` in `site/test/e2e/site.spec.js` so E2E
  navigation validates the Talos title and body marker before relying on UI
  assertions.
- Updated stale E2E navigation assertions to use the visible surfaces:
  - desktop section rail (`.vein-rail`);
  - mobile ritual menu opened by the Talos-head button.

Commands run:

```powershell
npm test --prefix site
npm run build --prefix site
$env:TALOS_SITE_E2E_BASE_URL='http://127.0.0.1:4173'
$env:TALOS_SITE_E2E_SKIP_WEBSERVER='1'
npm run test:e2e --prefix site -- --grep "preflight verifies this is the Talos site"
npm run test:e2e --prefix site -- --grep "nav anchors|scroll-spy|mobile header"
npm run test:e2e --prefix site
npm run test:deploy-surface --prefix site
```

Observed negative case:

- `http://127.0.0.1:4173` served `Aris Zounarakis | Software Engineer in
  Barcelona`.
- The preflight failed before visual assertions with expected title
  mismatch: expected `/Talos/`, received the unrelated personal-site title.

Observed positive case:

- `npm run test:e2e --prefix site` started its own strict preview and passed
  24/24 tests.

## Release Gate Impact

Pre-public-artifact QA reliability gate. It should be fixed before using local
rendered-site E2E evidence as part of a public artifact decision.
