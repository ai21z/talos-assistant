import { expect, test } from "@playwright/test";
import { readFileSync } from "node:fs";

// 305px models a 320px window with a 15px classic scrollbar.
// It does not replace a native scrollbar check on a visible browser.
const widths = [305, 320, 375, 390, 768, 1024, 1440];

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

async function expectTalosPage(page, marker) {
  await expect(page).toHaveTitle(/Talos/);
  await expect(page.locator(`body[data-talos-site="${marker}"]`)).toHaveCount(1);
}

async function gotoTalos(page, path = "/", marker = "landing") {
  await page.goto(path);
  await expectTalosPage(page, marker);
}

async function expectPageFits(page) {
  await page.evaluate(() => document.fonts.ready);
  const layout = await page.evaluate(() => ({
    clientWidth: document.documentElement.clientWidth,
    scrollWidth: document.documentElement.scrollWidth,
    overflowX: [document.documentElement, document.body].map((node) => getComputedStyle(node).overflowX),
  }));
  expect(layout.clientWidth).toBeGreaterThan(0);
  expect(layout.scrollWidth).toBeLessThanOrEqual(layout.clientWidth + 1);
  for (const overflowX of layout.overflowX) {
    expect(["hidden", "clip"]).not.toContain(overflowX);
  }
}

async function expectInsideContentWidth(locator) {
  await expect(locator).toBeVisible();
  const box = await locator.evaluate((node) => {
    const rect = node.getBoundingClientRect();
    return { left: rect.left, right: rect.right, clientWidth: document.documentElement.clientWidth };
  });
  expect(box.left, `${locator} left edge`).toBeGreaterThanOrEqual(0);
  expect(box.right, `${locator} right edge`).toBeLessThanOrEqual(box.clientWidth + 1);
}

test("preflight verifies this is the Talos site", async ({ page }) => {
  await gotoTalos(page);
  await gotoTalos(page, "/docs.html#/quickstart", "docs");
});

test("page renders without browser console errors and has one landing h1", async ({ page }) => {
  await gotoTalos(page);
  await expect(page.locator("h1")).toHaveCount(1);
  await expect(page.locator("h1")).toHaveText("The local CLI that verifies before it claims success.");
  expect(page.browserIssues).toEqual([]);
});

test("nav anchors exist and scroll to real sections", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await gotoTalos(page);
  const navLinks = page.locator(".vein-rail a");
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
    await gotoTalos(page);
    await expectPageFits(page);
  });
}

