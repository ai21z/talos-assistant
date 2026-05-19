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

test("page renders without browser console errors and has one product h1", async ({ page }) => {
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

test("copy buttons are visible, focusable, and uniquely labelled", async ({ page }) => {
  await page.goto("/");
  const buttons = page.locator(".copy-button");
  const count = await buttons.count();
  expect(count).toBeGreaterThanOrEqual(6);

  const labels = [];
  for (let index = 0; index < count; index += 1) {
    const button = buttons.nth(index);
    await expect(button).toBeVisible();
    await button.focus();
    await expect(button).toBeFocused();
    labels.push(await button.getAttribute("aria-label"));
  }

  expect(new Set(labels).size).toBe(labels.length);
  expect(labels.every((label) => /^Copy .+ command$/.test(label ?? ""))).toBe(true);
});

test("hero CTAs are real links, not placeholder beta actions", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByRole("link", { name: "View on GitHub" })).toHaveAttribute(
    "href",
    "https://github.com/ai21z/talos-cli",
  );
  await expect(page.getByRole("link", { name: "Read the execution contract" })).toHaveAttribute("href", "#contract");
  await expect(page.getByRole("button", { name: "Get beta build" })).toHaveCount(0);
});

test("mobile header and nav remain usable", async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 780 });
  await page.goto("/");
  const primaryNav = page.getByRole("navigation", { name: "Primary navigation" });
  await expect(primaryNav).toBeVisible();
  await expect(primaryNav.getByRole("link", { name: "Product" })).toBeVisible();
  await expect(primaryNav.getByRole("link", { name: "Docs" })).toBeVisible();
  await primaryNav.getByRole("link", { name: "Docs" }).click();
  await expect(page.locator("#docs")).toBeInViewport();
});

test("hero startup terminal image loads", async ({ page }) => {
  await page.goto("/");
  const image = page.locator(".startup-terminal-image");
  await expect(image).toHaveAttribute("src", /(?:\/assets\/img-[^/]+\.png|\.\/design\/img\.png)$/);
  await expect(image).toHaveAttribute("alt", /Talos startup terminal screen/);
  const loaded = await image.evaluate((node) => node instanceof HTMLImageElement && node.complete && node.naturalWidth > 0);
  expect(loaded).toBe(true);
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

  for (const selector of ["h1", ".hero-actions", ".status-row", ".machine-note", ".hero-visual"]) {
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
  await expect(page.locator("h1")).toBeVisible();
});
