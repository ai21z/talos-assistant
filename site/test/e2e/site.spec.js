import { expect, test } from "@playwright/test";

const widths = [320, 375, 390, 768, 1024, 1440];

// Benign GPU/WebGL lifecycle noise emitted by headless Chromium (occluded tabs
// drop WebGL contexts; ReadPixels stalls come from screenshot readback). These
// are browser/driver events, not application defects, so they are ignored while
// the guard stays strict on every real console error or warning.
const BENIGN_BROWSER_NOISE = [
  /CONTEXT_LOST_WEBGL/i,
  /GPU stall due to ReadPixels/i,
  /GL Driver Message/i,
];

test.beforeEach(async ({ page }) => {
  const browserIssues = [];
  page.on("console", (message) => {
    if (!["error", "warning"].includes(message.type())) return;
    const text = message.text();
    if (BENIGN_BROWSER_NOISE.some((pattern) => pattern.test(text))) return;
    browserIssues.push(`${message.type()}: ${text}`);
  });
  page.on("pageerror", (error) => browserIssues.push(`pageerror: ${error.message}`));
  page.browserIssues = browserIssues;
});

test("page renders without browser console errors and has one landing h1", async ({ page }) => {
  await page.goto("/");
  await expect(page).toHaveTitle(/Talos/);
  await expect(page.locator("h1")).toHaveCount(1);
  await expect(page.locator("h1")).toContainText(/local-first/i);
  await expect(page.locator("h1")).toContainText(/workspace/i);
  expect(page.browserIssues).toEqual([]);
});