test("live hero terminal streams a real turn and can replay", async ({ page }) => {
  await gotoTalos(page);
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

test("hero shows the TALOS wordmark and cycling acrostic word", async ({ page }) => {
  await gotoTalos(page);
  const mark = page.locator(".hero-inscription-mark");
  await expect(mark).toHaveText("TALOS");
  await expect(mark).toBeVisible();
  await expect(page.locator("[data-inscription-cycle]")).toBeVisible();
  await expect(page.locator(".live-terminal")).toBeVisible();
});

test("terminal tabs switch content on click and keyboard", async ({ page }) => {
  await gotoTalos(page);
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
  await gotoTalos(page);
  const rail = page.locator(".vein-rail");
  await expect(rail.locator('a[aria-current="page"]')).toContainText("Overview");

  await rail.getByRole("link", { name: "Local Boundaries" }).click();
  await expect(page).toHaveURL(/#local-boundaries$/);
  await expect(page.locator("#local-boundaries")).toBeInViewport();
  await expect(rail.locator('a[aria-current="page"]')).toContainText("Local Boundaries");

  // Native scroll is not hijacked.
  const scrollState = await page.evaluate(() => ({
    overflowY: getComputedStyle(document.documentElement).overflowY,
    snapped: getComputedStyle(document.documentElement).scrollSnapType,
  }));
  expect(scrollState.overflowY).not.toBe("hidden");
  expect(scrollState.snapped).not.toMatch(/mandatory/i);
});

test("desktop vein rail reveals section names only after scroll", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await gotoTalos(page);
  const rail = page.locator(".vein-rail");
  const firstStop = rail.locator(".vein-stop").first();
  const labels = rail.locator(".vein-stop-label");
  const activeLabel = rail.locator('.vein-stop[aria-current="page"] .vein-stop-label');

  await expect(firstStop).toBeVisible();
  await expect(rail).not.toHaveClass(/has-scrolled/);
  await expect
    .poll(() => labels.first().evaluate((node) => Number(getComputedStyle(node).opacity)), {
      timeout: 3000,
    })
    .toBeLessThan(0.1);

  await page.mouse.wheel(0, 520);
  await expect.poll(() => page.evaluate(() => window.scrollY), { timeout: 3000 }).toBeGreaterThan(96);
  await expect(rail).toHaveClass(/has-scrolled/);
  await expect
    .poll(() => activeLabel.evaluate((node) => Number(getComputedStyle(node).opacity)), {
      timeout: 3000,
    })
    .toBeGreaterThan(0.8);

  await page.setViewportSize({ width: 390, height: 900 });
  await expect(firstStop).toBeHidden();
});

test("staggered card grids reveal when scrolled into view", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await gotoTalos(page);
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
  await gotoTalos(page);
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
  await gotoTalos(page);
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

test("public beta install commands copy the active platform command", async ({ page, context }) => {
  await context.grantPermissions(["clipboard-read", "clipboard-write"]);
  await gotoTalos(page);
  const setup = page.locator(".setup-strip");
  await expect(setup).toContainText("public beta");
  await expect(setup.getByRole("tab", { name: "Windows" })).toBeVisible();
  await expect(setup.getByRole("tab", { name: "Linux" })).toBeVisible();
  await expect(setup).toContainText("install-talos.ps1");
  await expect(setup).toContainText("-AllowUnsigned");
  const copy = setup.locator("[data-setup-copy]");
  await expect(copy).toBeVisible();
  await expect(copy).toHaveAttribute("aria-label", "Copy Windows install command");
  await copy.click();
  await expect(copy).toHaveAttribute("data-copied", "true");
  await expect
    .poll(() => page.evaluate(() => navigator.clipboard.readText()))
    .toContain("powershell -ExecutionPolicy Bypass -File .\\install-talos.ps1 -Version 0.10.8 -Force -AllowUnsigned");

  await setup.getByRole("tab", { name: "Linux" }).click();
  await expect(setup).toContainText("github.com/ai21z/talos-assistant/releases/download/v0.10.8/install-talos.sh");
  await expect(copy).toHaveAttribute("aria-label", "Copy Linux install command");
  await copy.click();
  await expect
    .poll(() => page.evaluate(() => navigator.clipboard.readText()))
    .toBe("curl -fsSL https://github.com/ai21z/talos-assistant/releases/download/v0.10.8/install-talos.sh | bash -s -- --version 0.10.8 --force");
  await setup.getByLabel("Public beta details").focus();
  await expect(setup.getByRole("tooltip")).toContainText("Installs from GitHub Release assets");
  await expect(setup.getByRole("tooltip")).toContainText("Windows 0.10.8 is unsigned");
  await expect(setup.getByRole("tooltip")).toContainText("To upgrade, rerun the installer with --force and the pinned version");
});

test("header CTAs are real links, not placeholder beta actions", async ({ page }) => {
  await gotoTalos(page);
  const header = page.locator(".site-header");
  await expect(header.locator("[data-section-nav]")).toHaveCount(0);
  await expect(page.locator(".site-nav")).toHaveCount(0);
  await expect(header.getByRole("link", { name: "View on GitHub" })).toHaveAttribute(
    "href",
    "https://github.com/ai21z/talos-assistant",
  );
  await expect(header.getByRole("link", { name: "View on GitHub" })).toHaveAttribute("target", "_blank");
  await expect(header.getByRole("link", { name: "Read docs" })).toHaveAttribute("href", "./docs.html");
  await expect(header.getByRole("link", { name: "Read docs" })).toHaveAttribute("target", "_blank");
  await expect(header.getByRole("link", { name: "Read docs" })).toHaveClass(/button--primary/);
  await expect(page.locator(".hero-actions")).toHaveCount(0);
  await expect(page.locator(".evidence-row")).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Get beta build" })).toHaveCount(0);
});

test("landing documentation actions open the correct destinations in new tabs", async ({ page }) => {
  await gotoTalos(page);
  const docs = page.locator("#docs");
  await expect(docs.getByRole("link", { name: "Open documentation" })).toHaveAttribute("href", "./docs.html");
  await expect(docs.getByRole("link", { name: "Open documentation" })).toHaveAttribute("target", "_blank");
  await expect(docs.getByRole("link", { name: "GitHub Docs" })).toHaveAttribute(
    "href",
    "https://github.com/ai21z/talos-assistant/blob/main/docs/architecture/execution-model.md",
  );
  await expect(docs.getByRole("link", { name: "GitHub Docs" })).toHaveAttribute("target", "_blank");
  await expect(docs.getByRole("link", { name: "Jump to Quickstart" })).toHaveCount(0);

  const cards = await docs.locator(".doc-card").evaluateAll((nodes) =>
    nodes.map((node) => ({
      href: node.getAttribute("href"),
      rel: node.getAttribute("rel"),
      target: node.getAttribute("target"),
    })),
  );
  expect(cards).toEqual([
    { href: "./docs.html#/getting-started/quickstart", rel: "noopener", target: "_blank" },
    { href: "./docs.html#/getting-started/model-setup", rel: "noopener", target: "_blank" },
    { href: "./docs.html#/user/permissions-and-approvals", rel: "noopener", target: "_blank" },
    { href: "./docs.html#/architecture/execution-model", rel: "noopener", target: "_blank" },
  ]);
});

test("docs page routes render without hiding content under the sticky header", async ({ page }) => {
  await gotoTalos(page, "/docs.html#/getting-started/quickstart", "docs");
  await expect(page).toHaveTitle(/Quickstart \| Talos documentation/);
  await expect(page.locator("#docs-article h1")).toHaveText("Quickstart");
  await expect(page.locator('[data-doc-slug="getting-started/quickstart"]')).toHaveAttribute("aria-current", "page");

  const layout = await page.evaluate(() => {
    const header = document.querySelector(".site-header").getBoundingClientRect();
    const h1 = document.querySelector("#docs-article h1").getBoundingClientRect();
    return {
      h1Top: h1.top,
      headerBottom: header.bottom,
      overflow: document.documentElement.scrollWidth - document.documentElement.clientWidth,
    };
  });
  expect(layout.h1Top).toBeGreaterThan(layout.headerBottom + 8);
  expect(layout.overflow).toBeLessThanOrEqual(1);
  expect(page.browserIssues).toEqual([]);
});

for (const width of [305, 320]) {
  for (const route of ["", "getting-started/quickstart", "architecture/execution-model"]) {
    test(`docs ${route || "overview"} fits the content width at ${width}px`, async ({ page }) => {
      await page.setViewportSize({ width, height: 900 });
      await gotoTalos(page, `/docs.html#/${route}`, "docs");
      await expect(page.locator("#docs-article h1")).toBeVisible();
      await expectPageFits(page);

      const containers = page.locator(".docs-main, #docs-article, .docs-code, .docs-table-wrap, .docs-diagram");
      const containerCount = await containers.count();
      for (let index = 0; index < containerCount; index += 1) {
        await expectInsideContentWidth(containers.nth(index));
      }
      const articleOverflow = await page.locator(".docs-main, #docs-article").evaluateAll((nodes) =>
        nodes.map((node) => getComputedStyle(node).overflowX),
      );
      for (const overflowX of articleOverflow) {
        expect(["hidden", "clip"]).not.toContain(overflowX);
      }
      // Wide code and tables stay locally scrollable rather than being clipped.
      const scrollableOverflow = await page.locator(".docs-code, .docs-table-wrap").evaluateAll((nodes) =>
        nodes.map((node) => getComputedStyle(node).overflowX),
      );
      for (const overflowX of scrollableOverflow) {
        expect(["auto", "scroll"]).toContain(overflowX);
      }
      expect(page.browserIssues).toEqual([]);
    });
  }
}

test("docs code blocks expose a working copy control", async ({ page, context }) => {
  await context.grantPermissions(["clipboard-read", "clipboard-write"]);
  await gotoTalos(page, "/docs.html#/getting-started/quickstart", "docs");
  const copy = page.locator(".docs-copy").first();
  await expect(copy).toHaveCount(1);
  await copy.click();
  await expect(copy).toHaveText("Copied");
});

for (const slug of ["constructor", "__proto__", "toString", "hasOwnProperty", "missing-review-fixture"]) {
  test(`unknown docs route ${slug} renders not-found and recovers to a real page`, async ({ page }) => {
    await gotoTalos(page, `/docs.html#/${slug}`, "docs");
    await expect(page.locator("#docs-article h1")).toHaveText("Page not found");
    await expect(page.locator("#docs-article code")).toHaveText(slug);
    expect(page.browserIssues).toEqual([]);

    await page.locator('[data-doc-slug="getting-started/quickstart"]').click();
    await expect(page.locator("#docs-article h1")).toHaveText("Quickstart");
    await expect(page).toHaveTitle(/Quickstart \| Talos documentation/);
    await expect(page.locator(".docs-copy").first()).toBeVisible();
    expect(page.browserIssues).toEqual([]);
  });
}

test("docs page keeps in-page Markdown anchors inside the current docs route", async ({ page }) => {
  await gotoTalos(page, "/docs.html#/getting-started/quickstart", "docs");
  await page.getByRole("link", { name: "write test", exact: true }).click();
  await expect(page).toHaveURL(/\/docs(?:\.html)?#\/getting-started\/quickstart#write-test$/);
  await expect(page.locator("#docs-article h1")).toHaveText("Quickstart");
  await expect(page.locator("#write-test")).toBeInViewport();
  expect(page.browserIssues).toEqual([]);
});

for (const width of [320, 390, 1024, 1440]) {
  test(`architecture SVG diagrams retain structure at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await gotoTalos(page, "/docs.html#/architecture/execution-model", "docs");
    await expect(page.locator('.docs-diagram[data-diagram-state="ready"]')).toHaveCount(3);
    await expect(page.locator(".docs-diagram svg")).toHaveCount(3);
    const sequence = page.locator(".docs-diagram").first();
    await expect(sequence.locator("line.actor-line")).toHaveCount(8);
    await expect(sequence.locator(".messageLine0")).toHaveCount(11);
    await expect(sequence.locator(".messageLine1")).toHaveCount(2);
    expect((await sequence.locator("text.messageText").allTextContents()).join(" ")).toContain("answer with limits and evidence pointers");
    await expectPageFits(page);
    await sequence.getByRole("button", { name: "Expand diagram" }).click();
    const dialog = page.getByRole("dialog", { name: "A turn across runtime participants" });
    await expect(dialog).toBeVisible();
    await expect(dialog.getByRole("button", { name: "Close diagram" })).toBeFocused();
    await expect(dialog.locator("svg")).toHaveCount(1);
    await dialog.getByRole("button", { name: "Fit width" }).click();
    await expect(dialog.getByRole("button", { name: "Fit width" })).toHaveAttribute("aria-pressed", "true");
    await page.keyboard.press("Escape");
    await expect(dialog).toHaveCount(0);
    await expect(sequence.getByRole("button", { name: "Expand diagram" })).toBeFocused();
    await expect(sequence.locator("svg")).toHaveCount(1);
    expect(page.browserIssues).toEqual([]);
  });
}

test("architecture maps preserve nodes, groups and decisions", async ({ page }) => {
  await gotoTalos(page, "/docs.html#/architecture/package-map", "docs");
  await expect(page.locator('.docs-diagram[data-diagram-state="ready"]')).toHaveCount(2);
  const packages = page.locator(".docs-diagram").first();
  await expect(packages.locator("g.node")).toHaveCount(9);
  await expect(packages.locator(".flowchart-link")).toHaveCount(12);
  expect((await packages.locator("g.node").allTextContents()).map((text) => text.trim()).sort()).toEqual(["api", "app", "cli", "core", "engine", "runtime", "safety", "spi", "tools"]);

  await page.goto("/docs.html#/architecture/trust-boundaries");
  await expect(page.locator('.docs-diagram[data-diagram-state="ready"]')).toHaveCount(1);
  await expect(page.locator("g.cluster")).toHaveCount(4);
  await expect(page.locator("g.node")).toHaveCount(13);
  const server = page.locator('g.node[id*="-flowchart-Server-"]');
  await expect(server).toHaveCount(1);
  expect((await server.locator("tspan:not(:has(tspan))").allTextContents()).join(" ").replace(/\s+/g, " ").trim()).toBe("Managed or configured server");

  await page.goto("/docs.html#/architecture/model-profile-lifecycle");
  await expect(page.locator('.docs-diagram[data-diagram-state="ready"]')).toHaveCount(1);
  await expect(page.locator("g.node").filter({ hasText: "Disposition review" }).locator("polygon")).toHaveCount(1);
  await expect(page.locator(".flowchart-link")).toHaveCount(16);
  expect(page.browserIssues).toEqual([]);
});

test("diagram failure retains escaped source and readable description", async ({ page }) => {
  await page.route(/mermaid\.core.*\.js/, (route) => route.abort());
  await gotoTalos(page, "/docs.html#/architecture/overview", "docs");
  await expect(page.locator(".docs-diagram")).toHaveAttribute("data-diagram-state", "error");
  await expect(page.locator(".docs-diagram-status")).toContainText("Diagram unavailable");
  await expect(page.locator(".docs-diagram-source")).toHaveAttribute("open", "");
  await expect(page.locator(".docs-diagram-source code")).toContainText("subgraph");
  await expect(page.getByRole("button", { name: "Expand diagram" })).toBeHidden();
  await expect(page.locator(".docs-diagram-description")).toBeVisible();
});

test("late diagram rendering cannot overwrite a newer docs route", async ({ page }) => {
  let release;
  const blocked = new Promise((resolve) => { release = resolve; });
  let requested;
  const requestSeen = new Promise((resolve) => { requested = resolve; });
  await page.route(/mermaid\.core.*\.js/, async (route) => {
    requested();
    await blocked;
    await route.continue();
  });
  await gotoTalos(page, "/docs.html#/architecture/overview", "docs");
  await requestSeen;
  await page.locator('[data-doc-slug="reference/config"]').click();
  release();
  await expect(page.locator("#docs-article h1")).toHaveText("Configuration");
  await expect(page.locator(".docs-diagram")).toHaveCount(0);
  await expect(page.locator(".docs-diagram-stage")).toHaveCount(0);
  expect(page.browserIssues).toEqual([]);
});

test("on-page navigation survives a diagram loaded after the click", async ({ page }) => {
  let release;
  const blocked = new Promise((resolve) => { release = resolve; });
  await page.route(/mermaid\.core.*\.js/, async (route) => {
    await blocked;
    await route.continue();
  });
  await gotoTalos(page, "/docs.html#/architecture/overview", "docs");
  await page.getByRole("navigation", { name: "On this page" }).getByRole("link", { name: "Evidence Model" }).click();
  release();
  await expect(page.locator('.docs-diagram[data-diagram-state="ready"]')).toHaveCount(1);
  await expect(page.locator("#evidence-model")).toBeInViewport();
  await expect(page).toHaveURL(/#evidence-model$/);
  expect(page.browserIssues).toEqual([]);
});

test("mobile copy controls remain visible without hover", async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 900 });
  await gotoTalos(page, "/docs.html#/reference/config", "docs");
  const copy = page.locator(".docs-copy").first();
  await expect(copy).toHaveCSS("opacity", "1");
  const box = await copy.boundingBox();
  expect(box.height).toBeGreaterThanOrEqual(36);
  await expectPageFits(page);
});

test("source SVG admission rejects active and external content", async ({ page }) => {
  await gotoTalos(page, "/docs.html#/reference/config", "docs");
  const moduleSource = readFileSync(new URL("../../src/docs-diagrams.js", import.meta.url), "utf8");
  const checks = await page.evaluate(async (source) => {
    const url = URL.createObjectURL(new Blob([source], { type: "text/javascript" }));
    try {
      const { parseDiagramSvg } = await import(url);
      const wrap = (body) => `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 20 20">${body}</svg>`;
      const bad = [
        '<script>alert(1)</script>',
        '<foreignObject><div xmlns="http://www.w3.org/1999/xhtml">active</div></foreignObject>',
        '<rect onload="alert(1)"/>',
        '<image href="https://example.com/image.svg"/>',
        '<a href="https://example.com"><text>Link</text></a>',
        '<use href="https://example.com/image.svg#node"/>',
        '<g xml:base="https://example.com/actors.svg"><use href="#node"/></g>',
        '<style>@import "https://example.com/style.css"</style>',
        '<rect style="fill:url(https://example.com/paint.svg)"/>',
      ];
      return {
        rejected: bad.map((body) => {
          try { parseDiagramSvg(wrap(body)); return false; } catch { return true; }
        }),
        valid: parseDiagramSvg(wrap('<defs><symbol id="node"><rect width="10" height="10"/></symbol></defs><use href="#node"/>')).getAttribute("role"),
      };
    } finally {
      URL.revokeObjectURL(url);
    }
  }, moduleSource);
  expect(checks.rejected).toEqual(Array(9).fill(true));
  expect(checks.valid).toBe("img");
  expect(page.browserIssues).toEqual([]);
});

for (const width of [305, 320]) {
  test(`mobile header and nav remain usable at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 780 });
    await gotoTalos(page);
    await expectPageFits(page);
    const menuTrigger = page.getByRole("button", { name: "Open menu" });
    await expectInsideContentWidth(menuTrigger);
    await menuTrigger.click();

    const menu = page.getByRole("dialog", { name: "Talos sections" });
    await expect(menu).toBeVisible();
    await expectInsideContentWidth(menu);
    await expectInsideContentWidth(menu.locator('a[href="#overview"]'));
    await expectInsideContentWidth(menu.locator('a[href="#docs"]'));
    await expect(menu.getByRole("link", { name: "Read docs" })).toHaveAttribute("href", "./docs.html");
    await expect(menu.getByRole("link", { name: "Read docs" })).toHaveAttribute("target", "_blank");
    await expect(menu.getByRole("link", { name: "View on GitHub" })).toHaveAttribute("target", "_blank");

    await menu.locator('a[href="#docs"]').click();
    await expect(page.locator("#docs")).toBeInViewport();
    await expect(menuTrigger).toHaveAttribute("aria-expanded", "false");
    await expect(menu).toBeHidden();
    await expectPageFits(page);
  });
}

test("small tablet header uses the compact menu instead of an empty top bar", async ({ page }) => {
  await page.setViewportSize({ width: 1024, height: 900 });
  await gotoTalos(page);
  await expect(page.locator(".header-actions")).toBeHidden();
  await expect(page.locator(".site-nav")).toHaveCount(0);
  const menuTrigger = page.getByRole("button", { name: "Open menu" });
  await expect(menuTrigger).toBeVisible();
  await menuTrigger.click();
  const menu = page.getByRole("dialog", { name: "Talos sections" });
  await expect(menu.getByRole("link", { name: "Read docs" })).toHaveAttribute("href", "./docs.html");
  await expect(menu.getByRole("link", { name: "Read docs" })).toHaveAttribute("target", "_blank");
  await expect(menu.getByRole("link", { name: "View on GitHub" })).toHaveAttribute(
    "href",
    "https://github.com/ai21z/talos-assistant",
  );
  await expect(menu.getByRole("link", { name: "View on GitHub" })).toHaveAttribute("target", "_blank");
});

test("mobile hero content fits without horizontal clipping", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 900 });
  await gotoTalos(page);
  await expectPageFits(page);

  for (const selector of [
    "h1",
    ".setup-strip",
    ".machine-note",
    ".hero-visual",
    ".live-terminal",
    ".hero-inscription",
  ]) {
    await expectInsideContentWidth(page.locator(selector));
  }
});

for (const width of [305, 320]) {
  test(`mobile public beta tooltip stays inside the content width at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 780 });
    await gotoTalos(page);
    await page.locator(".setup-info").focus();
    const tooltip = page.getByRole("tooltip");
    await expect.poll(() => tooltip.evaluate((node) => Number(getComputedStyle(node).opacity))).toBeGreaterThan(0.9);
    // The hero clips overflow, so a fitting document alone cannot prove this.
    const bounds = await tooltip.evaluate((node) => {
      const tooltipRect = node.getBoundingClientRect();
      const strip = node.closest(".setup-strip");
      const stripRect = strip.getBoundingClientRect();
      const stripStyle = getComputedStyle(strip);
      return {
        left: tooltipRect.left,
        right: tooltipRect.right,
        contentLeft: stripRect.left + parseFloat(stripStyle.borderLeftWidth) + parseFloat(stripStyle.paddingLeft),
        contentRight: stripRect.right - parseFloat(stripStyle.borderRightWidth) - parseFloat(stripStyle.paddingRight),
      };
    });
    expect(bounds.left, "tooltip stays inside the setup strip content box").toBeGreaterThanOrEqual(bounds.contentLeft - 1);
    expect(bounds.right, "tooltip stays inside the setup strip content box").toBeLessThanOrEqual(bounds.contentRight + 1);
    await expectInsideContentWidth(tooltip);
    await expectPageFits(page);
  });
}

