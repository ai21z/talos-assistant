# [T946-done-high] Docs route coverage and model profile links

Status: done
Priority: high

## Summary

Fix the docs site's route coverage so every public Markdown guide linked from
the in-site documentation renders successfully. The current docs site bundles
top-level `docs/user/*.md` files but links to nested model-profile Markdown
files that the router cannot render.

## Evidence

Current docs router:

- `site/src/docs.js:10` imports only `../../docs/user/*.md`.
- `site/src/docs.js:263` renders "Page not found" when a route slug is missing.

Current docs links:

- `docs/user/model-setup.md:61` links to
  `model-profiles/qwen2.5-coder-14b.md`.
- `docs/user/model-setup.md:62` links to
  `model-profiles/gpt-oss-20b.md`.
- `docs/user/model-setup.md:63` links to
  `model-profiles/qwen36vf-q4km.md`.
- `docs/user/model-setup.md:64` links to
  `model-profiles/qwen36vf-q6k.md`.
- `docs/user/model-setup.md:65` links to
  `model-profiles/deepseek-v2lite-q4km.md`.

Rendered behavior verified locally:

- `http://127.0.0.1:4176/docs.html#/model-setup` exposes `guide` links with
  `href="#/model-profiles/..."`.
- `http://127.0.0.1:4176/docs.html#/model-profiles/qwen2.5-coder-14b`
  renders `Page not found`.

Navigation coverage gap:

- `docs/user/index.md:28` links to `retrieval-and-vectors.md`.
- `docs/user/index.md:29` links to `beta-best-practices.md`.
- `site/docs.html:71-92` sidebar omits both `retrieval-and-vectors` and
  `beta-best-practices`.
- The rendered docs landing cards also omit both pages.

## Why It Matters

Model setup is a critical first-run path. A public docs page that links to
"guide" pages which render "Page not found" is a direct usefulness and quality
failure before release artifacts.

## Implementation Direction

Choose the smallest durable design:

- Either import `../../docs/user/**/*.md` and normalize nested slugs such as
  `model-profiles/qwen2.5-coder-14b`, or change the docs links to routes that
  are actually bundled.
- Add `retrieval-and-vectors` and `beta-best-practices` to the sidebar and the
  curated docs landing unless there is an explicit intentional-exclusion list.
- Add a docs route/link coverage test:
  - every internal Markdown link in bundled user docs resolves to a route that
    renders a real document or is listed in a small intentional exclusion list;
  - every public `docs/user/**/*.md` file is either routable or intentionally
    excluded;
  - every sidebar slug points to an existing bundled document.

## Acceptance Criteria

- `#/model-profiles/qwen2.5-coder-14b` and the other model-profile routes render
  their real Markdown content, or the Model Setup table no longer links to
  unroutable pages.
- `retrieval-and-vectors` and `beta-best-practices` are reachable from the docs
  sidebar and landing, or an explicit tested exclusion explains why they are not
  public navigation entries.
- A focused test fails on the current broken model-profile route and passes
  after the fix.
- A focused test fails if a future internal Markdown link points to an
  unroutable docs page.

## Verification

Required focused checks:

```powershell
npm test --prefix site
npm run build --prefix site
npm run test:deploy-surface --prefix site
```

Rendered smoke:

- open `/docs.html#/model-setup`
- follow at least one `guide` route
- open `/docs.html#/retrieval-and-vectors`
- open `/docs.html#/beta-best-practices`
- verify mobile docs menu still opens and closes at `390x844`

Run `git diff --check` before closeout.

## Completion Evidence

Completed on `v0.9.0-beta-dev` for the 0.10.8 public-artifact preparation
batch.

- The docs router now bundles `docs/user/**/*.md`, preserving nested slugs such
  as `model-profiles/qwen2.5-coder-14b`.
- Model-profile links from `docs/user/model-setup.md` now route to real rendered
  pages instead of `Page not found`.
- `retrieval-and-vectors` and `beta-best-practices` are now linked from the
  docs sidebar and curated docs landing.
- Static tests now derive the public user-doc slug list from disk, assert the
  expected route set, verify internal Markdown links resolve to routable docs,
  and verify the sidebar/landing coverage.
- Rendered preview smoke verified the model-profile, retrieval, and beta
  best-practice routes plus mobile docs menu open/close behavior.

## Release Gate Impact

Blocks public release artifact publication. This is a public docs correctness
failure, not a subjective polish issue.