test("nav anchors exist and scroll to real sections", async ({ page }) => {
  await page.goto("/");
  const navLinks = page.locator(".site-nav a");
  const count = await navLinks.count();
  expect(count).toBeGreaterThan(4);

  for (let index = 0; index < count; index += 1) {
    const link = navLinks.nth(index);
    const href = await link.getAttribute("href");
    expect(href).toMatch(/^#/);
    const target = page.locator(href);
    await expect(target).toHaveCount(1);
    await link.click();
    await expect(page).toHaveURL(new RegExp(`${href}$`));
    await expect(target).toBeInViewport();
  }
});

for (const width of widths) {
  test(`has no horizontal overflow at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await page.goto("/");
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
    expect(overflow).toBeLessThanOrEqual(1);
  });
}

test("live hero terminal streams a real turn and can replay", async ({ page }) => {
  await page.goto("/");
  const output = page.locator("[data-live-output]");
  // Streams the inspect turn to completion (route → inspect → answer).
  await expect(output).toContainText("route", { timeout: 9000 });
  await expect(output).toContainText("Local-first CLI workspace operator", { timeout: 9000 });
  await expect(output).toContainText("/last trace", { timeout: 9000 });

  // Replay restarts the same turn.
  await page.locator("[data-live-replay]").click();
  await expect(output).toContainText("what does this workspace do?", { timeout: 9000 });
  await expect(output).toContainText("Local-first CLI workspace operator", { timeout: 9000 });
  expect(page.browserIssues).toEqual([]);
});

test("hero shows the Greek identity inscription accent", async ({ page }) => {
  await page.goto("/");
  const greek = page.locator(".hero-inscription-greek");
  await expect(greek).toHaveText("ΤΑΛΩΣ");
  await expect(greek).toBeVisible();
  await expect(page.locator(".live-terminal")).toBeVisible();
});

test("terminal tabs switch content on click and keyboard", async ({ page }) => {
  await page.goto("/");
  const output = page.locator("#terminal-output");

  await page.getByRole("tab", { name: "Approve" }).click();
  await expect(output).toContainText("approval required");

  await page.getByRole("tab", { name: "Approve" }).press("ArrowRight");
  await expect(page.getByRole("tab", { name: "Verify" })).toHaveAttribute("aria-selected", "true");
  await expect(output).toContainText("talos.run_command");

  await page.getByRole("tab", { name: "Verify" }).press("ArrowLeft");
  await expect(page.getByRole("tab", { name: "Approve" })).toHaveAttribute("aria-selected", "true");

  await page.getByRole("tab", { name: "Approve" }).press("End");
  await expect(page.getByRole("tab", { name: "Trace" })).toHaveAttribute("aria-selected", "true");
  await expect(output).toContainText("/last trace");

  await page.getByRole("tab", { name: "Trace" }).press("Home");
  await expect(page.getByRole("tab", { name: "Inspect" })).toHaveAttribute("aria-selected", "true");
});

test("scroll-spy moves the active nav state as sections are visited", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto("/");
  await expect(page.locator('.site-nav a[aria-current="page"]')).toHaveText("Overview");

  await page.getByRole("navigation", { name: "Primary navigation" })
    .getByRole("link", { name: "Local Boundaries" })
    .click();
  await expect(page).toHaveURL(/#local-boundaries$/);
  await expect(page.locator("#local-boundaries")).toBeInViewport();
  await expect(page.locator('.site-nav a[aria-current="page"]')).toHaveText("Local Boundaries");

  // Native scroll is not hijacked.
  const scrollState = await page.evaluate(() => ({
    overflowY: getComputedStyle(document.documentElement).overflowY,
    snapped: getComputedStyle(document.documentElement).scrollSnapType,
  }));
  expect(scrollState.overflowY).not.toBe("hidden");
  expect(scrollState.snapped).not.toMatch(/mandatory/i);
});

test("staggered card grids reveal when scrolled into view", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto("/");
  const card = page.locator(".boundary-band").first();
  await page.locator("#local-boundaries").scrollIntoViewIfNeeded();
  await expect
    .poll(async () => Number(await card.evaluate((node) => getComputedStyle(node).opacity)), {
      timeout: 5000,
    })
    .toBeGreaterThan(0.9);
});

test("the ichor vein fills from empty to full as you scroll", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto("/");
  const ichor = () =>
    page.locator(".vein").evaluate((node) => Number(node.style.getPropertyValue("--ichor")) || 0);
  expect(await ichor()).toBeLessThan(0.1);
  await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight));
  await expect.poll(ichor, { timeout: 3000 }).toBeGreaterThan(0.9);
});

test("the cold-boot awakening plays when forced, then clears to reveal the page", async ({ page }) => {
  await page.addInitScript(() => {
    window.__forceAwaken = true;
    window.__sawAwaken = false;
    const tick = () => {
      const el = document.querySelector(".awaken");
      if (el && getComputedStyle(el).display !== "none") window.__sawAwaken = true;
      if (el) requestAnimationFrame(tick);
    };
    requestAnimationFrame(tick);
  });
  await page.goto("/");
  // It clears itself, restoring scroll + content.
  await expect
    .poll(() => page.evaluate(() => document.documentElement.classList.contains("awakening")), { timeout: 5000 })
    .toBe(false);
  await expect(page.locator(".awaken")).toHaveCount(0);
  const state = await page.evaluate(() => ({
    saw: window.__sawAwaken,
    flag: (() => { try { return sessionStorage.getItem("talosAwoke"); } catch (e) { return null; } })(),
  }));
  expect(state.saw).toBe(true); // the overlay was actually shown
  expect(state.flag).toBe("1"); // finish() ran (not just the failsafe)
  await expect(page.locator("h1")).toBeVisible();
  expect(page.browserIssues).toEqual([]);
});

test("planned install surface has no fake copy affordance", async ({ page }) => {
  await page.goto("/");
  const setup = page.locator(".setup-strip");
  await expect(setup).toContainText("planned public beta");
  await expect(setup).toContainText("winget install talos-cli");
  await expect(setup).toContainText("TalosProject.TalosCLI");
  await expect(page.locator("[data-copy]")).toHaveCount(0);
});

test("hero CTAs are real links, not placeholder beta actions", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByRole("link", { name: "View on GitHub" })).toHaveAttribute(
    "href",
    "https://github.com/ai21z/talos-cli",
  );
  await expect(page.getByRole("link", { name: "Read docs" }).first()).toHaveAttribute("href", "#docs");
  await expect(page.getByRole("button", { name: "Get beta build" })).toHaveCount(0);
});

test("docs page routes render without hiding content under the sticky header", async ({ page }) => {
  await page.goto("/docs.html#/quickstart");
  await expect(page).toHaveTitle(/Quickstart \| Talos documentation/);
  await expect(page.locator("#docs-article h1")).toHaveText("Quickstart");
  await expect(page.locator('[data-doc-slug="quickstart"]')).toHaveAttribute("aria-current", "page");

  const layout = await page.evaluate(() => {
    const header = document.querySelector(".site-header").getBoundingClientRect();
    const h1 = document.querySelector("#docs-article h1").getBoundingClientRect();
    return {
      h1Top: h1.top,
      headerBottom: header.bottom,
      overflow: document.documentElement.scrollWidth - window.innerWidth,
    };
  });
  expect(layout.h1Top).toBeGreaterThan(layout.headerBottom + 8);
  expect(layout.overflow).toBeLessThanOrEqual(1);
  expect(page.browserIssues).toEqual([]);
});

test("docs code blocks expose a working copy control", async ({ page, context }) => {
  await context.grantPermissions(["clipboard-read", "clipboard-write"]);
  await page.goto("/docs.html#/quickstart");
  const copy = page.locator(".docs-copy").first();
  await expect(copy).toHaveCount(1);
  await copy.click();
  await expect(copy).toHaveText("Copied");
});

test("docs page keeps in-page Markdown anchors inside the current docs route", async ({ page }) => {
  await page.goto("/docs.html#/quickstart");
  await page.getByRole("link", { name: "Current Support" }).click();
  await expect(page).toHaveURL(/\/docs\.html#\/quickstart#current-support$/);
  await expect(page.locator("#docs-article h1")).toHaveText("Quickstart");
  await expect(page.locator("#current-support")).toBeInViewport();
  expect(page.browserIssues).toEqual([]);
});

test("mobile header and nav remain usable", async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 780 });
  await page.goto("/");
  const primaryNav = page.getByRole("navigation", { name: "Primary navigation" });
  await expect(primaryNav).toBeVisible();
  await expect(primaryNav.getByRole("link", { name: "Overview" })).toBeVisible();
  await expect(primaryNav.getByRole("link", { name: "Docs" })).toBeVisible();
  await primaryNav.getByRole("link", { name: "Docs" }).click();
  await expect(page.locator("#docs")).toBeInViewport();
});

test("mobile hero content fits without horizontal clipping", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 900 });
  await page.goto("/");
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
  expect(overflow).toBeLessThanOrEqual(1);

  for (const selector of [
    "h1",
    ".hero-actions",
    ".evidence-row",
    ".setup-strip",
    ".machine-note",
    ".hero-visual",
    ".live-terminal",
    ".hero-inscription",
  ]) {
    const box = await page.locator(selector).boundingBox();
    expect(box, `${selector} should render`).not.toBeNull();
    expect(box.x, `${selector} left edge`).toBeGreaterThanOrEqual(0);
    expect(box.x + box.width, `${selector} right edge`).toBeLessThanOrEqual(390 + 1);
  }
});

test("reduced-motion mode leaves content visible without reveal animations", async ({ page }) => {
  await page.emulateMedia({ reducedMotion: "reduce" });
  await page.goto("/");
  const hiddenRevealCount = await page.locator(".reveal").evaluateAll((nodes) =>
    nodes.filter((node) => {
      const style = window.getComputedStyle(node);
      return style.opacity === "0" || style.visibility === "hidden";
    }).length,
  );
  expect(hiddenRevealCount).toBe(0);
  await expect(page.locator("h1")).toBeVisible();
  await expect(page.locator(".hero-inscription-greek")).toBeVisible();
  // The live terminal still renders its content statically.
  await expect(page.locator("[data-live-output]")).toContainText("Local-first CLI workspace operator");
  // Staggered groups are shown immediately (no cascade) under reduced motion.
  const cardOpacity = await page
    .locator(".doc-card")
    .first()
    .evaluate((node) => getComputedStyle(node).opacity);
  expect(Number(cardOpacity)).toBe(1);
});