for (const width of [305, 320, 390, 768, 1024, 1440]) {
  test(`install command wraps without an internal horizontal scrollbar at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await gotoTalos(page);
    const metrics = await page.locator("#setup-command").evaluate((el) => ({
      clientWidth: el.clientWidth,
      scrollWidth: el.scrollWidth,
      overflowX: getComputedStyle(el).overflowX,
    }));
    expect(metrics.overflowX).toBe("hidden");
    expect(metrics.scrollWidth).toBeLessThanOrEqual(metrics.clientWidth + 1);
  });
}

for (const width of [305, 320, 375, 390]) {
  test(`mobile live terminal fits after answer renders at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await gotoTalos(page);
    await page.locator("[data-live-replay]").click();
    await expect(page.locator("[data-live-output]")).toContainText("/last trace", { timeout: 9000 });

    for (const selector of [
      ".hero-inscription",
      ".live-terminal",
      ".live-terminal .terminal",
      "[data-live-output]",
      ".live-terminal .terminal-foot",
      "#live-terminal-caption",
    ]) {
      await expectInsideContentWidth(page.locator(selector));
    }

    const terminalOverflow = await page.locator("[data-live-output]").evaluate((node) =>
      node.scrollWidth - node.clientWidth,
    );
    expect(terminalOverflow).toBeLessThanOrEqual(1);
  });
}

