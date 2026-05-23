import { expect, test } from "@playwright/test";

const widths = [320, 375, 390, 768, 1024, 1440];

test.beforeEach(async ({ page }) => {
  const browserIssues = [];
  page.on("console", (message) => {
    if (["error", "warning"].includes(message.type())) {
      browserIssues.push(`${message.type()}: ${message.text()}`);
    }
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

test("scroll story sections keep active nav state without hijacking native scroll", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto("/");
  const primaryNav = page.getByRole("navigation", { name: "Primary navigation" });
  await expect(page.locator('.site-nav a[aria-current="page"]')).toHaveText("Overview");

  await primaryNav.getByRole("link", { name: "Local Boundaries" }).click();
  await expect(page).toHaveURL(/#local-boundaries$/);
  await expect(page.locator("#local-boundaries")).toBeInViewport();
  await expect(page.locator('.site-nav a[aria-current="page"]')).toHaveText("Local Boundaries");

  const scrollState = await page.evaluate(() => ({
    overflowY: getComputedStyle(document.documentElement).overflowY,
    snapped: getComputedStyle(document.documentElement).scrollSnapType,
    executionMinHeight: getComputedStyle(document.querySelector("#execution")).minHeight,
    expectedStoryHeight: `${window.innerHeight - 72}px`,
  }));

  expect(scrollState.overflowY).not.toBe("hidden");
  expect(scrollState.snapped).not.toMatch(/mandatory/i);
  expect(scrollState.executionMinHeight).toBe(scrollState.expectedStoryHeight);

  await page.locator("#docs").scrollIntoViewIfNeeded();
  await expect(page.locator("#docs")).toBeInViewport();
  await expect(page.locator('.site-nav a[aria-current="page"]')).toHaveText("Docs");
});

test("desktop story handoff overlaps adjacent screens during scroll", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto("/");
  await page.evaluate(() => {
    document.documentElement.style.scrollBehavior = "auto";
    window.scrollTo({ top: 700, behavior: "instant" });
  });
  const handoffHandle = await page.waitForFunction(() => {
    const overviewNode = document.querySelector("#overview > .container");
    const executionNode = document.querySelector("#execution > .container");
    const overview = overviewNode.getBoundingClientRect();
    const execution = executionNode.getBoundingClientRect();
    const handoff = {
      overviewBottom: overview.bottom,
      executionTop: execution.top,
      overviewOpacity: Number(getComputedStyle(overviewNode).opacity),
      executionOpacity: Number(getComputedStyle(executionNode).opacity),
      executionSectionBackground: getComputedStyle(document.querySelector("#execution")).backgroundImage,
      executionBeforeDisplay: getComputedStyle(document.querySelector("#execution"), "::before").display,
    };
    return handoff.overviewOpacity < 0.25 && handoff.executionOpacity > 0.65 ? handoff : false;
  });
  const handoff = await handoffHandle.jsonValue();

  expect(handoff.overviewBottom).toBeGreaterThan(220);
  expect(handoff.executionTop).toBeLessThan(460);
  expect(handoff.executionOpacity).toBeGreaterThan(0.65);
  expect(handoff.overviewOpacity).toBeLessThan(0.25);
  expect(handoff.executionSectionBackground).toBe("none");
  expect(handoff.executionBeforeDisplay).toBe("none");
});

test("desktop story screens keep primary content centered across viewport heights", async ({ page }) => {
  const viewports = [
    { width: 1440, height: 900, maxDelta: 56 },
    { width: 1366, height: 768, maxDelta: 64 },
    { width: 1280, height: 720, maxDelta: 72 },
  ];

  for (const viewport of viewports) {
    await page.setViewportSize({ width: viewport.width, height: viewport.height });
    await page.goto("/");
    await page.evaluate(() => {
      document.documentElement.style.scrollBehavior = "auto";
    });

    for (const sectionId of ["overview", "execution", "turn-ui"]) {
      await page.evaluate((targetId) => {
        const section = document.getElementById(targetId);
        window.scrollTo({ top: section.offsetTop - 72, behavior: "instant" });
      }, sectionId);
      const metricsHandle = await page.waitForFunction((targetId) => {
        const section = document.getElementById(targetId);
        const container = section.querySelector(":scope > .container");
        const children = Array.from(container.children).filter((node) => {
          const style = window.getComputedStyle(node);
          return style.display !== "none" && style.visibility !== "hidden";
        });
        const rects = children
          .map((node) => node.getBoundingClientRect())
          .filter((rect) => rect.width > 0 && rect.height > 0);
        const top = Math.min(...rects.map((rect) => rect.top));
        const bottom = Math.max(...rects.map((rect) => rect.bottom));
        const contentCenter = (top + bottom) / 2;
        const viewportCenter = (72 + window.innerHeight) / 2;
        const metrics = {
          delta: contentCenter - viewportCenter,
          opacity: Number(window.getComputedStyle(container).opacity),
        };
        return Math.abs(metrics.delta) <= 72 && metrics.opacity > 0.86 ? metrics : false;
      }, sectionId);
      const metrics = await metricsHandle.jsonValue();

      expect(Math.abs(metrics.delta), `${sectionId} center at ${viewport.width}x${viewport.height}`).toBeLessThanOrEqual(
        viewport.maxDelta,
      );
      expect(metrics.opacity, `${sectionId} opacity at ${viewport.width}x${viewport.height}`).toBeGreaterThan(0.86);
    }
  }
});

test("primary story nav lands on the requested centered screen", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto("/");
  await page.evaluate(() => {
    document.documentElement.style.scrollBehavior = "auto";
  });

  const primaryNav = page.getByRole("navigation", { name: "Primary navigation" });

  for (const target of [
    { label: "Execution", id: "execution" },
    { label: "Turn UI", id: "turn-ui" },
    { label: "Local Boundaries", id: "local-boundaries" },
    { label: "Turn UI", id: "turn-ui" },
    { label: "Execution", id: "execution" },
    { label: "Overview", id: "overview" },
  ]) {
    await primaryNav.getByRole("link", { name: target.label }).click();
    await expect(page).toHaveURL(new RegExp(`#${target.id}$`));
    await expect(page.locator('.site-nav a[aria-current="page"]')).toHaveText(target.label);

    const metrics = await page.waitForFunction(
      (sectionId) => {
      const section = document.getElementById(sectionId);
      const container = section.querySelector(":scope > .container");
      const children = Array.from(container.children).filter((node) => {
        const style = window.getComputedStyle(node);
        return style.display !== "none" && style.visibility !== "hidden";
      });
      const rects = children
        .map((node) => node.getBoundingClientRect())
        .filter((rect) => rect.width > 0 && rect.height > 0);
      const top = Math.min(...rects.map((rect) => rect.top));
      const bottom = Math.max(...rects.map((rect) => rect.bottom));
      const contentCenter = (top + bottom) / 2;
      const viewportCenter = (72 + window.innerHeight) / 2;
      const metrics = {
        delta: contentCenter - viewportCenter,
        opacity: Number(window.getComputedStyle(container).opacity),
      };
      return Math.abs(metrics.delta) <= 64 && metrics.opacity > 0.86 ? metrics : false;
      },
      target.id,
    );

    const resolvedMetrics = await metrics.jsonValue();
    expect(Math.abs(resolvedMetrics.delta), `${target.id} nav center`).toBeLessThanOrEqual(64);
    expect(resolvedMetrics.opacity, `${target.id} nav opacity`).toBeGreaterThan(0.86);
  }
});

test("hero startup terminal image loads", async ({ page }) => {
  await page.goto("/");
  const image = page.locator(".startup-terminal-image");
  await expect(image).toHaveAttribute("src", /(?:\/assets\/img-[^/]+\.png|\.\/design\/img\.png)$/);
  await expect(image).toHaveAttribute("alt", /Talos startup terminal screen/);
  const loaded = await image.evaluate((node) => node instanceof HTMLImageElement && node.complete && node.naturalWidth > 0);
  expect(loaded).toBe(true);
});

test("hero inscription cycles TALOS, Greek, then terminal-typed product phrases", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto("/");

  const inscription = page.locator(".greek-hero-inscription");
  const english = page.locator(".hero-inscription-layer--english");
  const greek = page.locator(".hero-inscription-layer--greek");
  const terminal = page.locator(".hero-inscription-layer--terminal");
  const image = page.locator(".startup-terminal-image");

  await expect(english).toHaveText("TALOS");
  await expect(greek).toHaveText("ΤΑΛΩΣ");
  for (const phrase of [
    "local operator",
    "local model harness",
    "guard your workspace",
  ]) {
    await expect(terminal).toContainText(phrase);
  }
  await expect(terminal).not.toContainText(/approval before mutation|trace every turn|last trace/i);
  await expect(inscription).toBeVisible();
  await expect(image).toBeVisible();

  const visualOrder = await page.evaluate(() => {
    const inscriptionNode = document.querySelector(".greek-hero-inscription");
    const englishNode = document.querySelector(".hero-inscription-layer--english");
    const greekNode = document.querySelector(".hero-inscription-layer--greek");
    const terminalNode = document.querySelector(".hero-inscription-layer--terminal");
    const promptNode = document.querySelector(".hero-terminal-prompt");
    const textNode = document.querySelector(".hero-terminal-text");
    const imageNode = document.querySelector(".startup-terminal-image");
    const inscription = inscriptionNode.getBoundingClientRect();
    const image = imageNode.getBoundingClientRect();
    const styles = window.getComputedStyle(inscriptionNode);
    const englishStyles = window.getComputedStyle(englishNode);
    const greekStyles = window.getComputedStyle(greekNode);
    const terminalStyles = window.getComputedStyle(terminalNode);
    const promptStyles = window.getComputedStyle(promptNode);
    const textStyles = window.getComputedStyle(textNode);
    return {
      inscriptionTop: inscription.top,
      inscriptionLeft: inscription.left,
      inscriptionRight: inscription.right,
      inscriptionHeight: inscription.height,
      imageTop: image.top,
      imageHeight: image.height,
      color: styles.color,
      fontFamily: styles.fontFamily,
      englishColor: englishStyles.color,
      greekColor: greekStyles.color,
      terminalColor: terminalStyles.color,
      promptColor: promptStyles.color,
      textColor: textStyles.color,
      englishFontFamily: englishStyles.fontFamily,
      greekFontFamily: greekStyles.fontFamily,
      terminalFontFamily: terminalStyles.fontFamily,
      englishAnimation: englishStyles.animationName,
      greekAnimation: greekStyles.animationName,
      terminalAnimation: terminalStyles.animationName,
      terminalLineHeight: terminalStyles.lineHeight,
      terminalTextAlign: terminalStyles.textAlign,
    };
  });

  expect(visualOrder.inscriptionTop).toBeLessThan(visualOrder.imageTop);
  expect(visualOrder.inscriptionHeight).toBeLessThan(visualOrder.imageHeight);
  expect(visualOrder.inscriptionLeft).toBeGreaterThanOrEqual(0);
  expect(visualOrder.inscriptionRight).toBeLessThanOrEqual(1440);
  expect(visualOrder.color).toBe("rgb(194, 138, 76)");
  expect(visualOrder.fontFamily).toContain("GFS Neohellenic");
  expect(visualOrder.englishColor).toBe("rgb(194, 138, 76)");
  expect(visualOrder.greekColor).toBe("rgb(194, 138, 76)");
  expect(visualOrder.terminalColor).toBe("rgb(243, 236, 223)");
  expect(visualOrder.promptColor).toBe("rgb(95, 175, 207)");
  expect(visualOrder.textColor).toBe("rgb(243, 236, 223)");
  expect(visualOrder.englishFontFamily).toContain("GFS Neohellenic");
  expect(visualOrder.greekFontFamily).toContain("GFS Neohellenic");
  expect(visualOrder.terminalFontFamily).toContain("Consolas");
  expect(visualOrder.englishAnimation).toBe("talos-inscription-english");
  expect(visualOrder.greekAnimation).toBe("talos-inscription-greek");
  expect(visualOrder.terminalAnimation).toBe("talos-inscription-terminal");
  expect(visualOrder.terminalTextAlign).toBe("left");

  const terminalPhrasePhases = await page.evaluate(() => {
    const terminalNode = document.querySelector(".hero-inscription-layer--terminal");
    const lines = Array.from(document.querySelectorAll(".hero-terminal-line"));
    terminalNode.style.animationDelay = "-20s";
    terminalNode.style.animationPlayState = "paused";
    const setLinePhase = (seconds) => {
      for (const line of lines) {
        line.style.animationDelay = `-${seconds}s`;
        line.style.animationPlayState = "paused";
      }
      return lines.map((line) => ({
        text: line.textContent.trim().replace(/\s+/g, " "),
        opacity: Number(window.getComputedStyle(line).opacity),
        width: line.getBoundingClientRect().width,
        scrollWidth: line.scrollWidth,
      }));
    };
    return {
      first: setLinePhase(15.5),
      second: setLinePhase(18.8),
      third: setLinePhase(22),
    };
  });
  const assertOneActivePhrase = (phase, activeText) => {
    const active = phase.filter((line) => line.opacity > 0.75);
    expect(active.map((line) => line.text)).toEqual([activeText]);
    expect(active[0].width + 1, `${activeText} line should not clip typed content`).toBeGreaterThanOrEqual(
      active[0].scrollWidth,
    );
  };
  assertOneActivePhrase(terminalPhrasePhases.first, "> local operator");
  assertOneActivePhrase(terminalPhrasePhases.second, "> local model harness");
  assertOneActivePhrase(terminalPhrasePhases.third, "> guard your workspace");

  const phases = await page.evaluate(() => {
    const englishNode = document.querySelector(".hero-inscription-layer--english");
    const greekNode = document.querySelector(".hero-inscription-layer--greek");
    const terminalNode = document.querySelector(".hero-inscription-layer--terminal");
    const nodes = [englishNode, greekNode, terminalNode];
    const setPhase = (seconds) => {
      for (const node of nodes) {
        node.style.animationDelay = `-${seconds}s`;
        node.style.animationPlayState = "paused";
      }
      return {
        english: Number(window.getComputedStyle(englishNode).opacity),
        greek: Number(window.getComputedStyle(greekNode).opacity),
        terminal: Number(window.getComputedStyle(terminalNode).opacity),
      };
    };

    return {
      englishPhase: setPhase(0.5),
      greekPhase: setPhase(8.4),
      terminalPhase: setPhase(17),
    };
  });

  expect(phases.englishPhase.english).toBeGreaterThan(0.85);
  expect(phases.englishPhase.greek).toBeLessThan(0.2);
  expect(phases.englishPhase.terminal).toBeLessThan(0.2);
  expect(phases.greekPhase.greek).toBeGreaterThan(0.85);
  expect(phases.greekPhase.english).toBeLessThan(0.2);
  expect(phases.greekPhase.terminal).toBeLessThan(0.2);
  expect(phases.terminalPhase.terminal).toBeGreaterThan(0.85);
  expect(phases.terminalPhase.english).toBeLessThan(0.2);
  expect(phases.terminalPhase.greek).toBeLessThan(0.2);
});

test("mobile hero content fits without masked clipping", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 900 });
  await page.goto("/");
  const overflow = await page.evaluate(() => {
    const shell = document.querySelector(".page-shell");
    return {
      hiddenShell: getComputedStyle(shell).overflow === "hidden",
      scrollOverflow: document.documentElement.scrollWidth - window.innerWidth,
    };
  });
  expect(overflow.hiddenShell).toBe(false);
  expect(overflow.scrollOverflow).toBeLessThanOrEqual(1);

  for (const selector of [
    "h1",
    ".hero-actions",
    ".evidence-row",
    ".setup-strip",
    ".machine-note",
    ".hero-visual",
    ".greek-hero-inscription",
  ]) {
    const box = await page.locator(selector).boundingBox();
    expect(box, `${selector} should render`).not.toBeNull();
    expect(box.x, `${selector} left edge`).toBeGreaterThanOrEqual(0);
    expect(box.x + box.width, `${selector} right edge`).toBeLessThanOrEqual(390);
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
  await expect(page.locator(".hero-inscription-layer--english")).toBeVisible();
  await expect(page.locator(".hero-inscription-layer--greek")).toHaveCSS("display", "none");
  await expect(page.locator(".hero-inscription-layer--terminal")).toHaveCSS("display", "none");
  await expect(page.locator("h1")).toBeVisible();
});
