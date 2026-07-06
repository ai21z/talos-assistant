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

async function expectTalosPage(page, marker) {
  await expect(page).toHaveTitle(/Talos/);
  await expect(page.locator(`body[data-talos-site="${marker}"]`)).toHaveCount(1);
}

async function gotoTalos(page, path = "/", marker = "landing") {
  await page.goto(path);
  await expectTalosPage(page, marker);
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
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
    expect(overflow).toBeLessThanOrEqual(1);
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
      overflow: document.documentElement.scrollWidth - window.innerWidth,
    };
  });
  expect(layout.h1Top).toBeGreaterThan(layout.headerBottom + 8);
  expect(layout.overflow).toBeLessThanOrEqual(1);
  expect(page.browserIssues).toEqual([]);
});

test("docs code blocks expose a working copy control", async ({ page, context }) => {
  await context.grantPermissions(["clipboard-read", "clipboard-write"]);
  await gotoTalos(page, "/docs.html#/getting-started/quickstart", "docs");
  const copy = page.locator(".docs-copy").first();
  await expect(copy).toHaveCount(1);
  await copy.click();
  await expect(copy).toHaveText("Copied");
});

test("docs page keeps in-page Markdown anchors inside the current docs route", async ({ page }) => {
  await gotoTalos(page, "/docs.html#/getting-started/quickstart", "docs");
  await page.getByRole("link", { name: "write test" }).click();
  await expect(page).toHaveURL(/\/docs\.html#\/getting-started\/quickstart#write-test$/);
  await expect(page.locator("#docs-article h1")).toHaveText("Quickstart");
  await expect(page.locator("#write-test")).toBeInViewport();
  expect(page.browserIssues).toEqual([]);
});

test("mobile architecture diagrams keep arrows visible and sequence cards readable", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 900 });
  await gotoTalos(page, "/docs.html#/architecture/execution-model", "docs");

  const layout = await page.evaluate(() => {
    const sequenceArrow = document.querySelector(".docs-sequence-arrow");
    const sequenceStep = document.querySelector(".docs-sequence-message .docs-diagram-step");
    const flowArrow = document.querySelector(".docs-flow-path .docs-flow-arrow");
    const measure = (node) => {
      const rect = node.getBoundingClientRect();
      const style = getComputedStyle(node);
      return {
        display: style.display,
        height: rect.height,
        width: rect.width,
      };
    };

    return {
      flowArrow: measure(flowArrow),
      overflow: document.documentElement.scrollWidth - window.innerWidth,
      sequenceArrow: measure(sequenceArrow),
      sequenceStep: measure(sequenceStep),
    };
  });

  expect(layout.overflow).toBeLessThanOrEqual(1);
  expect(layout.sequenceArrow.display).not.toBe("none");
  expect(layout.sequenceArrow.height).toBeGreaterThan(8);
  expect(layout.sequenceStep.width).toBeLessThanOrEqual(32);
  expect(layout.flowArrow.display).not.toBe("none");
  expect(layout.flowArrow.height).toBeGreaterThan(8);
  expect(page.browserIssues).toEqual([]);
});

test("desktop architecture sequence diagrams keep actor transitions compact", async ({ page }) => {
  await page.setViewportSize({ width: 1024, height: 900 });
  await gotoTalos(page, "/docs.html#/architecture/execution-model", "docs");

  const layout = await page.evaluate(() => {
    const sequence = document.querySelector(".docs-sequence-message");
    const actors = sequence.querySelectorAll("strong");
    const textRect = (node) => {
      const range = document.createRange();
      range.selectNodeContents(node);
      return range.getBoundingClientRect();
    };
    const source = textRect(actors[0]);
    const target = textRect(actors[1]);
    const arrow = sequence.querySelector(".docs-sequence-arrow").getBoundingClientRect();
    const message = sequence.querySelector("p").getBoundingClientRect();

    return {
      arrowToTargetGap: target.left - arrow.right,
      sourceToArrowGap: arrow.left - source.right,
      messageBelowActors: message.top > source.bottom,
      overflow: document.documentElement.scrollWidth - window.innerWidth,
    };
  });

  expect(layout.overflow).toBeLessThanOrEqual(1);
  expect(layout.sourceToArrowGap).toBeLessThanOrEqual(24);
  expect(layout.arrowToTargetGap).toBeLessThanOrEqual(24);
  expect(layout.messageBelowActors).toBe(true);
  expect(page.browserIssues).toEqual([]);
});

test("mobile header and nav remain usable", async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 780 });
  await gotoTalos(page);
  const menuTrigger = page.getByRole("button", { name: "Open menu" });
  await expect(menuTrigger).toBeVisible();
  await menuTrigger.click();

  const menu = page.getByRole("dialog", { name: "Talos sections" });
  await expect(menu).toBeVisible();
  await expect(menu.locator('a[href="#overview"]')).toBeVisible();
  await expect(menu.locator('a[href="#docs"]')).toBeVisible();
  await expect(menu.getByRole("link", { name: "Read docs" })).toHaveAttribute("href", "./docs.html");
  await expect(menu.getByRole("link", { name: "Read docs" })).toHaveAttribute("target", "_blank");
  await expect(menu.getByRole("link", { name: "View on GitHub" })).toHaveAttribute("target", "_blank");

  await menu.locator('a[href="#docs"]').click();
  await expect(page.locator("#docs")).toBeInViewport();
  await expect(menuTrigger).toHaveAttribute("aria-expanded", "false");
});

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
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
  expect(overflow).toBeLessThanOrEqual(1);

  for (const selector of [
    "h1",
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

test("mobile public beta tooltip stays inside the viewport", async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 780 });
  await gotoTalos(page);
  await page.locator(".setup-info").focus();
  const tooltip = await page.locator(".setup-tooltip").boundingBox();
  expect(tooltip, "setup tooltip should render").not.toBeNull();
  expect(tooltip.x, "tooltip left edge").toBeGreaterThanOrEqual(0);
  expect(tooltip.x + tooltip.width, "tooltip right edge").toBeLessThanOrEqual(320 + 1);
});

for (const width of [320, 390, 768, 1024, 1440]) {
  test(`install command wraps without an internal horizontal scrollbar at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await gotoTalos(page);
    const metrics = await page.locator(".setup-command").evaluate((el) => ({
      clientWidth: el.clientWidth,
      scrollWidth: el.scrollWidth,
      overflowX: getComputedStyle(el).overflowX,
    }));
    expect(metrics.overflowX).toBe("hidden");
    expect(metrics.scrollWidth).toBeLessThanOrEqual(metrics.clientWidth + 1);
  });
}

for (const width of [320, 375, 390]) {
  test(`mobile live terminal fits after answer renders at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await gotoTalos(page);
    await page.locator("[data-live-replay]").click();
    await expect(page.locator("[data-live-output]")).toContainText("/last trace", { timeout: 9000 });

    for (const selector of [
      ".hero-inscription",
      ".live-terminal",
      ".live-terminal .terminal",
      ".live-terminal .terminal-screen",
      ".live-terminal .terminal-foot",
      "#live-terminal-caption",
    ]) {
      const box = await page.locator(selector).boundingBox();
      expect(box, `${selector} should render`).not.toBeNull();
      expect(box.x, `${selector} left edge at ${width}px`).toBeGreaterThanOrEqual(0);
      expect(box.x + box.width, `${selector} right edge at ${width}px`).toBeLessThanOrEqual(width + 1);
    }

    const terminalOverflow = await page.locator(".live-terminal .terminal-screen").evaluate((node) =>
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