test("reduced-motion mode leaves content visible without reveal animations", async ({ page }) => {
  await page.emulateMedia({ reducedMotion: "reduce" });
  await gotoTalos(page);
  const hiddenRevealCount = await page.locator(".reveal").evaluateAll((nodes) =>
    nodes.filter((node) => {
      const style = window.getComputedStyle(node);
      return style.opacity === "0" || style.visibility === "hidden";
    }).length,
  );
  expect(hiddenRevealCount).toBe(0);
  await expect(page.locator("h1")).toBeVisible();
  await expect(page.locator(".hero-inscription-mark")).toBeVisible();
  // The live terminal still renders its content statically.
  await expect(page.locator("[data-live-output]")).toContainText("Local-first CLI workspace operator");
  // Staggered groups are shown immediately (no cascade) under reduced motion.
  const cardOpacity = await page
    .locator(".doc-card")
    .first()
    .evaluate((node) => getComputedStyle(node).opacity);
  expect(Number(cardOpacity)).toBe(1);
});

for (const width of [320, 390, 760, 768, 1280]) {
  test(`dynamic panel geometry stays stable at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await gotoTalos(page);
    await page.evaluate(() => document.fonts.ready);
    await page.locator('[data-live-replay]').click();
    await expect(page.locator('[data-live-state]')).toHaveText('ready', { timeout: 9000 });
    await page.evaluate(() => {
      window.panelSamples = [];
      const measure = () => {
        const panel = document.querySelector('[data-live-terminal]').getBoundingClientRect();
        window.panelSamples.push({ height: panel.height, top: panel.top + scrollY });
      };
      measure();
      window.panelObserver = new MutationObserver(measure);
      window.panelObserver.observe(document.querySelector('[data-live-output]'), { childList: true, subtree: true });
    });
    await page.locator('[data-live-replay]').click();
    await expect(page.locator('[data-live-state]')).toHaveText('run');
    await expect(page.locator('[data-live-state]')).toHaveText('ready', { timeout: 9000 });
    const samples = await page.evaluate(() => {
      window.panelObserver.disconnect();
      return window.panelSamples;
    });
    expect(samples.length).toBeGreaterThan(10);
    for (const key of ['height', 'top']) {
      const values = samples.map((sample) => sample[key]);
      expect(Math.max(...values) - Math.min(...values), `hero ${key}`).toBeLessThanOrEqual(1);
    }
    const readGeometry = async (selector) => page.locator(selector).evaluate((node) => {
      const r = node.getBoundingClientRect();
      return { height: r.height, top: r.top + scrollY, complete: node.scrollHeight <= node.clientHeight + 1 };
    });
    const turns = [];
    for (const name of ['Inspect', 'Approve', 'Verify', 'Trace']) {
      await page.getByRole('tab', { name, exact: true }).click();
      turns.push(await readGeometry('#terminal-output'));
    }
    for (const key of ['height', 'top']) {
      expect(Math.max(...turns.map(x => x[key])) - Math.min(...turns.map(x => x[key])), `turn ${key}`).toBeLessThanOrEqual(1);
    }
    expect(turns.every(x => x.complete)).toBe(true);
    const commands = [];
    for (const name of ['Linux', 'Windows']) {
      await page.getByRole('tab', { name, exact: true }).click();
      commands.push(await readGeometry('#setup-command'));
    }
    expect(Math.abs(commands[0].height - commands[1].height)).toBeLessThanOrEqual(1);
    expect(commands.every(x => x.complete)).toBe(true);
    await expectPageFits(page);
    expect(page.browserIssues).toEqual([]);
  });
}

test('sizing variants remain inert and identifiers stay unique after resizing', async ({ page }) => {
  await page.emulateMedia({ reducedMotion: 'reduce' });
  await gotoTalos(page);
  await expect(page.locator('.panel-size-reserve').first()).toBeAttached();
  const admission = await page.locator('.panel-size-reserve').evaluateAll(nodes => ({
    inert: nodes.every(node => node.inert && node.getAttribute('aria-hidden') === 'true' && getComputedStyle(node).visibility === 'hidden'),
    hooks: nodes.flatMap(node => [node, ...node.querySelectorAll('*')]).flatMap(node => [...node.attributes]).filter(a => /^(id|role|name|tabindex)$|^data-/.test(a.name)).length,
  }));
  expect(admission).toEqual({ inert: true, hooks: 0 });
  for (const width of [1280, 320, 921, 920]) {
    await page.setViewportSize({ width, height: 900 });
    await page.evaluate(() => document.fonts.ready);
    const heights = [];
    for (const name of ['Trace', 'Approve']) {
      await page.getByRole('tab', { name, exact: true }).click();
      heights.push(await page.locator('#terminal-output').evaluate(node => node.getBoundingClientRect().height));
    }
    expect(Math.abs(heights[0] - heights[1])).toBeLessThanOrEqual(1);
  }
  const ids = await page.locator('[id]').evaluateAll(nodes => nodes.map(n => n.id));
  expect(new Set(ids).size).toBe(ids.length);
  await expect(page.locator('[data-live-output]')).toHaveCount(1);
  await expect(page.locator('#setup-command code')).toHaveCount(1);
});

test('mobile capabilities retain every group and leaf without a miniature graph', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 900 });
  await gotoTalos(page);
  const compact = page.locator('.scope-compact');
  await expect(compact).toBeVisible();
  await expect(page.locator('.scope-layout')).toBeHidden();
  await expect(compact.locator('details')).toHaveCount(5);
  await expect(compact.locator('li')).toHaveCount(22);
  await expect(compact).toContainText('keeps model turns on localhost unless you allow remote endpoints');
  for (const summary of await compact.locator('summary').all()) {
    await summary.press('Enter');
    await expect(summary.locator('..')).toHaveAttribute('open', '');
  }
  const graphLabels = await page.locator('.tree-node--leaf').evaluateAll(nodes => nodes.map(n => n.getAttribute('aria-label')).sort());
  const listLabels = await compact.locator('li').evaluateAll(nodes => nodes.map(n => `${n.querySelector('strong').textContent}, ${n.querySelector('.scope-compact-status').textContent}`).sort());
  expect(listLabels).toEqual(graphLabels);
  await expectPageFits(page);
  await page.setViewportSize({ width: 921, height: 900 });
  await expect(page.locator('.scope-layout')).toBeVisible();
  await expect(compact).toBeHidden();
  await page.setViewportSize({ width: 920, height: 900 });
  await expect(compact).toBeVisible();
  await expect(page.locator('.scope-layout')).toBeHidden();
});

test('desktop capability descriptions reserve all selectable states', async ({ page }) => {
  await page.emulateMedia({ reducedMotion: 'reduce' });
  await page.setViewportSize({ width: 1280, height: 900 });
  await gotoTalos(page);
  const heights = [];
  for (const node of await page.locator('.tree-node').all()) {
    await node.focus();
    heights.push(await page.locator('[data-scope-detail]').evaluate(el => el.getBoundingClientRect().height));
  }
  expect(heights).toHaveLength(28);
  expect(Math.max(...heights) - Math.min(...heights)).toBeLessThanOrEqual(1);
});

test('script-free capability and docs fallbacks remain available', async ({ browser, baseURL }) => {
  const context = await browser.newContext({ javaScriptEnabled: false, viewport: { width: 320, height: 900 } });
  try {
    const page = await context.newPage();
    await page.goto(`${baseURL}/`);
    await expect(page.locator('.scope-fallback')).toBeVisible();
    await page.goto(`${baseURL}/docs.html`);
    const fallback = page.locator('noscript a');
    await expect(fallback).toBeVisible();
    await expect(fallback).toHaveAttribute('href', 'https://github.com/ai21z/talos-assistant/tree/main/docs');
    await expect(page.locator('noscript p')).toBeVisible();
    await expect(page.locator('noscript p')).toContainText('This may include unreleased changes');
  } finally {
    await context.close();
  }
});

for (const width of [390, 1280]) {
  test(`docs skip and navigation focus preserve routes at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    for (const slug of ['', 'architecture/overview', 'missing-focus-fixture']) {
      await gotoTalos(page, `/docs.html#/${slug}`, 'docs');
      const url = page.url();
      if (width < 860) await page.getByRole('button', { name: 'Documentation menu' }).click();
      await page.getByRole('link', { name: 'Skip to content', exact: true }).press('Enter');
      await expect(page).toHaveURL(url);
      await expect(page.locator('#docs-article h1')).toBeFocused();
      await expect(page.locator('#docs-article h1')).toBeInViewport();
      if (width < 860) await page.getByRole('button', { name: 'Documentation menu' }).click();
    }
    if (width < 860) await page.getByRole('button', { name: 'Documentation menu' }).click();
    await page.locator('[data-doc-slug="architecture/overview"]').click();
    await expect(page.locator('#docs-article h1')).toBeFocused();
    if (width < 860) {
      await expect(page.getByRole('button', { name: 'Documentation menu' })).toHaveAttribute('aria-expanded', 'false');
      await page.getByRole('button', { name: 'Documentation menu' }).click();
    }
    await page.locator('[data-doc-slug="architecture/overview"]').click();
    await expect(page.locator('#docs-article h1')).toBeFocused();
    const anchor = page.getByRole('navigation', { name: 'On this page' }).getByRole('link', { name: 'Evidence Model' });
    await anchor.click();
    await expect(page.locator('#evidence-model')).toBeFocused();
    await anchor.click();
    await expect(page.locator('#evidence-model')).toBeFocused();
    await page.goBack();
    await expect(page.locator('#docs-article h1')).toBeFocused();
    await page.goForward();
    await expect(page.locator('#evidence-model')).toBeFocused();
    expect(page.browserIssues).toEqual([]);
  });
}

test('late diagram completion does not reclaim reader focus', async ({ page }) => {
  let release;
  const barrier = new Promise(resolve => { release = resolve; });
  await page.route(/\/assets\/mermaid\.core[^/]*\.js/, async route => {
    await barrier;
    await route.continue();
  });
  await gotoTalos(page, '/docs.html#/architecture/overview', 'docs');
  await page.getByRole('navigation', { name: 'On this page' }).getByRole('link', { name: 'Evidence Model' }).click();
  await expect(page.locator('#evidence-model')).toBeFocused();
  await page.keyboard.press('Tab');
  const focused = await page.evaluate(() => document.activeElement.outerHTML);
  release();
  await expect(page.locator('.docs-diagram[data-diagram-state="ready"]')).toHaveCount(1);
  expect(await page.evaluate(() => document.activeElement.outerHTML)).toBe(focused);
});
